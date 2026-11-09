package app.relay.lan;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.json.*;

public class RelayService extends Service {
  public static volatile RelayService instance;
  final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService workers = Executors.newFixedThreadPool(4);
  final Set<Channel> channels = ConcurrentHashMap.newKeySet();
  final Map<String, JSONObject> nearby = new LinkedHashMap<>();
  final Map<String, JSONObject> hubPeers = new LinkedHashMap<>();
  String hubStatus = "Desktop hub is not configured", connectionRoute = "Direct";
  boolean hubSupportsDispatch, hubSupportsEmergency;
  String dispatchMode = "", dispatchName = "", dispatchMessage = "", dispatchError = "";
  String sosStatus = "", pendingSosId = "";
  private String emergencyNotificationId = "";
  long dispatchStartedAt, lastSosAt = -30000;
  int dispatchReceivedAudioFrames;
  volatile boolean dispatchTalking, dispatchRemoteTalking, dispatchCapturing;
  private boolean dispatchVisible;
  private boolean dispatchAudioStarted;
  private volatile AudioRecord dispatchMic;
  private final Object dispatchCaptureLock = new Object();
  private volatile DispatchConnection dispatchConnection;
  private volatile DispatchFloor dispatchFloor;
  private JSONObject dispatchOffer;
  private ConnectionCancellation dispatchCancellation;
  private long dispatchGeneration;
  HubClient hub;
  long hubGeneration;
  boolean hubOfferPending;
  final ThreadPoolExecutor hubWorkers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
  Store store;
  Identity identity;
  volatile SSLServerSocket server;
  volatile boolean running;
  String status = "Starting local receiver…", error = "";
  long revision = 0;
  Channel session;
  String state = "idle", peerName = "", peerFp = "", direction = "", callId = "";
  long callTime, acceptedAt;
  boolean speaker = true, remoteRecording = false;
  volatile boolean remoteTalking;
  boolean headsetRoute;
  volatile boolean talking;
  volatile AudioRecord mic;
  volatile AudioTrack track;
  volatile WavRecorder recorder;
  JSONArray segments = new JSONArray();
  PowerManager.WakeLock wake;
  WifiManager.MulticastLock multicast;
  NsdManager nsd;
  NsdManager.RegistrationListener registration;
  NsdManager.DiscoveryListener discovery;
  final Semaphore handshakes = new Semaphore(3);
  long lastPairRequest;
  volatile long connectionAttempt;
  ConnectionCancellation pendingConnection;
  long lastJournal;
  volatile boolean capturing;
  boolean microphoneEnabled;
  boolean automaticAlertPending;
  boolean shortcutHeld;
  int port = 52743;
  Ringtone ring;
  Ringtone automaticChime;
  Runnable automaticChimeStart;
  SoundPool talkStartSounds;
  int talkStartSample, talkStartStream;
  boolean talkStartLoaded;
  AudioFocusRequest focus;
  long audioFocusGeneration;

  void changed() {
    revision++;
  }

  void problem(String text) {
    error = text;
    changed();
  }

  public void onCreate() {
    super.onCreate();
    instance = this;
    store = new Store(this);
    JSONArray savedEmergencies = store.emergencies();
    for (int i = 0; i < savedEmergencies.length(); i++) {
      JSONObject emergency = savedEmergencies.optJSONObject(i);
      if (emergency != null && emergency.optBoolean("active", true)
          && !emergency.optBoolean("resolved") && !emergency.optBoolean("dismissed")) {
        emergencyNotificationId = emergency.optString("id");
        break;
      }
    }
    running = true;
    JSONObject interrupted = store.pending();
    if (interrupted != null) {
      JSONArray files = interrupted.optJSONArray("recordings");
      if (files != null)
        for (int i = 0; i < files.length(); i++) {
          String n = files.optString(i);
          if (n.matches("relay_[0-9]+_[a-f0-9-]+\\.wav"))
            try {
              File f = new File(new File(getFilesDir(), "recordings"), n);
              if (f.isFile()) WavRecorder.recover(f);
            } catch (Exception ignored) {
            }
        }
      try {
        interrupted.put("result", "Interrupted");
      } catch (Exception ignored) {
      }
      store.history(interrupted);
      store.pending(null);
    }
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "availability", "Local receiver", NotificationManager.IMPORTANCE_LOW));
    NotificationChannel calls =
        new NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
    calls.enableVibration(true);
    nm.createNotificationChannel(calls);
    NotificationChannel emergencies = new NotificationChannel("emergencies", "Relay emergency alerts",
        NotificationManager.IMPORTANCE_HIGH);
    emergencies.setDescription("Emergency messages from your enrolled local Relay desktop hub");
    nm.createNotificationChannel(emergencies);
    NotificationChannel automaticCalls =
        new NotificationChannel(
            "automatic_calls", "Automatic call alerts", NotificationManager.IMPORTANCE_HIGH);
    automaticCalls.setDescription("A short chime when a trusted device connects automatically");
    automaticCalls.setSound(
        android.net.Uri.parse("android.resource://" + getPackageName() + "/raw/automatic_call"),
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build());
    automaticCalls.enableVibration(true);
    automaticCalls.setVibrationPattern(new long[] {0, 200});
    nm.createNotificationChannel(automaticCalls);
    nm.cancel(4);
    idleForeground();
    prepareTalkStartSound();
    workers.execute(
        () -> {
          try {
            Identity id = new Identity();
            SSLServerSocket listener =
                (SSLServerSocket) id.context.getServerSocketFactory().createServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
            listener.setNeedClientAuth(true);
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            listener.setEnabledCipherSuites(
                new String[] {
                  "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
                });
            if (!running) {
              listener.close();
              return;
            }
            identity = id;
            server = listener;
            main.post(
                () -> {
                  status = "Available on your local network";
                  error = "";
                  discover();
                  reconnectHub();
                  changed();
                });
            while (running) {
              SSLSocket s = (SSLSocket) listener.accept();
              if (!Lan.allowed(s.getInetAddress())
                  || channels.size() >= 8
                  || !handshakes.tryAcquire()) {
                s.close();
                continue;
              }
              workers.execute(
                  () -> {
                    try {
                      s.setSoTimeout(8000);
                      s.startHandshake();
                      Channel c = new Channel(s);
                      channels.add(c);
                      c.start();
                    } catch (Exception e) {
                      android.util.Log.w("Relay", "Incoming connection rejected", e);
                      try {
                        s.close();
                      } catch (Exception ignored) {
                      }
                    } finally {
                      handshakes.release();
                    }
                  });
            }
          } catch (Exception e) {
            if (running)
              main.post(
                  () -> {
                    status = "Receiver unavailable";
                    problem("Could not start receiver: " + e.getMessage());
                  });
          }
        });
    main.postDelayed(tick, 1000);
  }

  public int onStartCommand(Intent i, int flags, int startId) {
    if (i != null && "stop".equals(i.getAction())) {
      store.available(false);
      stopSelf();
      return START_NOT_STICKY;
    }
    if (i != null && "decline".equals(i.getAction())) end("Declined", true);
    if (i != null && "dispatchDecline".equals(i.getAction())) declineDispatch();
    if (i != null && "dispatchEnd".equals(i.getAction())) endDispatch();
    store.available(true);
    return START_STICKY;
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  void idleForeground() {
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(1, availability(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(1, availability());
  }

  PendingIntent activity(String action, int id) {
    return PendingIntent.getActivity(
        this,
        id,
        new Intent(this, MainActivity.class)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  PendingIntent service(String action, int id) {
    return PendingIntent.getService(
        this,
        id,
        new Intent(this, RelayService.class).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  Notification availability() {
    return new Notification.Builder(this, "availability")
        .setSmallIcon(app.relay.lan.R.drawable.ic_relay_status)
        .setContentTitle("Relay · Available")
        .setContentText("Listening for paired devices on local Wi-Fi")
        .setContentIntent(activity("open", 0))
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "Go offline", service("stop", 1)).build())
        .build();
  }

  void notifyCall() {
    Notification.Builder b =
        new Notification.Builder(this, "calls")
            .setSmallIcon(R.drawable.ic_relay_status)
            .setContentTitle(peerName + " is calling")
            .setContentText("Local walkie-talkie · tap to answer")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(activity("open", 2))
            .setFullScreenIntent(activity("open", 3), true)
            .setOngoing(true);
    if (Build.VERSION.SDK_INT >= 31)
      b.setStyle(
          Notification.CallStyle.forIncomingCall(
              new Person.Builder().setName(peerName).setImportant(true).build(),
              service("decline", 4),
              activity("answer", 5)));
    else {
      b.addAction(new Notification.Action.Builder(null, "Decline", service("decline", 4)).build());
      b.addAction(new Notification.Action.Builder(null, "Answer", activity("answer", 5)).build());
    }
    getSystemService(NotificationManager.class).notify(2, b.build());
    try {
      ring =
          RingtoneManager.getRingtone(
              this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
      if (Build.VERSION.SDK_INT >= 28) ring.setLooping(true);
      ring.play();
    } catch (Exception ignored) {
    }
  }

  void stopRing() {
    getSystemService(NotificationManager.class).cancel(2);
    if (ring != null) {
      ring.stop();
      ring = null;
    }
  }

  void notifyAutomaticCall() {
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.notify(
        4,
        new Notification.Builder(this, "automatic_calls")
            .setSmallIcon(R.drawable.ic_relay_status)
            .setContentTitle(peerName + " connected automatically")
            .setContentText("Local call connected · tap to open Relay")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(activity("open", 8))
            .setOnlyAlertOnce(true)
            // The call's audio route plays the chime; keep the visual alert quiet.
            .setGroup("relay_automatic_call")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build());
    NotificationChannel channel = notifications.getNotificationChannel("automatic_calls");
    if (channel == null
        || !notifications.areNotificationsEnabled()
        || channel.getImportance() == NotificationManager.IMPORTANCE_NONE
        || (notifications.getCurrentInterruptionFilter()
                != NotificationManager.INTERRUPTION_FILTER_ALL
            && !channel.canBypassDnd())) return;
    AudioManager audio = getSystemService(AudioManager.class);
    if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;
    if (channel.shouldVibrate())
      getSystemService(Vibrator.class)
          .vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
    String selected = store.automaticSound();
    android.net.Uri sound =
        selected == null
            ? channel.getSound()
            : selected.isEmpty() ? null : android.net.Uri.parse(selected);
    if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        || audio.getStreamVolume(AudioManager.STREAM_RING) == 0
        || sound == null) return;
    playAutomaticChime(sound, callId, 0);
  }

  void playAutomaticChime(android.net.Uri sound, String expectedCall, int attempts) {
    if (session == null || !state.equals("active") || !callId.equals(expectedCall)) return;
    AudioManager audio = getSystemService(AudioManager.class);
    if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        || audio.getStreamVolume(AudioManager.STREAM_RING) == 0) return;
    if (headsetRoute
        && Build.VERSION.SDK_INT >= 31
        && !isHeadset(audio.getCommunicationDevice())
        && attempts < 12) {
      automaticChimeStart = () -> playAutomaticChime(sound, expectedCall, attempts + 1);
      main.postDelayed(automaticChimeStart, 250);
      return;
    }
    try {
      stopAutomaticAlert();
      automaticChime = RingtoneManager.getRingtone(this, sound);
      if (automaticChime == null) return;
      automaticChime.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build());
      if (Build.VERSION.SDK_INT >= 28) automaticChime.setLooping(false);
      automaticChime.play();
      main.postDelayed(stopAutomaticChime, store.automaticSoundSeconds() * 1000L);
    } catch (Exception e) {
      stopAutomaticAlert();
      android.util.Log.w("Relay", "Automatic call sound unavailable", e);
    }
  }

  final Runnable stopAutomaticChime = this::stopAutomaticAlert;

  void stopAutomaticAlert() {
    if (automaticChimeStart != null) {
      main.removeCallbacks(automaticChimeStart);
      automaticChimeStart = null;
    }
    main.removeCallbacks(stopAutomaticChime);
    if (automaticChime != null) {
      automaticChime.stop();
      automaticChime = null;
    }
  }

  void activeForeground(boolean microphone) {
    Notification n =
        new Notification.Builder(this, "availability")
            .setSmallIcon(R.drawable.ic_relay_status)
            .setContentTitle("Relay · " + peerName)
            .setContentText("Call connected · microphone only while holding TALK")
            .setContentIntent(activity("open", 0))
            .setOngoing(true)
            .addAction(
                new Notification.Action.Builder(null, "End call", service("decline", 4)).build())
            .build();
    if (Build.VERSION.SDK_INT >= 29) {
      int types =
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
              | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
      if (Build.VERSION.SDK_INT >= 30 && microphone)
        types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      startForeground(1, n, types);
    } else startForeground(1, n);
  }

  void discover() {
    try {
      WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      multicast = wifi.createMulticastLock("Relay discovery");
      multicast.setReferenceCounted(false);
      multicast.acquire();
      nsd = (NsdManager) getSystemService(NSD_SERVICE);
      NsdServiceInfo info = new NsdServiceInfo();
      info.setServiceType("_relaylan._tcp.");
      info.setServiceName("Relay-" + identity.fingerprint.substring(0, 12));
      info.setPort(port);
      info.setAttribute("fp", identity.fingerprint);
      info.setAttribute("name", store.name());
      registration =
          new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) {}

            public void onRegistrationFailed(NsdServiceInfo i, int e) {}

            public void onServiceUnregistered(NsdServiceInfo i) {}

            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
          };
      nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
      discovery =
          new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}

            public void onDiscoveryStopped(String t) {}

            public void onStartDiscoveryFailed(String t, int e) {}

            public void onStopDiscoveryFailed(String t, int e) {}

            public void onServiceLost(NsdServiceInfo i) {
              main.post(
                  () -> {
                    nearby.remove(i.getServiceName());
                    changed();
                  });
            }

            public void onServiceFound(NsdServiceInfo i) {
              if (!i.getServiceName().startsWith("Relay-")) return;
              nsd.resolveService(
                  i,
                  new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo x, int e) {}

                    public void onServiceResolved(NsdServiceInfo x) {
                      try {
                        String fp =
                            new String(
                                x.getAttributes().get("fp"),
                                java.nio.charset.StandardCharsets.UTF_8);
                        String name =
                            new String(
                                x.getAttributes().get("name"),
                                java.nio.charset.StandardCharsets.UTF_8);
                        InetAddress host = x.getHost();
                        if (fp.equals(identity.fingerprint)
                            || !fp.matches("[0-9A-F]{64}")
                            || !Lan.allowed(host)) return;
                        main.post(
                            () -> {
                              nearby.put(
                                  x.getServiceName(),
                                  Store.json(
                                      "fingerprint",
                                      fp,
                                      "name",
                                      safeName(name),
                                      "host",
                                      host.getHostAddress(),
                                      "port",
                                      x.getPort()));
                              store.address(fp, host.getHostAddress(), x.getPort());
                              changed();
                            });
                      } catch (Exception ignored) {
                      }
                    }
                  });
            }
          };
      nsd.discoverServices("_relaylan._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
    } catch (Exception e) {
      problem("Discovery unavailable; pair using the other device's IP address.");
    }
  }

  static String safeName(String name) {
    return name.replaceAll("[\\p{Cntrl}]", "")
        .substring(0, Math.min(32, name.replaceAll("[\\p{Cntrl}]", "").length()));
  }

  void reconnectHub() {
    if (state.startsWith("dispatch")) endDispatch("Desktop manager changed");
    long generation = ++hubGeneration;
    if (hub != null) hub.close();
    for (Channel channel : new ArrayList<>(channels))
      if (!channel.hubFingerprint.isEmpty()) channel.close();
    hub = null;
    hubPeers.clear();
    hubSupportsDispatch = false;
    hubSupportsEmergency = false;
    JSONObject config = store.hub();
    if (!config.optBoolean("enabled")) {
      hubStatus = "Desktop hub is not configured";
      changed();
      return;
    }
    if (identity == null || !running) {
      hubStatus = "Become available to connect to the desktop";
      changed();
      return;
    }
    String version;
    try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
    catch (Exception ignored) { version = "unknown"; }
    hub = new HubClient(identity, store, port, version, new HubClient.Listener() {
      public void status(String value) {
        main.post(() -> {
          if (generation != hubGeneration || !running) return;
          hubStatus = value;
          changed();
        });
      }

      public void directory(JSONArray devices) {
        main.post(() -> {
          if (generation != hubGeneration || !running) return;
          hubPeers.clear();
          for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null) continue;
            String fp = device.optString("fingerprint");
            if (!fp.matches("[A-F0-9]{64}") || fp.equals(identity.fingerprint)) continue;
            hubPeers.put(fp, device);
          }
          changed();
        });
      }

      public void offer(JSONObject offer) {
        main.post(() -> acceptHubOffer(offer, generation));
      }

      public void capabilities(JSONArray capabilities) {
        main.post(() -> {
          if (generation != hubGeneration || !running) return;
          hubSupportsDispatch = false;
          hubSupportsEmergency = false;
          for (int i = 0; i < capabilities.length(); i++) {
            if (capabilities.optString(i).equals("dispatch-v1")) hubSupportsDispatch = true;
            if (capabilities.optString(i).equals("emergency-v1")) hubSupportsEmergency = true;
          }
          changed();
        });
      }

      public void event(JSONObject event) {
        main.post(() -> {
          if (generation == hubGeneration && running) hubEvent(event);
        });
      }
    });
    changed();
  }

  private void hubEvent(JSONObject event) {
    String type = event.optString("type");
    if (type.equals("dispatch_offer")) acceptDispatchOffer(event);
    else if (type.equals("emergency")) receiveEmergency(event);
    else if (type.equals("emergency_snapshot")) {
      if (hub == null || !hubSupportsEmergency || !hub.fingerprint().equals(event.optString("hubId"))) return;
      JSONArray ids = event.optJSONArray("activeIds");
      if (ids == null || ids.length() > 64) return;
      Set<String> activeIds = new HashSet<>();
      for (int i = 0; i < ids.length(); i++) {
        String id = ids.optString(i);
        if (!id.matches("[a-fA-F0-9]{32}")) return;
        activeIds.add(id);
      }
      store.reconcileEmergencies(hub.fingerprint(), activeIds);
      if (!emergencyNotificationId.isEmpty() && !activeIds.contains(emergencyNotificationId)) {
        getSystemService(NotificationManager.class).cancel(6);
        emergencyNotificationId = "";
      }
      changed();
    } else if (type.equals("emergency_resolved")) {
      if (hub == null || !hub.fingerprint().equals(event.optString("by"))
          || !event.optString("id").matches("[a-fA-F0-9]{32}")) return;
      store.resolveEmergency(event.optString("id"), hub.fingerprint(), event.optLong("at", System.currentTimeMillis()));
      if (event.optString("id").equals(emergencyNotificationId)) {
        getSystemService(NotificationManager.class).cancel(6);
        emergencyNotificationId = "";
      }
      changed();
    } else if (type.equals("sos_ack")) {
      if (event.optString("id").equals(pendingSosId)) {
        sosStatus = "SOS confirmed by desktop at " + java.text.DateFormat.getTimeInstance().format(new Date());
        pendingSosId = "";
        changed();
      }
    } else if (type.equals("error")) {
      String code = event.optString("code");
      String message = Store.cleanMessage(event.optString("message", "Desktop refused the request"), 280);
      if (code.startsWith("sos") || code.equals("invalid_sos")
          || (!pendingSosId.isEmpty() && (code.equals("send_failed") || code.equals("unauthorized")))) {
        sosStatus = "SOS not confirmed: " + message;
        pendingSosId = "";
      } else dispatchError = message;
      changed();
    } else if (type.equals("hub_disconnected")) {
      if (state.startsWith("dispatch")) endDispatch("Desktop connection lost");
      if (!pendingSosId.isEmpty()) {
        sosStatus = "SOS not confirmed: desktop connection lost";
        pendingSosId = "";
      }
      changed();
    }
  }

  private void receiveEmergency(JSONObject event) {
    if (hub == null || !hubSupportsEmergency || !event.optString("id").matches("[a-fA-F0-9]{32}")
        || !event.optString("from").matches("[A-F0-9]{64}") || !event.optBoolean("active", true)) return;
    boolean fresh = store.emergency(event, hub.fingerprint());
    if (fresh) {
      emergencyNotificationId = event.optString("id");
      Notification notification = new Notification.Builder(this, "emergencies")
          .setSmallIcon(R.drawable.ic_relay_status)
          .setContentTitle("Relay emergency · " + safeName(event.optString("name", "Desktop")))
          .setContentText(Store.cleanMessage(event.optString("message"), 280))
          .setStyle(new Notification.BigTextStyle().bigText(Store.cleanMessage(event.optString("message"), 280)))
          .setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE)
          .setContentIntent(activity("open", 16)).setAutoCancel(true).build();
      getSystemService(NotificationManager.class).notify(6, notification);
    }
    changed();
  }

  void dismissEmergency(String id) {
    store.dismissEmergency(id);
    if (id.equals(emergencyNotificationId)) {
      getSystemService(NotificationManager.class).cancel(6);
      emergencyNotificationId = "";
    }
    changed();
  }

  void sendSos(String message) {
    if (hub == null || !hub.registered() || !hubSupportsEmergency) {
      sosStatus = "Connect to an approved desktop with emergency support first";
      changed(); return;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - lastSosAt < 30000) {
      sosStatus = "Wait before sending another SOS";
      changed(); return;
    }
    String id = UUID.randomUUID().toString().replace("-", "");
    try {
      hub.sendControl(Store.json("type", "sos", "id", id,
          "message", Store.cleanMessage(message, 280)));
      lastSosAt = now;
      pendingSosId = id;
      sosStatus = "Sending SOS to desktop…";
      main.postDelayed(() -> {
        if (id.equals(pendingSosId)) {
          pendingSosId = "";
          sosStatus = "SOS was not confirmed. Check the desktop connection.";
          changed();
        }
      }, 20000);
    } catch (IOException failure) { sosStatus = "SOS not sent: " + failure.getMessage(); }
    changed();
  }

  private void acceptDispatchOffer(JSONObject offer) {
    if (hub == null || !hub.registered() || !hubSupportsDispatch
        || !hub.fingerprint().equals(offer.optString("from"))
        || !offer.optString("id").matches("[a-fA-F0-9]{32}")) return;
    String mode = offer.optString("mode");
    if (!(mode.equals("call") || mode.equals("broadcast") || mode.equals("emergency"))) return;
    if (dispatchOffer != null && offer.optString("id").equals(dispatchOffer.optString("id"))) return;
    // The canonical emergency alert has its own ID and arrives separately on the control channel.
    // A voice-session ID must never create a second persistent alert that cannot be resolved.
    if (!state.equals("idle")) {
      if (mode.equals("emergency") && !(state.startsWith("dispatch") && dispatchMode.equals("emergency")))
        end("Interrupted by emergency dispatch", true);
      else {
        try { hub.sendControl(Store.json("type", "dispatch_decline", "id", offer.optString("id"), "reason", "Device is busy")); }
        catch (IOException ignored) { }
        return;
      }
    }
    dispatchOffer = offer;
    dispatchMode = mode;
    dispatchName = safeName(offer.optString("name", "Relay dispatch"));
    dispatchMessage = Store.cleanMessage(offer.optString("message"), 280);
    dispatchError = "";
    dispatchStartedAt = 0;
    dispatchAudioStarted = false;
    dispatchTalking = false;
    dispatchRemoteTalking = false;
    peerName = dispatchName;
    connectionRoute = "Desktop dispatch · hub is a participant";
    long generation = ++dispatchGeneration;
    if (mode.equals("call")) {
      state = "dispatchIncoming";
      getSystemService(NotificationManager.class).notify(5,
          new Notification.Builder(this, "calls").setSmallIcon(R.drawable.ic_relay_status)
              .setContentTitle(dispatchName + " is calling").setContentText("Tap to answer desktop dispatch")
              .setCategory(Notification.CATEGORY_CALL).setContentIntent(activity("open", 17))
              .addAction(new Notification.Action.Builder(null, "Decline", service("dispatchDecline", 18)).build())
              .addAction(new Notification.Action.Builder(null, "Answer", activity("dispatchAnswer", 19)).build())
              .setOngoing(true).build());
      main.postDelayed(() -> {
        if (generation == dispatchGeneration && state.equals("dispatchIncoming")) declineDispatch();
      }, 30000);
    } else beginDispatch();
    changed();
  }

  void answerDispatch() {
    if (!state.equals("dispatchIncoming")) return;
    if (!dispatchVisible) { dispatchError = "Open Relay to answer this desktop call"; changed(); return; }
    beginDispatch();
  }

  void declineDispatch() {
    if (!state.startsWith("dispatch")) return;
    if (hub != null && dispatchOffer != null)
      try { hub.sendControl(Store.json("type", "dispatch_decline", "id", dispatchOffer.optString("id"), "reason", "Declined on device")); }
      catch (IOException ignored) { }
    endDispatch("Desktop dispatch declined");
  }

  private void beginDispatch() {
    final HubClient source = hub;
    final JSONObject offer = dispatchOffer;
    if (source == null || !source.registered() || offer == null) {
      endDispatch("Desktop hub is unavailable"); return;
    }
    state = "dispatchConnecting";
    getSystemService(NotificationManager.class).cancel(5);
    final long generation = dispatchGeneration;
    final ConnectionCancellation cancellation = new ConnectionCancellation();
    dispatchCancellation = cancellation;
    try {
      hubWorkers.execute(() -> {
        SSLSocket socket = null;
        try {
          socket = source.attachDispatch(offer, cancellation);
          cancellation.track(socket);
          final DispatchConnection connection = new DispatchConnection(socket, new DispatchConnection.Listener() {
            public void frame(JSONObject frame) {
              main.post(() -> { if (generation == dispatchGeneration) receiveDispatch(frame); });
            }
            public void closed(String reason) {
              main.post(() -> { if (generation == dispatchGeneration) endDispatch(reason); });
            }
          });
          final SSLSocket transport = socket;
          main.post(() -> {
            if (!running || generation != dispatchGeneration || cancellation.isCancelled()) {
              connection.close(); cancellation.release(transport); return;
            }
            try {
              dispatchConnection = connection;
              dispatchFloor = new DispatchFloor(dispatchMode, identity.fingerprint, source.fingerprint());
              dispatchFloor.visible(dispatchVisible);
              dispatchAudioStarted = true;
              activeForeground(false);
              startAudio();
              state = "dispatchActive";
              dispatchStartedAt = System.currentTimeMillis();
              dispatchReceivedAudioFrames = 0;
              android.util.Log.i("Relay", "Desktop dispatch connected: " + dispatchMode);
              dispatchCancellation = null;
              cancellation.release(transport);
              connection.start();
              changed();
            } catch (Exception failure) {
              cancellation.release(transport);
              connection.close();
              endDispatch("Desktop audio could not start: " + failure.getMessage());
            }
          });
        } catch (Exception failure) {
          if (socket != null) try { socket.close(); } catch (IOException ignored) { }
          main.post(() -> { if (generation == dispatchGeneration) endDispatch("Desktop dispatch failed: " + failure.getMessage()); });
        }
      });
    } catch (RejectedExecutionException busy) { endDispatch("Desktop connection is busy"); }
    changed();
  }

  private void receiveDispatch(JSONObject frame) {
    if (!state.equals("dispatchActive") || dispatchConnection == null || dispatchFloor == null) return;
    String type = frame.optString("type");
    try {
      if (type.equals("floor")) {
        dispatchFloor.owner(frame.optString("owner"));
        dispatchRemoteTalking = dispatchFloor.remoteTalking();
        if (dispatchFloor.mayCapture()) startDispatchCapture();
        else stopDispatchCapture();
        changed();
      } else if (type.equals("audio")) {
        byte[] pcm = android.util.Base64.decode(frame.optString("data"), android.util.Base64.NO_WRAP);
        if (pcm.length != 640 || !dispatchRemoteTalking) throw new IOException("Invalid desktop audio frame or floor");
        dispatchReceivedAudioFrames++;
        AudioTrack output = track;
        if (output != null) workersAudio.execute(() -> {
          try { output.write(pcm, 0, pcm.length); } catch (Exception ignored) { }
        });
      } else if (type.equals("end")) endDispatch(Store.cleanMessage(frame.optString("reason", "Desktop dispatch ended"), 160));
      else if (type.equals("error")) endDispatch(Store.cleanMessage(frame.optString("message", "Desktop dispatch failed"), 160));
      else throw new IOException("Unexpected desktop dispatch message");
    } catch (Exception invalid) { endDispatch("Desktop dispatch protocol error"); }
  }

  void dispatchUiVisible(boolean visible) {
    dispatchVisible = visible;
    DispatchFloor floor = dispatchFloor;
    if (floor != null) floor.visible(visible);
    if (!visible && state.equals("dispatchActive")) {
      dispatchTalk(false);
      try { activeForeground(false); } catch (Exception ignored) { }
    }
  }

  void dispatchTalk(boolean on) {
    DispatchFloor floor = dispatchFloor;
    DispatchConnection connection = dispatchConnection;
    if (floor == null || connection == null || !state.equals("dispatchActive") || !dispatchMode.equals("call")) return;
    if (!on) {
      floor.hold(false);
      stopDispatchCapture();
      connection.send(Store.json("type", "talk", "on", false));
      changed(); return;
    }
    if (!dispatchVisible || floor.held()) return;
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      dispatchError = "Allow microphone access in Android settings to talk"; changed(); return;
    }
    try { activeForeground(true); }
    catch (Exception blocked) { dispatchError = "Keep the call screen open to talk"; changed(); return; }
    floor.hold(true);
    connection.send(Store.json("type", "talk", "on", true));
    if (floor.mayCapture()) startDispatchCapture();
    changed();
  }

  private void startDispatchCapture() {
    final DispatchFloor floor = dispatchFloor;
    final DispatchConnection connection = dispatchConnection;
    if (floor == null || connection == null || !floor.mayCapture() || dispatchCapturing) return;
    dispatchCapturing = true;
    dispatchTalking = true;
    final long generation = dispatchGeneration;
    new Thread(() -> {
      AudioRecord recorder = null;
      boolean captureFailed = false;
      try {
        int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
          throw new SecurityException("Microphone permission was revoked");
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
        synchronized (dispatchCaptureLock) {
          dispatchMic = recorder;
          if (!floor.mayCapture() || !dispatchTalking) return;
          recorder.startRecording();
        }
        byte[] pcm = new byte[640];
        while (dispatchTalking && floor.mayCapture() && dispatchConnection == connection) {
          int offset = 0;
          while (offset < pcm.length && dispatchTalking && floor.mayCapture()) {
            int count = recorder.read(pcm, offset, pcm.length - offset);
            if (count <= 0) break;
            offset += count;
          }
          if (offset == pcm.length && dispatchTalking && floor.mayCapture())
            connection.send(Store.json("type", "audio", "data",
                android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP)));
        }
      } catch (Exception failure) {
        captureFailed = true;
        main.post(() -> {
          if (generation == dispatchGeneration) { dispatchError = "Microphone error: " + failure.getMessage(); changed(); }
        });
      } finally {
        if (recorder != null) {
          try { recorder.stop(); } catch (Exception ignored) { }
          recorder.release();
        }
        if (dispatchMic == recorder) dispatchMic = null;
        final boolean failed = captureFailed;
        main.post(() -> {
          if (generation == dispatchGeneration) {
            dispatchTalking = false;
            dispatchCapturing = false;
            if (failed) {
              floor.hold(false);
              connection.send(Store.json("type", "talk", "on", false));
            } else if (floor.mayCapture() && dispatchConnection == connection) startDispatchCapture();
            else if (!floor.held()) connection.send(Store.json("type", "talk", "on", false));
            changed();
          }
        });
      }
    }, "relay-dispatch-microphone").start();
    changed();
  }

  private void stopDispatchCapture() {
    synchronized (dispatchCaptureLock) {
      dispatchTalking = false;
      AudioRecord microphone = dispatchMic;
      if (microphone != null) try { microphone.stop(); } catch (Exception ignored) { }
    }
  }

  void endDispatch() { endDispatch("Desktop dispatch ended on device"); }

  private void endDispatch(String reason) {
    if (!state.startsWith("dispatch") && dispatchConnection == null && dispatchOffer == null) return;
    android.util.Log.i("Relay", "Desktop dispatch ended: " + dispatchMode
        + "; receivedAudioFrames=" + dispatchReceivedAudioFrames + "; reason=" + Store.cleanMessage(reason, 120));
    ++dispatchGeneration;
    stopDispatchCapture();
    dispatchCapturing = false;
    dispatchRemoteTalking = false;
    if (dispatchFloor != null) { dispatchFloor.hold(false); dispatchFloor.visible(false); }
    dispatchFloor = null;
    if (dispatchCancellation != null) dispatchCancellation.close();
    dispatchCancellation = null;
    DispatchConnection connection = dispatchConnection;
    dispatchConnection = null;
    if (connection != null) {
      if (dispatchMode.equals("call")) connection.send(Store.json("type", "talk", "on", false));
      connection.send(Store.json("type", "end", "reason", Store.cleanMessage(reason, 160)));
      connection.closeAfterWrite();
      main.postDelayed(connection::close, 1000);
    }
    dispatchOffer = null;
    dispatchError = reason;
    getSystemService(NotificationManager.class).cancel(5);
    state = "idle";
    if (dispatchAudioStarted) releaseCallAudio();
    dispatchAudioStarted = false;
    changed();
  }

  private void acceptHubOffer(JSONObject offer, long generation) {
    if (!running || generation != hubGeneration || hub == null || !hub.registered()
        || !state.equals("idle") || hubOfferPending || channels.size() >= 8
        || !handshakes.tryAcquire()) return;
    hubOfferPending = true;
    final HubClient source = hub;
    try {
      hubWorkers.execute(() -> {
        Channel channel = null;
        try {
          TlsTunnel tunnel = source.attach(offer);
          channel = new Channel(tunnel, offer.optString("from"), source.fingerprint());
          final Channel accepted = channel;
          main.post(() -> {
            if (!running || generation != hubGeneration || !state.equals("idle")) {
              accepted.close();
              return;
            }
            channels.add(accepted);
            accepted.start();
          });
        } catch (Exception failure) {
          if (channel != null) channel.close();
          android.util.Log.w("Relay", "Relayed peer rejected", failure);
        } finally {
          handshakes.release();
          main.post(() -> hubOfferPending = false);
        }
      });
    } catch (RejectedExecutionException busy) {
      handshakes.release();
      hubOfferPending = false;
    }
  }

  void connectPeer(JSONObject peer, boolean pair) {
    connectMode(peer, peer.optString("fingerprint"), pair ? "pair" : "call");
  }

  void connect(String host, int targetPort, String pin, boolean pair) {
    connectMode(host, targetPort, pin, pair ? "pair" : "call");
  }

  void requestAutomatic(JSONObject contact) {
    connectMode(contact, contact.optString("fingerprint"), "auto_request");
  }

  void revokeAutomatic(JSONObject contact) {
    store.automatic(contact.optString("fingerprint"), false);
    changed();
    if (state.equals("idle"))
      connectMode(contact, contact.optString("fingerprint"), "auto_revoke");
  }

  void connectMode(String host, int targetPort, String pin, String kind) {
    connectMode(Store.json("host", host, "port", targetPort, "fingerprint", pin), pin, kind);
  }

  void connectMode(JSONObject peer, String pin, String kind) {
    final boolean pair = kind.equals("pair"), setup = !kind.equals("call");
    if (identity == null) {
      problem("Receiver is still starting");
      return;
    }
    if (!state.equals("idle")) {
      problem("Finish the current call or pairing first");
      return;
    }
    String clean = Identity.clean(pin);
    if (pair && clean.length() != 12 && clean.length() != 64) {
      problem("Enter the other device's 12-character pairing key");
      return;
    }
    final long attempt = ++connectionAttempt;
    final ConnectionCancellation cancellation = new ConnectionCancellation();
    pendingConnection = cancellation;
    final JSONObject directory = hubPeers.get(clean);
    final String host = peer.optString("host");
    final int targetPort = peer.optInt("port", 52743);
    final HubClient selectedHub = hub;
    state = pair ? "connectingPair" : setup ? "connectingAuto" : "connectingCall";
    peerName = "Connecting…";
    error = "";
    changed();
    workers.execute(
        () -> {
          Channel connected = null;
          try {
            Channel direct = null;
            Exception reachability = null;
            List<JSONObject> endpoints = directEndpoints(peer, directory);
            for (JSONObject endpoint : endpoints) {
              if (!running || attempt != connectionAttempt)
                throw new CancellationException("Connection cancelled");
              try {
                direct = openDirect(endpoint.optString("host"), endpoint.optInt("port", targetPort), attempt,
                    cancellation);
                // Never fall back to a relay after a certificate or pairing mismatch.
                if (!direct.fp.startsWith(clean) || (!pair && store.contact(direct.fp) == null)) {
                  direct.close();
                  throw new SSLPeerUnverifiedException("Pairing key does not match this device. Check its screen.");
                }
                break;
              } catch (SSLException authentication) {
                throw authentication;
              } catch (Exception unreachable) {
                reachability = unreachable;
              }
            }
            if (direct == null) {
              if (!running || attempt != connectionAttempt)
                throw new CancellationException("Connection cancelled");
              if (selectedHub == null || !selectedHub.registered() || clean.length() != 64
                  || directory == null || !directory.optBoolean("online"))
                throw reachability != null ? reachability
                    : new IOException("No approved direct route or online desktop relay is available");
              if (!pair && store.contact(clean) == null)
                throw new SSLPeerUnverifiedException("Pair these devices first");
              direct = new Channel(selectedHub.open(clean, pair ? "pair" : "call", cancellation), clean,
                  selectedHub.fingerprint());
            }
            final Channel c = direct;
            connected = c;
            if (!c.fp.startsWith(clean) || (!pair && store.contact(c.fp) == null))
              throw new Exception(
                  "Pairing key does not match this device. Check the key on its screen.");
            channels.add(c);
            main.post(
                () -> {
                  if (!running || attempt != connectionAttempt || !state.startsWith("connecting")) {
                    c.close();
                    return;
                  }
                  session = c;
                  if (pendingConnection == cancellation) pendingConnection = null;
                  connectionRoute = c.route;
                  peerFp = c.fp;
                  JSONObject contact = store.contact(c.fp);
                  peerName = contact == null ? safeName(peer.optString("name", host)) : contact.optString("name");
                  direction = "Outgoing";
                  state = pair ? "pairOutgoing" : setup ? "autoOutgoing" : "calling";
                  callTime = System.currentTimeMillis();
                  acceptedAt = 0;
                  callId = UUID.randomUUID().toString();
                  segments = new JSONArray();
                  c.send(
                      Store.json(
                          "type",
                          kind,
                          "name",
                          store.name(),
                          "port",
                          port,
                          "version",
                          1,
                          "auto",
                          store.automatic(c.fp)));
                  c.start();
                  changed();
                });
          } catch (Exception e) {
            if (connected != null) connected.close();
            main.post(
                () -> {
                  if (attempt == connectionAttempt && state.startsWith("connecting")) {
                    if (pendingConnection == cancellation) pendingConnection = null;
                    state = "idle";
                    problem("Connection failed: " + e.getMessage());
                  }
                });
          }
        });
  }

  private List<JSONObject> directEndpoints(JSONObject peer, JSONObject directory) {
    List<JSONObject> result = new ArrayList<>();
    addEndpoint(result, peer.optString("host"), peer.optInt("port", 52743));
    JSONArray known = peer.optJSONArray("directEndpoints");
    if (known != null)
      for (int i = 0; i < known.length(); i++) {
        JSONObject endpoint = known.optJSONObject(i);
        if (endpoint != null)
          addEndpoint(result, endpoint.optString("host"), endpoint.optInt("port", 52743));
      }
    JSONObject reported = directory == null ? peer : directory;
    JSONArray addresses = reported.optJSONArray("addresses");
    if (addresses != null)
      for (int i = 0; i < addresses.length(); i++)
        addEndpoint(result, addresses.optString(i), reported.optInt("port", 52743));
    return result;
  }

  private void addEndpoint(List<JSONObject> endpoints, String host, int port) {
    if (endpoints.size() >= 3 || port < 1024 || port > 65535) return;
    try {
      Lan.parse(host);
      for (JSONObject existing : endpoints)
        if (host.equals(existing.optString("host")) && port == existing.optInt("port")) return;
      endpoints.add(Store.json("host", host, "port", port));
    } catch (Exception ignored) { }
  }

  private Channel openDirect(String host, int port, long attempt, ConnectionCancellation cancellation) throws Exception {
    InetAddress address = Lan.parse(host);
    Socket raw = new Socket();
    cancellation.track(raw);
    SSLSocket socket = null;
    try {
      if (!running || attempt != connectionAttempt)
        throw new CancellationException("Connection cancelled");
      raw.connect(new InetSocketAddress(address, port), 3000);
      socket = (SSLSocket) identity.context.getSocketFactory().createSocket(raw, host, port, true);
      cancellation.track(socket);
      socket.setEnabledProtocols(new String[] {"TLSv1.2"});
      socket.setEnabledCipherSuites(new String[] {
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});
      socket.setSoTimeout(8000);
      socket.startHandshake();
      return new Channel(socket);
    } catch (Exception failure) {
      try { if (socket == null) raw.close(); else socket.close(); } catch (Exception ignored) { }
      throw failure;
    } finally {
      cancellation.release(raw);
      cancellation.release(socket);
    }
  }

  private void savePeer(Channel channel, String name) {
    store.saveContact(channel.fp, name, channel.host, channel.remotePort);
    if (!channel.hubFingerprint.isEmpty()) store.relayRoute(channel.fp, channel.hubFingerprint);
  }

  void receive(Channel c, JSONObject j) {
    String type = j.optString("type");
    if (session != c) {
      if (!type.equals("pair")
          && !type.equals("call")
          && !type.equals("auto_request")
          && !type.equals("auto_revoke")) {
        c.close();
        return;
      }
      if (!state.equals("idle")) {
        c.send(Store.json("type", "busy"));
        c.closeAfterWrite();
        return;
      }
      if (j.optInt("version") != 1) {
        c.send(Store.json("type", "reject", "reason", "Unsupported app version"));
        c.closeAfterWrite();
        return;
      }
      if (!type.equals("pair") && store.contact(c.fp) == null) {
        c.send(Store.json("type", "reject", "reason", "Pair these devices first"));
        c.closeAfterWrite();
        return;
      }
      if (type.equals("pair") && SystemClock.elapsedRealtime() - lastPairRequest < 10000) {
        c.send(Store.json("type", "reject", "reason", "Wait 10 seconds before pairing again"));
        c.closeAfterWrite();
        return;
      }
      if (type.equals("pair")) lastPairRequest = SystemClock.elapsedRealtime();
      session = c;
      connectionRoute = c.route;
      peerFp = c.fp;
      peerName = safeName(j.optString("name", "Local device"));
      direction = "Incoming";
      callTime = System.currentTimeMillis();
      acceptedAt = 0;
      callId = UUID.randomUUID().toString();
      segments = new JSONArray();
      c.remotePort = j.optInt("port", 52743);
      state =
          type.equals("pair")
              ? "pairIncoming"
              : type.startsWith("auto_") ? "autoIncoming" : "incoming";
      error = "";
      c.send(Store.json("type", "ringing", "name", store.name(), "port", port));
      if (type.equals("auto_revoke")) {
        store.automatic(c.fp, false);
        c.send(Store.json("type", "auto_revoked"));
        end("Agreement revoked", false);
        status = "Automatic answering disabled for " + peerName;
        return;
      }
      if (state.equals("incoming")) {
        persistCall();
        if (store.automatic(c.fp) && j.optBoolean("auto")) activate(true);
        else {
          if (store.automatic(c.fp) && !j.optBoolean("auto")) store.automatic(c.fp, false);
          notifyCall();
        }
      } else
        getSystemService(NotificationManager.class)
            .notify(
                3,
                new Notification.Builder(this, "calls")
                    .setSmallIcon(R.drawable.ic_relay_status)
                    .setContentTitle(
                        (type.equals("auto_request")
                                ? "Automatic-answer request from "
                                : "Pairing request from ")
                            + peerName)
                    .setContentText("Open Relay to review and approve")
                    .setContentIntent(activity("open", 6))
                    .setAutoCancel(true)
                    .build());
      changed();
      return;
    }
    switch (type) {
      case "auto_ok":
        if (state.equals("autoOutgoing")) {
          store.automatic(c.fp, true);
          c.send(Store.json("type", "auto_ack"));
          end("Agreement enabled", false);
          status = "Automatic answering agreed with " + peerName;
          changed();
        }
        break;
      case "auto_ack":
        if (state.equals("autoApproved")) {
          store.automatic(c.fp, true);
          end("Agreement enabled", false);
          status = "Automatic answering agreed with " + peerName;
          changed();
        }
        break;
      case "auto_revoked":
        store.automatic(c.fp, false);
        end("Agreement revoked", false);
        status = "Automatic answering disabled";
        changed();
        break;
      case "ringing":
        peerName = safeName(j.optString("name", peerName));
        c.remotePort = j.optInt("port", 52743);
        changed();
        break;
      case "pair_ok":
        if (state.equals("pairOutgoing")) {
          savePeer(c, safeName(j.optString("name", peerName)));
          c.send(Store.json("type", "pair_ack"));
          end("Paired", false);
          status = "Device paired. Ready to call.";
        }
        break;
      case "pair_ack":
        if (state.equals("pairApproved")) {
          savePeer(c, peerName);
          end("Paired", false);
          status = "Device paired. Ready to call.";
        }
        break;
      case "accept":
        if (state.equals("calling")) {
          state = "acceptedPending";
          status = "Open Relay to finish connecting the call";
          getSystemService(NotificationManager.class)
              .notify(
                  2,
                  new Notification.Builder(this, "calls")
                      .setSmallIcon(R.drawable.ic_relay_status)
                      .setContentTitle(peerName + " answered")
                      .setContentText("Tap to enable your call audio")
                      .setContentIntent(activity("resumeCall", 7))
                      .build());
          changed();
        }
        break;
      case "ready":
        if (state.equals("acceptedWaiting")) {
          state = "active";
          if (automaticAlertPending) {
            automaticAlertPending = false;
            notifyAutomaticCall();
          }
          changed();
        }
        break;
      case "audio":
        if (state.equals("active")) {
          byte[] pcm = android.util.Base64.decode(j.optString("data"), android.util.Base64.NO_WRAP);
          if (pcm.length != 640) {
            c.close();
            return;
          }
          if (++c.receivedAudioFrames % 250 == 0)
            android.util.Log.i("Relay", "Audio received frames=" + c.receivedAudioFrames);
          AudioTrack out = track;
          if (out != null)
            workersAudio.execute(
                () -> {
                  try {
                    record(pcm, false);
                    out.write(pcm, 0, pcm.length);
                  } catch (Exception ignored) {
                  }
                });
        }
        break;
      case "talk":
        boolean wasRemoteTalking = remoteTalking;
        remoteTalking = j.optBoolean("on");
        if (remoteTalking && talking) {
          if (identity.fingerprint.compareTo(c.fp) > 0) talk(false);
          else remoteTalking = false;
        }
        if (wasRemoteTalking && !j.optBoolean("on") && state.equals("active")) notifyTurnEnd();
        changed();
        break;
      case "record":
        remoteRecording = j.optBoolean("on");
        changed();
        break;
      case "busy":
        problem("That device is busy. Try again shortly.");
        end("Busy", false);
        break;
      case "reject":
        problem(j.optString("reason", "Request declined"));
        end("Declined", false);
        break;
      case "end":
        end(
            state.startsWith("pair")
                ? "Pairing cancelled"
                : acceptedAt > 0 ? "Completed" : "Cancelled",
            false);
        break;
      case "ping":
        c.send(Store.json("type", "pong"));
        break;
      case "pong":
        break;
      default:
        c.close();
    }
  }

  // A small bounded audio queue prevents a slow speaker from growing memory usage.
  final ThreadPoolExecutor workersAudio =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          new ThreadPoolExecutor.DiscardOldestPolicy());

  void prepareTalkStartSound() {
    try {
      talkStartSounds =
          new SoundPool.Builder()
              .setMaxStreams(1)
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                      .build())
              .build();
      talkStartSounds.setOnLoadCompleteListener(
          (pool, sample, status) -> {
            if (pool == talkStartSounds && sample == talkStartSample) {
              talkStartLoaded = status == 0;
              if (!talkStartLoaded)
                android.util.Log.w("Relay", "Talk-start sound could not load: " + status);
            }
          });
      talkStartSample = talkStartSounds.load(this, R.raw.talk_start, 1);
    } catch (Exception e) {
      android.util.Log.w("Relay", "Talk-start sound unavailable", e);
    }
  }

  void playTalkStartSound() {
    if (talkStartSounds == null || !talkStartLoaded) return;
    AudioManager audio = getSystemService(AudioManager.class);
    NotificationManager notifications = getSystemService(NotificationManager.class);
    if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        || notifications.getCurrentInterruptionFilter()
            != NotificationManager.INTERRUPTION_FILTER_ALL
        || audio.getStreamVolume(AudioManager.STREAM_RING) == 0
        || audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) return;
    talkStartStream = talkStartSounds.play(talkStartSample, 1f, 1f, 1, 0, 1f);
  }

  void notifyTurnEnd() {
    AudioTrack output = track;
    if (output == null || (!store.turnEndBeep() && !store.turnEndVibration())) return;
    // Use the same ordered output as speech, so the cue follows its final frame
    // and keeps the selected headset/speaker route. Cues are not recorded.
    workersAudio.execute(
        () -> {
          if (track != output || remoteTalking || talking) return;
          AudioManager audio = getSystemService(AudioManager.class);
          NotificationManager notifications = getSystemService(NotificationManager.class);
          int ringer = audio.getRingerMode();
          if (ringer == AudioManager.RINGER_MODE_SILENT
              || notifications.getCurrentInterruptionFilter()
                  != NotificationManager.INTERRUPTION_FILTER_ALL) return;
          if (store.turnEndVibration()) {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
              vibrator.vibrate(
                  VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
              android.util.Log.i("Relay", "Turn-end vibration");
            }
          }
          if (!store.turnEndBeep()
              || ringer != AudioManager.RINGER_MODE_NORMAL
              || audio.getStreamVolume(AudioManager.STREAM_RING) == 0
              || audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) return;
          try {
            int offset = 0;
            while (offset < TurnEndTone.PCM.length
                && track == output
                && !remoteTalking
                && !talking) {
              int written = output.write(TurnEndTone.PCM, offset, TurnEndTone.PCM.length - offset);
              if (written <= 0) return;
              offset += written;
            }
            if (offset == TurnEndTone.PCM.length)
              android.util.Log.i("Relay", "Turn-end beep queued");
          } catch (Exception e) {
            if (track == output) android.util.Log.w("Relay", "Turn-end beep unavailable", e);
          }
        });
  }

  void approvePair() {
    if (!state.equals("pairIncoming")) return;
    state = "pairApproved";
    session.send(Store.json("type", "pair_ok", "name", store.name()));
    getSystemService(NotificationManager.class).cancel(3);
    changed();
  }

  void activate() {
    activate(false);
  }

  void approveAutomatic() {
    if (!state.equals("autoIncoming")) return;
    state = "autoApproved";
    session.send(Store.json("type", "auto_ok"));
    getSystemService(NotificationManager.class).cancel(3);
    changed();
  }

  void enableMicrophone() {
    if (!microphoneEnabled && (state.equals("active") || state.equals("acceptedWaiting"))) {
      try {
        activeForeground(true);
        microphoneEnabled = true;
      } catch (Exception e) {
        problem("Open the call screen and allow microphone access to talk");
      }
    }
  }

  void activate(boolean automatic) {
    if (!state.equals("incoming") && !state.equals("acceptedPending")) return;
    boolean incoming = state.equals("incoming");
    try {
      activeForeground(!automatic);
      microphoneEnabled = !automatic;
      startAudio();
      stopRing();
      acceptedAt = System.currentTimeMillis();
      state = incoming ? "acceptedWaiting" : "active";
      automaticAlertPending = incoming && automatic;
      persistCall();
      session.send(Store.json("type", incoming ? "accept" : "ready"));
      changed();
    } catch (Exception e) {
      problem("Audio could not start: " + e.getMessage());
      end("Audio error", true);
    }
  }

  void startAudio() throws Exception {
    AudioManager am = getSystemService(AudioManager.class);
    final long focusGeneration = ++audioFocusGeneration;
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change == AudioManager.AUDIOFOCUS_LOSS
                      || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    main.post(() -> {
                      if (focusGeneration == audioFocusGeneration) end("Audio interrupted", true);
                    });
                },
                main)
            .build();
    if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
      throw new IOException("Another app is using call audio");
    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if (!selectHeadset(am)) am.setSpeakerphoneOn(speaker);
    int min =
        AudioTrack.getMinBufferSize(
            16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    track =
        new AudioTrack.Builder()
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
            .setBufferSizeInBytes(Math.max(min, 6400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
    track.play();
    wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Relay:call");
    wake.acquire(2 * 60 * 60 * 1000L);
  }

  void speaker(boolean b) {
    speaker = b;
    AudioManager audio = getSystemService(AudioManager.class);
    if (b || !selectHeadset(audio)) {
      headsetRoute = false;
      audio.setSpeakerphoneOn(b);
    }
    changed();
  }

  static boolean isHeadset(AudioDeviceInfo device) {
    if (device == null) return false;
    int type = device.getType();
    return type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        || type == AudioDeviceInfo.TYPE_USB_HEADSET
        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        || type == AudioDeviceInfo.TYPE_HEARING_AID;
  }

  boolean selectHeadset(AudioManager audio) {
    headsetRoute = false;
    if (Build.VERSION.SDK_INT >= 31) {
      for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
        if (isHeadset(device) && audio.setCommunicationDevice(device)) {
          headsetRoute = true;
          speaker = false;
          return true;
        }
      }
    } else {
      for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
          audio.setSpeakerphoneOn(false);
          headsetRoute = true;
          speaker = false;
          return true;
        }
      }
    }
    return false;
  }

  void talk(boolean on) {
    if (!on) {
      talking = false;
      AudioRecord a = mic;
      if (a != null)
        try {
          a.stop();
        } catch (Exception ignored) {
        }
      // The capture thread sends the release after its final audio packet.
      return;
    }
    if (!state.equals("active") || talking || capturing || remoteTalking || !microphoneEnabled)
      return;
    talking = true;
    capturing = true;
    Channel c = session;
    c.send(Store.json("type", "talk", "on", true));
    playTalkStartSound();
    new Thread(
            () -> {
              AudioRecord a = null;
              try {
                int min =
                    AudioRecord.getMinBufferSize(
                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                  throw new SecurityException("Microphone permission was revoked");
                a =
                    new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, 6400));
                mic = a;
                if (a.getState() != AudioRecord.STATE_INITIALIZED)
                  throw new IOException("Microphone unavailable");
                if (!talking) return;
                a.startRecording();
                byte[] buf = new byte[640];
                int sentFrames = 0;
                while (talking && session == c) {
                  int offset = 0;
                  while (offset < buf.length && talking) {
                    int n = a.read(buf, offset, buf.length - offset);
                    if (n <= 0) break;
                    offset += n;
                  }
                  if (offset == 640 && talking) {
                    record(buf, true);
                    c.send(
                        Store.json(
                            "type",
                            "audio",
                            "data",
                            android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)));
                    if (++sentFrames % 250 == 0)
                      android.util.Log.i("Relay", "Audio sent frames=" + sentFrames);
                  }
                }
              } catch (Exception e) {
                main.post(() -> problem("Microphone error: " + e.getMessage()));
              } finally {
                if (a != null) {
                  try {
                    a.stop();
                  } catch (Exception ignored) {
                  }
                  a.release();
                }
                mic = null;
                talking = false;
                c.send(Store.json("type", "talk", "on", false));
                capturing = false;
              }
            },
            "relay-microphone")
        .start();
  }

  void record(byte[] pcm, boolean local) {
    WavRecorder w = recorder;
    if (w != null)
      try {
        w.append(pcm, local);
      } catch (Exception e) {
        main.post(
            () -> {
              if (recorder == w) {
                stopRecording();
                problem(e.getMessage());
              }
            });
      }
  }

  void startRecording() {
    if (!state.equals("active") || recorder != null) return;
    try {
      recorder = new WavRecorder(this);
      segments.put(recorder.name);
      persistCall();
      session.send(Store.json("type", "record", "on", true));
      changed();
    } catch (Exception e) {
      problem(e.getMessage());
    }
  }

  void persistCall() {
    if (session != null && !state.startsWith("pair") && !state.startsWith("auto"))
      store.pending(
          Store.json(
              "id",
              callId,
              "name",
              peerName,
              "fingerprint",
              peerFp,
              "direction",
              direction,
              "time",
              callTime,
              "duration",
              acceptedAt == 0 ? 0 : (System.currentTimeMillis() - acceptedAt) / 1000,
              "result",
              "Interrupted",
              "recordings",
              segments));
    lastJournal = System.currentTimeMillis();
  }

  void stopRecording() {
    WavRecorder w = recorder;
    recorder = null;
    if (w != null)
      try {
        w.close();
      } catch (Exception e) {
        problem("Could not finalize recording");
      }
    if (session != null) session.send(Store.json("type", "record", "on", false));
    changed();
  }

  void end(String result, boolean send) {
    if (state.startsWith("dispatch")) { endDispatch(result); return; }
    android.util.Log.i("Relay", "Call ended: " + result + "; state=" + state);
    if (talkStartSounds != null) talkStartSounds.stop(talkStartStream);
    stopAutomaticAlert();
    automaticAlertPending = false;
    getSystemService(NotificationManager.class).cancel(4);
    connectionAttempt++;
    ConnectionCancellation pending = pendingConnection;
    pendingConnection = null;
    if (pending != null) pending.close();
    Channel c = session;
    if (c == null) {
      state = "idle";
      changed();
      return;
    }
    String old = state;
    if (send) c.send(Store.json("type", "end"));
    talk(false);
    stopRecording();
    session = null;
    c.closeAfterWrite();
    stopRing();
    getSystemService(NotificationManager.class).cancel(3);
    if (!old.startsWith("pair")
        && !old.startsWith("auto")
        && !old.equals("connectingPair")
        && !old.equals("connectingAuto"))
      store.history(
          Store.json(
              "id",
              callId,
              "name",
              peerName,
              "fingerprint",
              peerFp,
              "direction",
              direction,
              "time",
              callTime,
              "duration",
              acceptedAt == 0 ? 0 : (System.currentTimeMillis() - acceptedAt) / 1000,
              "result",
              result,
              "recordings",
              segments));
    store.pending(null);
    state = "idle";
    microphoneEnabled = false;
    shortcutHeld = false;
    remoteRecording = false;
    remoteTalking = false;
    acceptedAt = 0;
    releaseCallAudio();
    changed();
  }

  private void releaseCallAudio() {
    ++audioFocusGeneration;
    workersAudio.getQueue().clear();
    AudioTrack a = track;
    track = null;
    if (a != null)
      try {
        a.pause();
        a.flush();
        a.release();
      } catch (Exception ignored) {
      }
    if (wake != null && wake.isHeld()) wake.release();
    wake = null;
    AudioManager am = getSystemService(AudioManager.class);
    if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
    am.setMode(AudioManager.MODE_NORMAL);
    am.setSpeakerphoneOn(false);
    headsetRoute = false;
    if (focus != null) {
      am.abandonAudioFocusRequest(focus);
      focus = null;
    }
    if (running) idleForeground();
  }

  final Runnable tick =
      new Runnable() {
        public void run() {
          if (!running) return;
          Channel c = session;
          if (c != null) {
            long now = System.currentTimeMillis();
            if (!state.equals("active") && now - callTime > 45000)
              end(state.equals("incoming") ? "Missed" : "No answer", true);
            else if (now - c.lastRead > 20000) end("Connection lost", true);
            else if (now - c.lastPing > 5000) {
              c.lastPing = now;
              c.send(Store.json("type", "ping"));
            }
          }
          if (session != null && System.currentTimeMillis() - lastJournal > 5000) persistCall();
          main.postDelayed(this, 1000);
        }
      };

  public void onDestroy() {
    running = false;
    ++hubGeneration;
    if (hub != null) hub.close();
    hubWorkers.shutdownNow();
    end("Receiver stopped", true);
    if (talkStartSounds != null) {
      talkStartSounds.release();
      talkStartSounds = null;
      talkStartLoaded = false;
    }
    main.removeCallbacksAndMessages(null);
    for (Channel c : channels) c.close();
    if (server != null)
      try {
        server.close();
      } catch (Exception ignored) {
      }
    if (nsd != null) {
      try {
        if (discovery != null) nsd.stopServiceDiscovery(discovery);
      } catch (Exception ignored) {
      }
      try {
        if (registration != null) nsd.unregisterService(registration);
      } catch (Exception ignored) {
      }
    }
    if (multicast != null && multicast.isHeld()) multicast.release();
    workers.shutdownNow();
    workersAudio.shutdownNow();
    instance = null;
    super.onDestroy();
  }

  final class Channel {
    final Closeable connection;
    final String fp, host, route, hubFingerprint;
    final DataInputStream in;
    final DataOutputStream out;
    final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(128);
    volatile boolean closed = false, drain = false;
    volatile long lastRead = System.currentTimeMillis();
    long lastPing = 0;
    int receivedAudioFrames = 0, remotePort = 52743;

    Channel(SSLSocket s) throws Exception {
      connection = s;
      host = s.getInetAddress().getHostAddress();
      fp = Identity.fingerprint(s.getSession().getPeerCertificates()[0]);
      route = Lan.sameSubnet(s.getInetAddress()) ? "Direct" : "Routed direct";
      hubFingerprint = "";
      s.setSoTimeout(20000);
      s.setTcpNoDelay(true);
      in = new DataInputStream(s.getInputStream());
      out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
    }

    Channel(TlsTunnel tunnel, String peerFingerprint, String hubFingerprint) throws Exception {
      if (!tunnel.fingerprint.equals(peerFingerprint)) {
        tunnel.close();
        throw new SSLPeerUnverifiedException("Relayed peer identity mismatch");
      }
      connection = tunnel;
      fp = tunnel.fingerprint;
      host = "";
      route = "Via desktop hub";
      this.hubFingerprint = hubFingerprint;
      in = new DataInputStream(tunnel.input());
      out = new DataOutputStream(new BufferedOutputStream(tunnel.output()));
    }

    void start() {
      new Thread(
              () -> {
                try {
                  while (!closed) {
                    JSONObject j = queue.poll(1, TimeUnit.SECONDS);
                    if (j == null) {
                      if (drain) break;
                      continue;
                    }
                    byte[] bytes = j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.flush();
                  }
                } catch (Exception e) {
                  if (!closed) android.util.Log.w("Relay", "Channel send failed", e);
                } finally {
                  close();
                }
              },
              "relay-send")
          .start();
      new Thread(
              () -> {
                long now = SystemClock.elapsedRealtime();
                PacketRateLimiter packets = new PacketRateLimiter(256, 100, now);
                // Microphones produce 50 frames/second. Wi-Fi can batch frames;
                // assess their sustained rate on the reader, not delayed UI callbacks.
                PacketRateLimiter audio = new PacketRateLimiter(150, 70, now);
                try {
                  while (!closed) {
                    int size = in.readInt();
                    if (size < 2 || size > 16384) throw new IOException("Bad packet size");
                    if (!packets.acquire(SystemClock.elapsedRealtime()))
                      throw new IOException("Sustained packet rate exceeded");
                    byte[] bytes = new byte[size];
                    in.readFully(bytes);
                    JSONObject j =
                        new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    if (j.optString("type").equals("audio")
                        && !audio.acquire(SystemClock.elapsedRealtime()))
                      throw new IOException("Sustained audio rate exceeded");
                    lastRead = System.currentTimeMillis();
                    main.post(
                        () -> {
                          if (!closed || drain)
                            try {
                              receive(this, j);
                            } catch (Exception e) {
                              android.util.Log.w("Relay", "Packet handling failed", e);
                              close();
                            }
                        });
                  }
                } catch (Exception e) {
                  if (!closed) android.util.Log.w("Relay", "Channel receive failed", e);
                } finally {
                  close();
                }
              },
              "relay-receive")
          .start();
    }

    void send(JSONObject j) {
      if (!closed && !queue.offer(j)) {
        android.util.Log.w("Relay", "Channel send queue full");
        close();
      }
    }

    void closeAfterWrite() {
      drain = true;
      main.postDelayed(this::close, 1200);
    }

    void close() {
      if (closed) return;
      closed = true;
      channels.remove(this);
      try {
        connection.close();
      } catch (Exception ignored) {
      }
      main.post(
          () -> {
            if (session == this)
              end(
                  acceptedAt > 0
                      ? "Connection lost"
                      : state.equals("incoming") ? "Missed" : "Disconnected",
                  false);
          });
    }
  }
}
