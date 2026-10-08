package app.relay.lan;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.DateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  static final int INK = 0xff241c43,
      MUTED = 0xffc3bad7,
      BG = 0xff17182f,
      LIME = 0xffd6c6f3,
      TEXT = 0xfff4effc,
      RED = 0xffe9a0b4;
  Store store;
  LinearLayout root, content;
  String tab = "Devices", renderKey = "";
  TextView callClock, callHint, callRecording, callError;
  Button talkButton, recordButton, speakerButton;
  CosmicDrawable.Orb talkOrb;
  MediaPlayer player;
  Ringtone alertPreview;
  final Runnable stopAlertPreview = this::stopAlertPreview;
  boolean visible = false, answerPending = false, startPending = false;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Runnable poll =
      new Runnable() {
        public void run() {
          if (visible) {
            refresh();
            handler.postDelayed(this, 400);
          }
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    store = new Store(this);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().getDecorView().setSystemUiVisibility(0);
    if (Build.VERSION.SDK_INT >= 26) getWindow().getDecorView().setSystemUiVisibility(0);
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(false);
      setTurnScreenOn(false);
    }
    consumeIntent(getIntent());
    if (store.available() && RelayService.instance == null) startReceiver();
  }

  void consumeIntent(Intent i) {
    if (i != null && "answer".equals(i.getAction())) answerPending = true;
    if (i != null
        && ("answer".equals(i.getAction())
            || "callShortcut".equals(i.getAction())
            || "talkShortcut".equals(i.getAction()))) {
      if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true);
        setTurnScreenOn(true);
      } else
        getWindow()
            .addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
  }

  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    consumeIntent(i);
    renderKey = "";
    refresh();
  }

  public void onResume() {
    super.onResume();
    visible = true;
    handler.post(poll);
  }

  public void onPause() {
    visible = false;
    handler.removeCallbacks(poll);
    RelayService s = RelayService.instance;
    if (s != null) s.talk(false);
    stopPlayer();
    stopAlertPreview();
    super.onPause();
  }

  boolean audioPermission() {
    return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  void startReceiver() {
    ArrayList<String> permissions = new ArrayList<>();
    if (!audioPermission()) permissions.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!permissions.isEmpty()) {
      startPending = true;
      requestPermissions(permissions.toArray(new String[0]), 10);
      return;
    }
    startPending = false;
    store.available(true);
    startForegroundService(new Intent(this, RelayService.class));
    renderKey = "";
  }

  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 10) {
      if (!audioPermission()) {
        new RelayDialog.Builder(this)
            .setTitle("Microphone access needed")
            .setMessage(
                "Relay needs microphone permission to send your voice while you hold TALK. Enable"
                    + " it in App info → Permissions.")
            .setPositiveButton(
                "App info",
                (d, w) ->
                    startActivity(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("Close", null)
            .show();
        return;
      }
      if (Build.VERSION.SDK_INT >= 33
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED) {
        new RelayDialog.Builder(this)
            .setTitle("Allow incoming call alerts")
            .setMessage(
                "Enable notifications in Android settings so calls can reach you when Relay is"
                    + " closed.")
            .setPositiveButton(
                "Settings",
                (d, w) ->
                    startActivity(
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
            .setNegativeButton("Later", null)
            .show();
      }
      if (startPending) {
        startPending = false;
        store.available(true);
        startForegroundService(new Intent(this, RelayService.class));
      }
      renderKey = "";
    }
  }

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(radius));
    return g;
  }

  TextView text(String value, int size, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color == INK ? TEXT : color);
    if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setLineSpacing(dp(3), 1);
    return v;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setClipChildren(false);
    l.setClipToPadding(false);
    return l;
  }

  void gap(LinearLayout l, int height) {
    Space s = new Space(this);
    l.addView(s, new LinearLayout.LayoutParams(1, dp(height)));
  }

  LinearLayout card(LinearLayout into, int color) {
    LinearLayout l = column();
    l.setPadding(dp(18), dp(18), dp(18), dp(18));
    l.setBackground(RelayStyle.surface(this, color, 22));
    RelayStyle.shadow(l, 3);
    into.addView(l, new LinearLayout.LayoutParams(-1, -2));
    gap(into, 14);
    return l;
  }

  Button button(String label, int color, int txt, Runnable action) {
    Button b = RelayDialog.button(this, label, color, txt);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void addButton(LinearLayout l, String title, int bg, int fg, Runnable run) {
    gap(l, 10);
    l.addView(button(title, bg, fg, run), new LinearLayout.LayoutParams(-1, -2));
  }

  void deviceHeader(LinearLayout into, String name, String detail, Runnable more) {
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    TextView avatar =
        text(name.isEmpty() ? "R" : name.substring(0, 1).toUpperCase(Locale.ROOT), 20, INK, true);
    avatar.setGravity(Gravity.CENTER);
    avatar.setTextColor(INK);
    avatar.setBackground(RelayStyle.surface(this, LIME, 16));
    line.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout label = column();
    TextView title = text(name, 18, INK, true);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.addView(title);
    label.addView(text(detail, 12, MUTED, false));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(12), 0, dp(6), 0);
    line.addView(label, lp);
    if (more != null) {
      Button options = button("", BG, INK, more);
      options.setTextSize(0);
      options.setPadding(dp(14), 0, dp(14), 0);
      options.setCompoundDrawablesRelativeWithIntrinsicBounds(
          RelayStyle.icon(this, "more", TEXT, 20), null, null, null);
      options.setContentDescription("Options for " + name);
      line.addView(options, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    into.addView(line, new LinearLayout.LayoutParams(-1, -2));
  }

  void refresh() {
    RelayService s = RelayService.instance;
    String state = s == null ? "offline" : s.state;
    if (!state.equals("idle") && !state.equals("offline")) stopAlertPreview();
    if (s != null
        && (state.equals("active") || state.equals("acceptedWaiting"))
        && audioPermission()) s.enableMicrophone();
    if (s != null && state.equals("active") && s.shortcutHeld && !s.talking) s.talk(true);
    if (s != null && state.equals("acceptedPending") && audioPermission()) {
      s.activate();
      state = s.state;
    }
    if (s != null && answerPending && state.equals("incoming")) {
      answerPending = false;
      if (audioPermission()) {
        s.activate();
        state = s.state;
      } else requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
    }
    String key =
        tab
            + state
            + (s == null ? "" : s.peerFp)
            + (s == null ? "" : s.identity == null ? "" : s.identity.fingerprint);
    if (!state.equals("active") && !state.equals("acceptedWaiting"))
      key +=
          (s == null ? "" : s.revision)
              + store.contacts().toString()
              + store.history().toString()
              + Lan.address()
              + store.name();
    if (!key.equals(renderKey)) {
      if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(
            state.equals("incoming") || state.equals("active") || state.equals("acceptedWaiting"));
        setTurnScreenOn(state.equals("incoming"));
      }
      renderKey = key;
      render(s, state);
    }
    if (s != null
        && callClock != null
        && (state.equals("active") || state.equals("acceptedWaiting"))) {
      long seconds = s.acceptedAt == 0 ? 0 : (System.currentTimeMillis() - s.acceptedAt) / 1000;
      updateText(callClock, String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
      updateText(
          callHint,
          state.equals("acceptedWaiting")
              ? "Waiting for the other device's audio…"
              : s.remoteTalking
                  ? s.peerName + " is speaking"
                  : s.talking ? "You’re transmitting" : "Hold to talk. Release to listen.");
      updateText(talkButton, s.talking ? "Talking…" : "Hold to\ntalk");
      if (talkOrb != null) talkOrb.transmitting(s.talking);
      talkButton.setEnabled(state.equals("active") && !s.remoteTalking);
      talkButton.setAlpha(talkButton.isEnabled() ? 1 : .5f);
      updateText(
          callRecording,
          s.recorder != null
              ? "● Recording on this device · both voices"
              : s.remoteRecording
                  ? "● The other device is recording"
                  : "Encrypted · only paired devices");
      callRecording.setTextColor(s.recorder != null || s.remoteRecording ? LIME : 0xffb4c8c0);
      updateText(recordButton, s.recorder == null ? "Record call" : "Stop recording");
      updateText(speakerButton, s.speaker ? "Speaker on" : s.headsetRoute ? "Headset" : "Earpiece");
      updateText(callError, s.error);
      callError.setVisibility(s.error.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  void updateText(TextView view, String value) {
    if (!view.getText().toString().equals(value)) view.setText(value);
  }

  void render(RelayService s, String state) {
    callClock = null;
    root = column();
    root.setClipChildren(true);
    root.setBackground(new CosmicDrawable());
    root.setPadding(dp(18), dp(8), dp(18), dp(8));
    if (Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18), bars.top + dp(8), dp(18), bars.bottom + dp(8));
            return insets;
          });
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView logo = text("relay", 23, INK, true);
    logo.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, "radio", TEXT, 26), null, null, null);
    logo.setCompoundDrawablePadding(dp(9));
    logo.setMinHeight(dp(48));
    top.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
    TextView badge = text(s == null ? "Offline" : "Online", 12, s == null ? MUTED : INK, true);
    badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, s == null ? "power" : "wifi", s == null ? MUTED : 0xffb8e8d9, 15),
        null,
        null,
        null);
    badge.setCompoundDrawablePadding(dp(5));
    badge.setBackground(RelayStyle.surface(this, Color.WHITE, 20));
    badge.setPadding(dp(12), dp(7), dp(12), dp(7));
    top.addView(badge);
    root.addView(top);
    gap(root, 12);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(true);
    scroll.setClipChildren(true);
    scroll.setPadding(dp(3), dp(3), dp(3), dp(4));
    content = column();
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (!state.equals("idle") && !state.equals("offline")) {
      callScreen(s, state);
    } else
      switch (tab) {
        case "History":
          history();
          break;
        case "Settings":
          settings(s);
          break;
        default:
          devices(s);
      }
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(dp(5), dp(5), dp(5), dp(5));
    nav.setBackground(RelayStyle.surface(this, Color.WHITE, 22));
    RelayStyle.shadow(nav, 4);
    for (String item : new String[] {"Devices", "History", "Settings"}) {
      Button b =
          button(
              item,
              item.equals(tab) ? LIME : Color.WHITE,
              item.equals(tab) ? INK : MUTED,
              () -> {
                tab = item;
                renderKey = "";
                refresh();
              });
      b.setTextSize(13);
      b.setPadding(dp(4), dp(7), dp(4), dp(7));
      b.setCompoundDrawablesRelativeWithIntrinsicBounds(
          null,
          RelayStyle.icon(
              this,
              item.equals("Devices") ? "users" : item.equals("History") ? "history" : "settings",
              item.equals(tab) ? INK : MUTED,
              21),
          null,
          null);
      b.setCompoundDrawablePadding(dp(4));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(3), 0, dp(3), 0);
      nav.addView(b, p);
    }
    if (state.equals("idle") || state.equals("offline")) root.addView(nav);
  }

  void heading(String title, String sub) {
    content.addView(text(title, 25, INK, true));
    gap(content, 6);
    content.addView(text(sub, 14, MUTED, false));
    gap(content, 16);
  }

  void devices(RelayService s) {
    heading("Your devices", "Talk to someone on the same Wi-Fi.");
    LinearLayout hero = card(content, INK);
    LinearLayout status = new LinearLayout(this);
    status.setGravity(Gravity.CENTER_VERTICAL);
    ImageView orb = new ImageView(this);
    orb.setImageDrawable(new CosmicDrawable.Orb());
    orb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    status.addView(orb, new LinearLayout.LayoutParams(dp(44), dp(44)));
    TextView statusTitle = text(s == null ? "Ready to connect" : "Ready for calls", 21, TEXT, true);
    LinearLayout.LayoutParams statusLabel = new LinearLayout.LayoutParams(0, -2, 1);
    statusLabel.leftMargin = dp(10);
    status.addView(statusTitle, statusLabel);
    hero.addView(status);
    gap(hero, 8);
    hero.addView(
        text(
            s == null
                ? "Turn on availability to receive calls from your paired devices."
                : "Receive calls while Relay is in the background.",
            14,
            0xffc4d5ce,
            false));
    addButton(
        hero,
        s == null ? "Become available" : "Pair a device",
        LIME,
        INK,
        () -> {
          if (s == null) startReceiver();
          else pairing(null);
        });
    if (s != null && !s.error.isEmpty()) {
      TextView e = text(s.error, 13, RED, false);
      content.addView(e);
      gap(content, 12);
    }
    LinearLayout label = new LinearLayout(this);
    label.addView(text("Paired devices", 16, INK, true));
    content.addView(label);
    gap(content, 10);
    JSONArray contacts = store.contacts();
    if (contacts.length() == 0) {
      LinearLayout empty = card(content, Color.WHITE);
      empty.addView(text("Your first connection", 18, INK, true));
      gap(empty, 6);
      empty.addView(
          text(
              "Open Relay on another device and scan its QR code. Both devices approve the"
                  + " connection.",
              14,
              MUTED,
              false));
    }
    for (int i = 0; i < contacts.length(); i++) {
      JSONObject c = contacts.optJSONObject(i);
      LinearLayout row = card(content, Color.WHITE);
      deviceHeader(
          row,
          c.optString("name"),
          c.optBoolean("auto") ? "Automatic calls agreed" : "Paired · " + c.optString("host"),
          () -> contactOptions(c, s));
      LinearLayout actions = new LinearLayout(this);
      gap(row, 12);
      Button call =
          button(
              "Call",
              INK,
              Color.WHITE,
              () -> {
                if (s == null) {
                  toast("Become available first");
                  return;
                }
                s.connect(
                    c.optString("host"),
                    c.optInt("port", 52743),
                    c.optString("fingerprint"),
                    false);
              });
      actions.addView(call, new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(actions);
    }
    if (s != null && !s.nearby.isEmpty()) {
      boolean nearbyHeading = false;
      for (JSONObject n : new ArrayList<>(s.nearby.values()))
        if (store.contact(n.optString("fingerprint")) == null) {
          if (!nearbyHeading) {
            content.addView(text("Nearby devices", 16, INK, true));
            gap(content, 10);
            nearbyHeading = true;
          }
          LinearLayout row = card(content, Color.WHITE);
          row.addView(text(n.optString("name"), 18, INK, true));
          row.addView(text(n.optString("host"), 13, MUTED, false));
          addButton(row, "Pair", BG, INK, () -> pairing(n));
        }
    }
  }

  EditText field(String hint, String value) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(TEXT);
    e.setHighlightColor(0x556f85b9);
    e.setHintTextColor(MUTED);
    e.setTextSize(16);
    e.setHint(hint);
    e.setText(value);
    e.setPadding(dp(14), dp(12), dp(14), dp(12));
    e.setBackground(RelayStyle.surface(this, BG, 14));
    LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(-1, -2);
    inputLayout.topMargin = dp(10);
    e.setLayoutParams(inputLayout);
    e.setMinHeight(dp(56));
    return e;
  }

  void pairing(JSONObject n) {
    new RelayDialog.Builder(this)
        .setTitle("Pair a device")
        .setItems(
            new String[] {"Scan QR code", "Show my QR code", "Enter details manually"},
            (d, w) -> {
              if (w == 0) {
                RelayService s = RelayService.instance;
                if (s == null) {
                  toast("Become available first");
                  return;
                }
                if (!s.state.equals("idle")) {
                  toast("Finish the current call or pairing first");
                  return;
                }
                startActivityForResult(new Intent(this, ScannerActivity.class), 30);
              } else if (w == 1) showQr();
              else manualPairing(n);
            })
        .show();
  }

  void showQr() {
    RelayService s = RelayService.instance;
    if (s == null || s.identity == null) {
      toast("Become available first");
      return;
    }
    String host = Lan.address();
    if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
      toast("Connect to a local Wi-Fi network first");
      return;
    }
    try {
      LinearLayout body = column();
      body.setPadding(dp(20), dp(8), dp(20), dp(8));
      body.addView(
          text(
              "On the other device, choose Pair a device → Scan QR code. You’ll approve its request"
                  + " here.",
              14,
              MUTED,
              false));
      gap(body, 14);
      ImageView qr = new ImageView(this);
      qr.setImageBitmap(
          PairingQr.bitmap(
              PairingQr.payload(host, s.port, s.identity.fingerprint, store.name()), 768));
      qr.setAdjustViewBounds(true);
      qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
      qr.setContentDescription("Relay pairing QR code for " + store.name());
      int size = Math.min(dp(290), getResources().getDisplayMetrics().widthPixels - dp(80));
      body.addView(qr, new LinearLayout.LayoutParams(-1, size));
      gap(body, 12);
      body.addView(text(store.name() + " · " + host, 14, INK, true));
      AlertDialog dialog =
          new RelayDialog.Builder(this)
              .setTitle("Scan my QR code")
              .setView(body)
              .setPositiveButton("Done", null)
              .create();
      dialog.setOnShowListener(
          d -> {
            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.screenBrightness = .9f;
            dialog.getWindow().setAttributes(params);
          });
      dialog.show();
    } catch (Exception e) {
      toast("Could not create QR code: " + e.getMessage());
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 31 && result == RESULT_OK && data != null) {
      Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
      store.automaticSound(picked == null ? "" : picked.toString());
      automaticSoundSettings();
      return;
    }
    if (request == 30 && result == RESULT_OK && data != null) {
      try {
        JSONObject peer = PairingQr.parse(data.getStringExtra("pairing"));
        RelayService s = RelayService.instance;
        if (s == null) {
          toast("Become available and scan again");
          return;
        }
        s.connect(peer.optString("host"), peer.optInt("port"), peer.optString("fingerprint"), true);
        renderKey = "";
      } catch (Exception e) {
        toast(e.getMessage());
      }
    }
  }

  void manualPairing(JSONObject n) {
    RelayService s = RelayService.instance;
    if (s == null) return;
    LinearLayout form = column();
    form.setPadding(dp(22), dp(8), dp(22), 0);
    form.addView(
        text(
            "Use the key in the other device’s Settings → Connection details. Both devices must"
                + " approve.",
            14,
            MUTED,
            false));
    EditText host = field("Other device’s IP address", n == null ? "" : n.optString("host"));
    host.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
    form.addView(host);
    EditText key = field("Pairing key · 12 characters", "");
    form.addView(key);
    EditText port = field("Port", String.valueOf(n == null ? 52743 : n.optInt("port", 52743)));
    port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    form.addView(port);
    AlertDialog dialog =
        new RelayDialog.Builder(this)
            .setTitle("Pair a device")
            .setView(form)
            .setPositiveButton("Request pairing", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        int p = Integer.parseInt(port.getText().toString());
                        String k = Identity.clean(key.getText().toString());
                        if (!k.matches("[A-F0-9]{12}|[A-F0-9]{64}")) {
                          key.setError("Use the pairing key shown on the other device");
                          return;
                        }
                        Lan.parse(host.getText().toString().trim());
                        dialog.dismiss();
                        s.connect(host.getText().toString().trim(), p, k, true);
                      } catch (Exception e) {
                        host.setError(e.getMessage());
                      }
                    }));
    dialog.show();
  }

  void contactOptions(JSONObject c, RelayService s) {
    new RelayDialog.Builder(this)
        .setTitle(c.optString("name"))
        .setItems(
            new String[] {
              "Update IP address", "View trusted identity", "Forget device", "Automatic answering"
            },
            (d, w) -> {
              if (w == 3) {
                automaticOptions(c, s);
                return;
              }
              if (w == 0) {
                LinearLayout form = column();
                form.setPadding(dp(20), 0, dp(20), 0);
                EditText ip = field("IP address", c.optString("host"));
                form.addView(ip);
                new RelayDialog.Builder(this)
                    .setTitle("Update local address")
                    .setView(form)
                    .setPositiveButton(
                        "Save",
                        (x, y) -> {
                          try {
                            Lan.parse(ip.getText().toString().trim());
                            store.address(
                                c.optString("fingerprint"),
                                ip.getText().toString().trim(),
                                c.optInt("port", 52743));
                            renderKey = "";
                          } catch (Exception e) {
                            toast(e.getMessage());
                          }
                        })
                    .setNegativeButton("Cancel", null)
                    .show();
              } else if (w == 1)
                new RelayDialog.Builder(this)
                    .setTitle("Pinned certificate")
                    .setMessage(c.optString("fingerprint"))
                    .setPositiveButton("Close", null)
                    .show();
              else
                new RelayDialog.Builder(this)
                    .setTitle("Forget this device?")
                    .setMessage(
                        "It will need mutual pairing approval again before it can call you.")
                    .setPositiveButton(
                        "Forget",
                        (x, y) -> {
                          store.forget(c.optString("fingerprint"));
                          renderKey = "";
                        })
                    .setNegativeButton("Cancel", null)
                    .show();
            })
        .show();
  }

  void automaticOptions(JSONObject contact, RelayService s) {
    boolean enabled = store.automatic(contact.optString("fingerprint"));
    new RelayDialog.Builder(this)
        .setTitle(enabled ? "Automatic calls are agreed" : "Agree to automatic calls?")
        .setMessage(
            "Calls with "
                + contact.optString("name")
                + " can connect on speaker without tapping Answer. Both devices must agree first."
                + " The microphone stays off until TALK is held. Either device can revoke the"
                + " agreement.")
        .setPositiveButton(
            enabled ? "Revoke agreement" : "Request agreement",
            (d, w) -> {
              if (s == null) {
                toast("Become available first");
                return;
              }
              if (enabled) s.revokeAutomatic(contact);
              else s.requestAutomatic(contact);
              renderKey = "";
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  void shortcutOptions(boolean up) {
    new RelayDialog.Builder(this)
        .setTitle(up ? "Volume-up shortcut" : "Volume-down shortcut")
        .setMessage("Choose an action for Relay’s call screen.")
        .setItems(
            new String[] {"System volume", "Answer call", "Hold to talk", "End/decline call"},
            (d, w) -> {
              store.shortcut(
                  up,
                  new String[] {"System volume", "Answer call", "Hold to talk", "End/decline call"}
                      [w]);
              renderKey = "";
            })
        .show();
  }

  void testShortcuts() {
    ButtonShortcutService service = ButtonShortcutService.connected;
    TextView result =
        text(
            service == null
                ? "The shortcut service is not connected. Enable Relay button shortcuts in"
                    + " Accessibility settings."
                : "Press the physical Volume Up and Volume Down buttons. Each press should appear"
                    + " here.",
            16,
            INK,
            false);
    AlertDialog dialog =
        new RelayDialog.Builder(this)
            .setTitle("Test volume buttons")
            .setView(result)
            .setPositiveButton("Done", null)
            .create();
    if (service != null) {
      service.testing = true;
      ButtonShortcutService.lastKey = "Waiting for a physical volume-button press…";
    }
    Runnable update =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            if (service != null) result.setText(ButtonShortcutService.lastKey);
            handler.postDelayed(this, 200);
          }
        };
    dialog.setOnDismissListener(
        d -> {
          if (service != null) service.testing = false;
          handler.removeCallbacks(update);
        });
    dialog.show();
    handler.post(update);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN)
      return super.dispatchKeyEvent(event);
    RelayService s = RelayService.instance;
    if (s == null
        || (!s.state.equals("incoming")
            && !s.state.equals("active")
            && !s.state.equals("acceptedWaiting"))) return super.dispatchKeyEvent(event);
    String action = store.shortcut(key == KeyEvent.KEYCODE_VOLUME_UP);
    if (action.equals("System volume")) return super.dispatchKeyEvent(event);
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (action.equals("Hold to talk")) {
      if (down && audioPermission()) s.enableMicrophone();
      if (!down || event.getRepeatCount() == 0) s.talk(down);
      return true;
    }
    if (down && event.getRepeatCount() == 0) {
      if (action.equals("Answer call") && s.state.equals("incoming")) {
        if (audioPermission()) s.activate();
        else {
          answerPending = true;
          requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
        }
      } else if (action.equals("End/decline call"))
        s.end(s.state.equals("incoming") ? "Declined" : "Completed", true);
    }
    return true;
  }

  void callScreen(RelayService s, String state) {
    if (!state.equals("active") && !state.equals("acceptedWaiting"))
      heading(
          state.startsWith("pair") || state.equals("connectingPair")
              ? "Connect a device"
              : "Local call",
          "Private · same Wi-Fi");
    LinearLayout box = card(content, INK);
    box.addView(text(s.peerName, 23, Color.WHITE, true));
    gap(box, 12);
    if (state.equals("autoIncoming")) {
      box.addView(text("A mutual automatic-answer agreement", 17, LIME, true));
      gap(box, 12);
      box.addView(
          text(
              "Calls between these devices will connect on speaker without tapping Answer. Your"
                  + " microphone stays off until you hold TALK. Either device can revoke this"
                  + " agreement.",
              15,
              0xffc4d5ce,
              false));
      addButton(box, "Agree and enable", LIME, INK, s::approveAutomatic);
      addButton(box, "Decline", 0xff31544e, Color.WHITE, () -> s.end("Agreement declined", true));
    } else if (state.startsWith("auto") || state.equals("connectingAuto")) {
      box.addView(text("Waiting for the other device to agree…", 16, 0xffc4d5ce, false));
      addButton(
          box, "Cancel request", 0xff31544e, Color.WHITE, () -> s.end("Agreement cancelled", true));
    } else if (state.equals("pairIncoming")) {
      box.addView(text("Wants to pair with your device", 16, 0xffc4d5ce, false));
      gap(box, 10);
      box.addView(text("Requesting device key\n" + s.peerFp.substring(0, 12), 18, LIME, true));
      box.addView(
          text(
              "Approve only if you recognize the device and requested this connection.",
              14,
              0xffc4d5ce,
              false));
      addButton(box, "Approve pairing", LIME, INK, s::approvePair);
      addButton(box, "Decline", 0xff31544e, Color.WHITE, () -> s.end("Declined", true));
    } else if (state.startsWith("pair") || state.equals("connectingPair")) {
      box.addView(text("Waiting for pairing approval…", 16, 0xffc4d5ce, false));
      addButton(box, "Cancel", 0xff31544e, Color.WHITE, () -> s.end("Pairing cancelled", true));
    } else if (state.equals("incoming")) {
      box.addView(text("Incoming local call", 17, LIME, true));
      gap(box, 16);
      box.addView(text("Answer to connect, then hold TALK to speak.", 15, 0xffc4d5ce, false));
      addButton(
          box,
          "Answer",
          LIME,
          INK,
          () -> {
            if (audioPermission()) s.activate();
            else {
              answerPending = true;
              requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
            }
          });
      addButton(box, "Decline", 0xff31544e, Color.WHITE, () -> s.end("Declined", true));
    } else if (state.equals("active") || state.equals("acceptedWaiting")) {
      callClock = text("00:00", 20, LIME, true);
      box.addView(callClock);
      gap(box, 14);
      callHint = text("Hold to talk. Release to listen.", 15, 0xffc4d5ce, false);
      box.addView(callHint);
      gap(box, 14);
      talkButton = button("Hold to\ntalk", LIME, INK, () -> {});
      talkButton.setTextSize(18);
      talkButton.setPadding(dp(24), dp(24), dp(24), dp(24));
      talkButton.setContentDescription("Hold to talk. Release to listen.");
      talkButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
          null, RelayStyle.icon(this, "mic", INK, 24), null, null);
      talkButton.setCompoundDrawablePadding(dp(6));
      talkOrb = new CosmicDrawable.Orb();
      talkButton.setBackground(talkOrb);
      talkButton.setOnTouchListener(
          (v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              s.talk(true);
              return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
              s.talk(false);
              v.performClick();
              return true;
            }
            return true;
          });
      talkButton.setMinHeight(0);
      LinearLayout.LayoutParams talkLayout = new LinearLayout.LayoutParams(dp(144), dp(144));
      talkLayout.gravity = Gravity.CENTER_HORIZONTAL;
      box.addView(talkButton, talkLayout);
      gap(box, 18);
      callRecording = text("Encrypted · only paired devices", 12, 0xffb4c8c0, false);
      box.addView(callRecording);
      gap(box, 14);
      LinearLayout controls = new LinearLayout(this);
      speakerButton = button("Speaker on", 0xff31544e, Color.WHITE, () -> s.speaker(!s.speaker));
      recordButton =
          button(
              "Record call",
              0xff31544e,
              Color.WHITE,
              () -> {
                if (s.recorder != null) s.stopRecording();
                else
                  new RelayDialog.Builder(this)
                      .setTitle("Record this conversation?")
                      .setMessage(
                          "Both voices are saved on this device. The other person sees a recording"
                              + " indicator. Make sure everyone agrees before recording.")
                      .setPositiveButton("Start recording", (d, w) -> s.startRecording())
                      .setNegativeButton("Cancel", null)
                      .show();
              });
      controls.addView(speakerButton, new LinearLayout.LayoutParams(0, -2, 1));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(10), 0, 0, 0);
      controls.addView(recordButton, p);
      box.addView(controls);
      addButton(box, "End call", RED, INK, () -> s.end("Completed", true));
      callError = text(s.error, 13, 0xffffc8b9, false);
      gap(box, 10);
      box.addView(callError);
    } else {
      box.addView(text("Calling… waiting for an answer", 16, 0xffc4d5ce, false));
      addButton(box, "Cancel call", 0xff31544e, Color.WHITE, () -> s.end("Cancelled", true));
    }
  }

  void settings(RelayService s) {
    heading("Settings", "Personalize Relay and stay connected.");
    LinearLayout identity = card(content, Color.WHITE);
    deviceHeader(identity, store.name(), "This device", null);
    addButton(
        identity,
        "Rename device",
        BG,
        INK,
        () -> {
          EditText name = field("Device name", store.name());
          new RelayDialog.Builder(this)
              .setTitle("Device name")
              .setView(name)
              .setPositiveButton(
                  "Save",
                  (d, w) -> {
                    store.name(name.getText().toString());
                    toast("Name saved. Restart availability to refresh discovery.");
                    renderKey = "";
                  })
              .setNegativeButton("Cancel", null)
              .show();
        });
    if (s != null && s.identity != null)
      addButton(identity, "Show my QR code", INK, Color.WHITE, this::showQr);
    addButton(identity, "Connection details", BG, INK, () -> identityDetails(s));

    LinearLayout availability = card(content, Color.WHITE);
    availability.addView(text("Availability", 19, INK, true));
    gap(availability, 6);
    availability.addView(
        text(s == null ? "You are offline." : "Ready to receive local calls.", 14, MUTED, false));
    addButton(
        availability,
        s == null ? "Become available" : "Go offline",
        LIME,
        INK,
        () -> {
          if (s == null) startReceiver();
          else {
            store.available(false);
            stopService(new Intent(this, RelayService.class));
          }
          renderKey = "";
        });
    addButton(availability, "Background call settings", BG, INK, this::backgroundSettings);
    addButton(availability, "Automatic call sound", BG, INK, this::automaticSoundSettings);

    LinearLayout feedback = card(content, Color.WHITE);
    feedback.addView(text("Call feedback", 19, INK, true));
    gap(feedback, 6);
    feedback.addView(
        text(
            "Hear a short beep or feel a vibration when the other person releases Talk."
                + " Vibrate mode uses vibration only; silent and Do Not Disturb suppress both.",
            14,
            MUTED,
            false));
    addButton(
        feedback,
        "Turn-end beep · " + (store.turnEndBeep() ? "On" : "Off"),
        BG,
        INK,
        () -> {
          store.turnEndBeep(!store.turnEndBeep());
          renderKey = "";
        });

    addButton(
        feedback,
        "Turn-end vibration · " + (store.turnEndVibration() ? "On" : "Off"),
        BG,
        INK,
        () -> {
          store.turnEndVibration(!store.turnEndVibration());
          renderKey = "";
        });

    LinearLayout shortcuts = card(content, Color.WHITE);
    shortcuts.addView(text("Button shortcuts", 19, INK, true));
    gap(shortcuts, 6);
    shortcuts.addView(text("Use volume buttons to answer or hold to talk.", 14, MUTED, false));
    addButton(
        shortcuts, "Volume up · " + store.shortcut(true), BG, INK, () -> shortcutOptions(true));
    addButton(
        shortcuts, "Volume down · " + store.shortcut(false), BG, INK, () -> shortcutOptions(false));
    addButton(shortcuts, "Background shortcuts", BG, INK, this::backgroundShortcuts);

    LinearLayout about = card(content, Color.WHITE);
    about.addView(text("Private by design", 19, INK, true));
    gap(about, 6);
    about.addView(
        text("Calls stay on your Wi-Fi. Recordings stay on this device.", 14, MUTED, false));
    addButton(
        about,
        "Privacy and storage",
        BG,
        INK,
        () ->
            new RelayDialog.Builder(this)
                .setTitle("Local and private")
                .setMessage(
                    "No accounts, cloud storage, or analytics. Calls use encrypted TLS and pinned"
                        + " device certificates. Recordings stay in private storage until you share"
                        + " or delete them. Uninstalling removes history, recordings, and paired"
                        + " devices. Wi-Fi client isolation can block connections.")
                .setPositiveButton("Done", null)
                .show());
  }

  void identityDetails(RelayService s) {
    String fp = s == null || s.identity == null ? "" : s.identity.fingerprint;
    String details =
        "Device: "
            + store.name()
            + "\nIP: "
            + Lan.address()
            + ":52743"
            + "\nPairing key: "
            + (fp.isEmpty() ? "Turn on availability" : fp.substring(0, 12));
    TextView value = text(details, 16, INK, false);
    value.setTextIsSelectable(true);
    new RelayDialog.Builder(this)
        .setTitle("Connection details")
        .setView(value)
        .setPositiveButton(
            "Copy details",
            (d, w) -> {
              ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                  .setPrimaryClip(
                      ClipData.newPlainText("Relay pairing", details + "\nCertificate: " + fp));
              toast("Pairing details copied");
            })
        .setNegativeButton("Close", null)
        .show();
  }

  Uri automaticSoundUri() {
    String selected = store.automaticSound();
    if (selected != null) return selected.isEmpty() ? null : Uri.parse(selected);
    NotificationChannel channel =
        getSystemService(NotificationManager.class).getNotificationChannel("automatic_calls");
    return channel == null
        ? Uri.parse("android.resource://" + getPackageName() + "/raw/automatic_call")
        : channel.getSound();
  }

  void automaticSoundSettings() {
    Uri sound = automaticSoundUri();
    String title = "Silent";
    if (sound != null) {
      if ("android.resource".equals(sound.getScheme())) title = "Relay chime";
      else {
        Ringtone tone = RingtoneManager.getRingtone(this, sound);
        title = tone == null ? "Selected sound" : tone.getTitle(this);
      }
    }
    LinearLayout body = column();
    body.addView(text("Selected · " + title, 16, INK, true));
    gap(body, 10);
    body.addView(
        text(
            "Plays once when a trusted device connects automatically. Uses your call audio output"
                + " and respects silent/vibrate and Do Not Disturb.",
            14,
            MUTED,
            false));
    AlertDialog[] dialog = new AlertDialog[1];
    addButton(
        body,
        "Choose sound",
        LIME,
        INK,
        () -> {
          dialog[0].dismiss();
          chooseAutomaticSound();
        });
    addButton(body, "Preview sound", BG, INK, this::previewAutomaticSound);
    addButton(
        body,
        "Alert length · up to " + store.automaticSoundSeconds() + " seconds",
        BG,
        INK,
        () -> {
          dialog[0].dismiss();
          new RelayDialog.Builder(this)
              .setTitle("Maximum alert length")
              .setMessage("Short sounds finish naturally. Longer tones stop after this time.")
              .setItems(
                  new String[] {"3 seconds", "5 seconds", "8 seconds"},
                  (d, which) -> {
                    store.automaticSoundSeconds(new int[] {3, 5, 8}[which]);
                    automaticSoundSettings();
                  })
              .setNegativeButton("Cancel", null)
              .show();
        });
    addButton(
        body,
        "Use Relay chime",
        BG,
        INK,
        () -> {
          stopAlertPreview();
          store.automaticSound("android.resource://" + getPackageName() + "/raw/automatic_call");
          dialog[0].dismiss();
          automaticSoundSettings();
        });
    dialog[0] =
        new RelayDialog.Builder(this)
            .setTitle("Automatic call sound")
            .setView(body)
            .setNegativeButton("Done", null)
            .create();
    dialog[0].setOnDismissListener(d -> stopAlertPreview());
    dialog[0].show();
  }

  void chooseAutomaticSound() {
    try {
      startActivityForResult(
          new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
              .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
              .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Automatic call sound")
              .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
              .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
              .putExtra(
                  RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                  RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
              .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, automaticSoundUri()),
          31);
    } catch (ActivityNotFoundException e) {
      toast("This phone has no sound picker. You can still use the Relay chime.");
      automaticSoundSettings();
    }
  }

  void previewAutomaticSound() {
    stopAlertPreview();
    Uri sound = automaticSoundUri();
    if (sound == null) {
      toast("Silent is selected");
      return;
    }
    AudioManager audio = getSystemService(AudioManager.class);
    if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        || audio.getStreamVolume(AudioManager.STREAM_RING) == 0) {
      toast("Enable ringing on your phone to preview the alert");
      return;
    }
    try {
      alertPreview = RingtoneManager.getRingtone(this, sound);
      if (alertPreview == null) throw new IllegalStateException("Sound unavailable");
      alertPreview.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build());
      if (Build.VERSION.SDK_INT >= 28) alertPreview.setLooping(false);
      alertPreview.play();
      handler.postDelayed(stopAlertPreview, store.automaticSoundSeconds() * 1000L);
    } catch (Exception e) {
      stopAlertPreview();
      toast("Could not preview this sound. Please choose another.");
    }
  }

  void stopAlertPreview() {
    handler.removeCallbacks(stopAlertPreview);
    if (alertPreview != null) {
      alertPreview.stop();
      alertPreview = null;
    }
  }

  void backgroundSettings() {
    ArrayList<String> items =
        new ArrayList<>(
            Arrays.asList(
                "Battery settings", "Notification settings", "Automatic call alert sound"));
    if (Build.VERSION.SDK_INT >= 34) items.add("Incoming call screen permission");
    new RelayDialog.Builder(this)
        .setTitle("Receive calls in the background")
        .setMessage(
            "Keep availability on. Battery restrictions or force-stop can prevent calls. Open Relay"
                + " again after restarting your phone.")
        .setItems(
            items.toArray(new String[0]),
            (d, which) -> {
              if (which == 0)
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
              else if (which == 1)
                startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
              else if (which == 2) automaticSoundSettings();
              else
                startActivity(
                    new Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            })
        .setNegativeButton("Close", null)
        .show();
  }

  boolean isRedmi() {
    String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
    return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
  }

  void backgroundShortcuts() {
    ArrayList<String> items =
        new ArrayList<>(Arrays.asList("Test volume buttons", "Accessibility settings"));
    if (isRedmi()) items.add("Redmi / Xiaomi permissions");
    new RelayDialog.Builder(this)
        .setTitle("Background shortcuts")
        .setMessage(
            (ButtonShortcutService.connected == null
                    ? "Service is not connected. "
                    : "Service is connected. ")
                + "Enable Relay button shortcuts in Accessibility to use the buttons after leaving"
                + " Relay. Only volume keys during calls are handled; screen content is not read. A"
                + " sideloaded app may need App info → Allow restricted settings. The power button"
                + " is reserved by Android.")
        .setItems(
            items.toArray(new String[0]),
            (d, which) -> {
              if (which == 0) testShortcuts();
              else if (which == 1)
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
              else
                new RelayDialog.Builder(this)
                    .setTitle("Redmi background permissions")
                    .setMessage(
                        "In App info → Other permissions, allow ‘Open new windows while running in"
                            + " the background’. This lets the call screen open and enable your"
                            + " microphone. Allow ‘Show on Lock screen’ to use it while locked.")
                    .setPositiveButton(
                        "Open App info",
                        (x, y) ->
                            startActivity(
                                new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Close", null)
                    .show();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  void history() {
    heading("Call history", "Recent conversations and recordings.");
    JSONArray history = store.history();
    if (history.length() == 0) {
      LinearLayout empty = card(content, Color.WHITE);
      empty.addView(text("No calls yet", 20, INK, true));
      gap(empty, 8);
      empty.addView(text("Your calls and recordings will appear here.", 14, MUTED, false));
    }
    for (int i = 0; i < Math.min(history.length(), 100); i++) {
      JSONObject call = history.optJSONObject(i);
      LinearLayout entry = card(content, Color.WHITE);
      deviceHeader(
          entry,
          call.optString("name"),
          call.optString("direction") + " · " + call.optString("result"),
          () -> historyOptions(call));
      gap(entry, 10);
      entry.addView(
          text(
              DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                      .format(new Date(call.optLong("time")))
                  + " · "
                  + call.optLong("duration")
                  + " sec",
              12,
              MUTED,
              false));
      JSONArray recordings = call.optJSONArray("recordings");
      if (recordings != null)
        for (int k = 0; k < recordings.length(); k++) {
          String file = recordings.optString(k);
          addButton(
              entry,
              "Recording " + (k + 1) + " · Play / Share",
              BG,
              INK,
              () -> recordingOptions(file));
        }
    }
  }

  void historyOptions(JSONObject call) {
    new RelayDialog.Builder(this)
        .setTitle("Call details")
        .setMessage(
            call.optString("name")
                + " · "
                + call.optString("result")
                + "\nDeleting this entry also removes its saved recordings.")
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              stopPlayer();
              JSONArray recordings = call.optJSONArray("recordings");
              if (recordings != null)
                for (int k = 0; k < recordings.length(); k++)
                  new File(new File(getFilesDir(), "recordings"), recordings.optString(k)).delete();
              store.deleteHistory(call.optString("id"));
              renderKey = "";
            })
        .setNegativeButton("Close", null)
        .show();
  }

  void recordingOptions(String name) {
    new RelayDialog.Builder(this)
        .setTitle("Saved recording")
        .setItems(
            new String[] {"Play recording", "Share WAV file"},
            (d, w) -> {
              Uri uri = Uri.parse("content://app.relay.lan.recordings/" + name);
              if (w == 1) {
                Intent share =
                    new Intent(Intent.ACTION_SEND)
                        .setType("audio/wav")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newRawUri("Recording", uri));
                startActivity(Intent.createChooser(share, "Share recording"));
              } else {
                try {
                  stopPlayer();
                  player = new MediaPlayer();
                  player.setDataSource(this, uri);
                  player.prepare();
                  player.start();
                  AlertDialog dialog =
                      new RelayDialog.Builder(this)
                          .setTitle("Playing saved conversation")
                          .setMessage("Both voices · recorded on this device")
                          .setPositiveButton("Stop", (x, y) -> stopPlayer())
                          .create();
                  dialog.setOnDismissListener(x -> stopPlayer());
                  player.setOnCompletionListener(x -> dialog.dismiss());
                  dialog.show();
                } catch (Exception e) {
                  stopPlayer();
                  toast("Could not play recording: " + e.getMessage());
                }
              }
            })
        .show();
  }

  void stopPlayer() {
    if (player != null) {
      try {
        player.stop();
      } catch (Exception ignored) {
      }
      player.release();
      player = null;
    }
  }

  void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }
}
