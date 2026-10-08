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
  Store store;
  Identity identity;
  volatile SSLServerSocket server;
  volatile boolean running;
  String status = "Starting local receiver…", error = "";
  long revision = 0;
  Channel session;
  String state = "idle", peerName = "", peerFp = "", direction = "", callId = "";
  long callTime, acceptedAt;
  boolean speaker = true, remoteRecording = false, remoteTalking = false;
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
  long connectionAttempt;
  long lastJournal;
  volatile boolean capturing;
  boolean microphoneEnabled;
  boolean shortcutHeld;
  int port = 52743;
  Ringtone ring;
  AudioFocusRequest focus;

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
    idleForeground();
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

  void connect(String host, int targetPort, String pin, boolean pair) {
    connectMode(host, targetPort, pin, pair ? "pair" : "call");
  }

  void requestAutomatic(JSONObject contact) {
    connectMode(
        contact.optString("host"),
        contact.optInt("port", 52743),
        contact.optString("fingerprint"),
        "auto_request");
  }

  void revokeAutomatic(JSONObject contact) {
    store.automatic(contact.optString("fingerprint"), false);
    changed();
    if (state.equals("idle"))
      connectMode(
          contact.optString("host"),
          contact.optInt("port", 52743),
          contact.optString("fingerprint"),
          "auto_revoke");
  }

  void connectMode(String host, int targetPort, String pin, String kind) {
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
    state = pair ? "connectingPair" : setup ? "connectingAuto" : "connectingCall";
    peerName = "Connecting…";
    error = "";
    changed();
    workers.execute(
        () -> {
          SSLSocket s = null;
          try {
            InetAddress address = Lan.parse(host);
            if (targetPort < 1024 || targetPort > 65535) throw new Exception("Invalid local port");
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(address, targetPort), 6000);
            s =
                (SSLSocket)
                    identity.context.getSocketFactory().createSocket(raw, host, targetPort, true);
            s.setEnabledProtocols(new String[] {"TLSv1.2"});
            s.setEnabledCipherSuites(
                new String[] {
                  "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
                });
            s.setSoTimeout(8000);
            s.startHandshake();
            Channel c = new Channel(s);
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
                  peerFp = c.fp;
                  JSONObject contact = store.contact(c.fp);
                  peerName = contact == null ? host : contact.optString("name");
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
            if (s != null)
              try {
                s.close();
              } catch (Exception ignored) {
              }
            main.post(
                () -> {
                  if (attempt == connectionAttempt && state.startsWith("connecting")) {
                    state = "idle";
                    problem("Connection failed: " + e.getMessage());
                  }
                });
          }
        });
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
          store.saveContact(c.fp, safeName(j.optString("name", peerName)), c.host, c.remotePort);
          c.send(Store.json("type", "pair_ack"));
          end("Paired", false);
          status = "Device paired. Ready to call.";
        }
        break;
      case "pair_ack":
        if (state.equals("pairApproved")) {
          store.saveContact(c.fp, peerName, c.host, c.remotePort);
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
          if (SystemClock.elapsedRealtime() - c.audioWindow > 1000) {
            c.audioWindow = SystemClock.elapsedRealtime();
            c.audioCount = 0;
          }
          if (++c.audioCount > 70) {
            c.close();
            return;
          }
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
        remoteTalking = j.optBoolean("on");
        if (remoteTalking && talking) {
          if (identity.fingerprint.compareTo(c.fp) > 0) talk(false);
          else remoteTalking = false;
        }
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
                    main.post(() -> end("Audio interrupted", true));
                },
                main)
            .build();
    if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
      throw new IOException("Another app is using call audio");
    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
    am.setSpeakerphoneOn(speaker);
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
    getSystemService(AudioManager.class).setSpeakerphoneOn(b);
    changed();
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
      if (session != null) session.send(Store.json("type", "talk", "on", false));
      return;
    }
    if (!state.equals("active") || talking || capturing || remoteTalking || !microphoneEnabled)
      return;
    talking = true;
    capturing = true;
    Channel c = session;
    c.send(Store.json("type", "talk", "on", true));
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
                capturing = false;
                c.send(Store.json("type", "talk", "on", false));
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
    connectionAttempt++;
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
    am.setMode(AudioManager.MODE_NORMAL);
    am.setSpeakerphoneOn(false);
    if (focus != null) {
      am.abandonAudioFocusRequest(focus);
      focus = null;
    }
    if (running) idleForeground();
    changed();
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
    end("Receiver stopped", true);
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
    final SSLSocket socket;
    final String fp, host;
    final DataInputStream in;
    final DataOutputStream out;
    final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(128);
    volatile boolean closed = false, drain = false;
    volatile long lastRead = System.currentTimeMillis();
    long lastPing = 0, audioWindow = 0;
    int audioCount = 0, remotePort = 52743;

    Channel(SSLSocket s) throws Exception {
      socket = s;
      host = s.getInetAddress().getHostAddress();
      fp = Identity.fingerprint(s.getSession().getPeerCertificates()[0]);
      s.setSoTimeout(20000);
      in = new DataInputStream(s.getInputStream());
      out = new DataOutputStream(s.getOutputStream());
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
                } catch (Exception ignored) {
                } finally {
                  close();
                }
              },
              "relay-send")
          .start();
      new Thread(
              () -> {
                long window = SystemClock.elapsedRealtime();
                int count = 0;
                try {
                  while (!closed) {
                    int size = in.readInt();
                    if (size < 2 || size > 16384) throw new IOException("Bad packet size");
                    if (SystemClock.elapsedRealtime() - window > 1000) {
                      window = SystemClock.elapsedRealtime();
                      count = 0;
                    }
                    if (++count > 100) throw new IOException("Packet rate exceeded");
                    byte[] bytes = new byte[size];
                    in.readFully(bytes);
                    JSONObject j =
                        new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    lastRead = System.currentTimeMillis();
                    main.post(
                        () -> {
                          if (!closed || drain)
                            try {
                              receive(this, j);
                            } catch (Exception e) {
                              close();
                            }
                        });
                  }
                } catch (Exception ignored) {
                } finally {
                  close();
                }
              },
              "relay-receive")
          .start();
    }

    void send(JSONObject j) {
      if (!closed && !queue.offer(j)) close();
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
        socket.close();
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

