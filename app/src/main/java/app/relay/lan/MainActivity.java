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
  // INK is the text colour and the dark button fill; BG fills secondary buttons and fields.
  static final int INK = 0xff15172b,
      MUTED = 0xff6c7189,
      BG = 0xfff1f2f9,
      ACCENT = 0xff5b5fe0,
      TEXT = INK,
      RED = 0xffe5484d,
      PAGE = 0xfff7f8fc,
      MINT = 0xff10a37f,
      SOFT = 0xffecedfd,
      RED_SOFT = 0xfffdecee,
      LINE = 0xffe6e8f1;
  Store store;
  LinearLayout root, content;
  String tab = "Devices", renderKey = "", shownScreen = "";
  TextView callClock, callHint, callRecording, callError;
  Button talkButton, recordButton, speakerButton;
  RelayArt.Orb talkOrb;
  WaveView callWave;
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
    getWindow().setStatusBarColor(PAGE);
    getWindow().setNavigationBarColor(PAGE);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
    l.setBackground(RelayStyle.surface(this, color, 24));
    RelayStyle.shadow(l, color == ACCENT ? 8 : 3);
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
    Button b = button(title, bg, fg, run);
    if (bg == BG) {
      // Secondary actions read as list rows: icon, label, chevron.
      b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      b.setCompoundDrawablesRelativeWithIntrinsicBounds(
          RelayStyle.icon(this, RelayStyle.iconFor(title), ACCENT, 20),
          null,
          RelayStyle.icon(this, "chevron", MUTED, 16),
          null);
    }
    l.addView(b, new LinearLayout.LayoutParams(-1, -2));
  }

  TextView centered(TextView v) {
    v.setGravity(Gravity.CENTER_HORIZONTAL);
    return v;
  }

  TextView avatar(String name, int size, int textSize) {
    TextView a =
        text(
            name.isEmpty() ? "R" : name.substring(0, 1).toUpperCase(Locale.ROOT),
            textSize,
            ACCENT,
            true);
    a.setGravity(Gravity.CENTER);
    a.setBackground(RelayStyle.surface(this, SOFT, size / 2));
    return a;
  }

  /** A round, icon-only button. */
  Button iconButton(String icon, int size, int bg, int fg, String description, Runnable action) {
    Button b = button("", bg, fg, action);
    b.setTextSize(0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp((size - 20) / 2), 0, 0, 0);
    b.setCompoundDrawablePadding(0);
    b.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, icon, fg, 20), null, null, null);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x2215172b),
            RelayStyle.surface(this, bg, size / 2),
            RelayStyle.surface(this, Color.WHITE, size / 2)));
    b.setContentDescription(description);
    return b;
  }

  void deviceHeader(LinearLayout into, String name, String detail, Runnable call, Runnable more) {
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.addView(avatar(name, 46, 19), new LinearLayout.LayoutParams(dp(46), dp(46)));
    LinearLayout label = column();
    TextView title = text(name, 17, INK, true);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.addView(title);
    label.addView(text(detail, 13, MUTED, false));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(12), 0, dp(6), 0);
    line.addView(label, lp);
    if (call != null)
      line.addView(
          iconButton("phone", 46, ACCENT, Color.WHITE, "Call " + name, call),
          new LinearLayout.LayoutParams(dp(46), dp(46)));
    if (more != null) {
      LinearLayout.LayoutParams options = new LinearLayout.LayoutParams(dp(40), dp(46));
      options.leftMargin = dp(4);
      line.addView(iconButton("more", 40, 0x00ffffff, MUTED, "Options for " + name, more), options);
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
      callRecording.setTextColor(s.recorder != null || s.remoteRecording ? RED : MUTED);
      if (callWave != null) callWave.setColor(s.talking ? MINT : ACCENT);
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
    callWave = null;
    root = column();
    root.setClipChildren(true);
    root.setBackground(new RelayArt.Backdrop());
    root.setPadding(dp(20), dp(10), dp(20), dp(10));
    if (Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(20), bars.top + dp(10), dp(20), bars.bottom + dp(10));
            return insets;
          });
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    ImageView mark = new ImageView(this);
    mark.setImageDrawable(RelayStyle.icon(this, "radio", Color.WHITE, 22));
    mark.setScaleType(ImageView.ScaleType.CENTER);
    mark.setBackground(RelayStyle.surface(this, ACCENT, 13));
    top.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
    TextView logo = text("relay", 23, INK, true);
    LinearLayout.LayoutParams logoLayout = new LinearLayout.LayoutParams(0, -2, 1);
    logoLayout.leftMargin = dp(11);
    top.addView(logo, logoLayout);
    TextView badge = text(s == null ? "Offline" : "Online", 12, s == null ? MUTED : MINT, true);
    GradientDrawable dot = new GradientDrawable();
    dot.setShape(GradientDrawable.OVAL);
    dot.setColor(s == null ? MUTED : MINT);
    dot.setSize(dp(8), dp(8));
    badge.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    badge.setCompoundDrawablePadding(dp(7));
    badge.setBackground(RelayStyle.surface(this, Color.WHITE, 20));
    badge.setPadding(dp(12), dp(7), dp(13), dp(7));
    top.addView(badge);
    root.addView(top);
    gap(root, 14);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(2), dp(2), dp(2), dp(10));
    content = column();
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    boolean home = state.equals("idle") || state.equals("offline");
    if (!home) {
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
    // Slide the cards in when the screen changes, not on every data refresh.
    String screen = home ? tab : state;
    if (!screen.equals(shownScreen)) {
      shownScreen = screen;
      int order = 0;
      for (int i = 0; i < content.getChildCount(); i++) {
        View child = content.getChildAt(i);
        if (child instanceof Space) continue;
        child.setAlpha(0);
        child.setTranslationY(dp(14));
        child
            .animate()
            .alpha(1)
            .translationY(0)
            .setStartDelay(Math.min(order++, 6) * 45L)
            .setDuration(280)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
            .start();
      }
    }
    if (!home) return;
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(dp(6), dp(6), dp(6), dp(6));
    nav.setBackground(RelayStyle.surface(this, Color.WHITE, 28));
    RelayStyle.shadow(nav, 6);
    for (String item : new String[] {"Devices", "History", "Settings"}) {
      boolean selected = item.equals(tab);
      Button b =
          button(
              item,
              selected ? SOFT : 0x00ffffff,
              selected ? ACCENT : MUTED,
              () -> {
                tab = item;
                renderKey = "";
                refresh();
              });
      b.setTextSize(12);
      b.setPadding(dp(4), dp(8), dp(4), dp(8));
      b.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(0x1f15172b),
              RelayStyle.surface(this, selected ? SOFT : 0x00ffffff, 22),
              RelayStyle.surface(this, Color.WHITE, 22)));
      b.setCompoundDrawablesRelativeWithIntrinsicBounds(
          null,
          RelayStyle.icon(
              this,
              item.equals("Devices") ? "users" : item.equals("History") ? "history" : "settings",
              selected ? ACCENT : MUTED,
              21),
          null,
          null);
      b.setCompoundDrawablePadding(dp(4));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(2), 0, dp(2), 0);
      nav.addView(b, p);
    }
    LinearLayout.LayoutParams navLayout = new LinearLayout.LayoutParams(-1, -2);
    navLayout.setMargins(dp(2), dp(4), dp(2), dp(4));
    root.addView(nav, navLayout);
  }

  void heading(String title, String sub) {
    content.addView(text(title, 27, INK, true));
    gap(content, 4);
    content.addView(text(sub, 14, MUTED, false));
    gap(content, 18);
  }

  void devices(RelayService s) {
    heading("Your devices", "Talk to someone on the same Wi-Fi.");
    LinearLayout hero = card(content, ACCENT);
    hero.setPadding(dp(20), dp(20), dp(20), dp(20));
    LinearLayout status = new LinearLayout(this);
    status.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout words = column();
    words.addView(text(s == null ? "Ready to connect" : "Ready for calls", 21, Color.WHITE, true));
    gap(words, 4);
    words.addView(
        text(
            s == null
                ? "Turn on availability to receive calls from your paired devices."
                : "Receive calls while Relay is in the background.",
            14,
            0xddffffff,
            false));
    status.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    if (s != null) {
      LinearLayout.LayoutParams waveLayout = new LinearLayout.LayoutParams(dp(52), dp(44));
      waveLayout.leftMargin = dp(12);
      status.addView(new WaveView(this, 7, Color.WHITE).ambient(), waveLayout);
    }
    hero.addView(status);
    gap(hero, 6);
    addButton(
        hero,
        s == null ? "Become available" : "Pair a device",
        Color.WHITE,
        ACCENT,
        () -> {
          if (s == null) startReceiver();
          else pairing(null);
        });
    if (s != null && !s.error.isEmpty()) {
      TextView e = text(s.error, 13, RED, false);
      content.addView(e);
      gap(content, 12);
    }
    content.addView(text("Paired devices", 16, INK, true));
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
      row.setPadding(dp(14), dp(14), dp(10), dp(14));
      deviceHeader(
          row,
          c.optString("name"),
          c.optBoolean("auto") ? "Automatic calls agreed" : "Paired · " + c.optString("host"),
          () -> {
            if (s == null) {
              toast("Become available first");
              return;
            }
            s.connect(
                c.optString("host"), c.optInt("port", 52743), c.optString("fingerprint"), false);
          },
          () -> contactOptions(c, s));
    }
    if (s != null && !s.nearby.isEmpty()) {
      boolean nearbyHeading = false;
      for (JSONObject n : new ArrayList<>(s.nearby.values()))
        if (store.contact(n.optString("fingerprint")) == null) {
          if (!nearbyHeading) {
            gap(content, 4);
            content.addView(text("Nearby devices", 16, INK, true));
            gap(content, 10);
            nearbyHeading = true;
          }
          LinearLayout row = card(content, Color.WHITE);
          deviceHeader(row, n.optString("name"), n.optString("host"), null, null);
          addButton(row, "Pair", BG, INK, () -> pairing(n));
        }
    }
  }

  EditText field(String hint, String value) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(TEXT);
    e.setHighlightColor(0x335b5fe0);
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

  void addAction(LinearLayout l, String title, int bg, int fg, Runnable run) {
    gap(l, 10);
    l.addView(button(title, bg, fg, run), new LinearLayout.LayoutParams(-1, -2));
  }

  void note(LinearLayout l, String value, int size, int color, boolean bold) {
    l.addView(centered(text(value, size, color, bold)));
  }

  void callScreen(RelayService s, String state) {
    boolean live = state.equals("active") || state.equals("acceptedWaiting");
    boolean pairingState = state.startsWith("pair") || state.equals("connectingPair");
    if (!live) heading(pairingState ? "Connect a device" : "Local call", "Private · same Wi-Fi");
    LinearLayout box = card(content, Color.WHITE);
    box.setPadding(dp(20), dp(22), dp(20), dp(20));
    if (!live) {
      boolean asking =
          state.equals("incoming") || state.equals("pairIncoming") || state.equals("autoIncoming");
      FrameLayout badge = new FrameLayout(this);
      View rings = new View(this);
      rings.setBackground(new RelayArt.Pulse(asking ? MINT : ACCENT, .5f));
      badge.addView(rings, new FrameLayout.LayoutParams(-1, -1));
      badge.addView(
          avatar(s.peerName, 72, 28), new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
      LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(dp(136), dp(136));
      badgeLayout.gravity = Gravity.CENTER_HORIZONTAL;
      box.addView(badge, badgeLayout);
      gap(box, 4);
    }
    note(box, s.peerName, 23, INK, true);
    gap(box, 8);
    if (state.equals("autoIncoming")) {
      note(box, "A mutual automatic-answer agreement", 16, ACCENT, true);
      gap(box, 10);
      note(
          box,
          "Calls between these devices will connect on speaker without tapping Answer. Your"
              + " microphone stays off until you hold TALK. Either device can revoke this"
              + " agreement.",
          14,
          MUTED,
          false);
      gap(box, 8);
      addAction(box, "Agree and enable", ACCENT, INK, s::approveAutomatic);
      addAction(box, "Decline", RED_SOFT, RED, () -> s.end("Agreement declined", true));
    } else if (state.startsWith("auto") || state.equals("connectingAuto")) {
      note(box, "Waiting for the other device to agree…", 15, MUTED, false);
      gap(box, 8);
      addAction(box, "Cancel request", BG, INK, () -> s.end("Agreement cancelled", true));
    } else if (state.equals("pairIncoming")) {
      note(box, "Wants to pair with your device", 15, MUTED, false);
      gap(box, 14);
      note(box, "Requesting device key", 12, MUTED, true);
      TextView key = centered(text(s.peerFp.substring(0, 12), 20, ACCENT, true));
      key.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
      key.setLetterSpacing(.08f);
      box.addView(key);
      gap(box, 10);
      note(
          box,
          "Approve only if you recognize the device and requested this connection.",
          14,
          MUTED,
          false);
      gap(box, 8);
      addAction(box, "Approve pairing", ACCENT, INK, s::approvePair);
      addAction(box, "Decline", RED_SOFT, RED, () -> s.end("Declined", true));
    } else if (pairingState) {
      note(box, "Waiting for pairing approval…", 15, MUTED, false);
      gap(box, 8);
      addAction(box, "Cancel", BG, INK, () -> s.end("Pairing cancelled", true));
    } else if (state.equals("incoming")) {
      note(box, "Incoming local call", 16, MINT, true);
      gap(box, 6);
      note(box, "Answer to connect, then hold TALK to speak.", 14, MUTED, false);
      gap(box, 8);
      addAction(
          box,
          "Answer",
          MINT,
          INK,
          () -> {
            if (audioPermission()) s.activate();
            else {
              answerPending = true;
              requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
            }
          });
      addAction(box, "Decline", RED_SOFT, RED, () -> s.end("Declined", true));
    } else if (live) {
      callClock = centered(text("00:00", 16, ACCENT, true));
      callClock.setFontFeatureSettings("tnum");
      box.addView(callClock);
      gap(box, 14);
      callWave = new WaveView(this, 31, ACCENT);
      callWave.source = () -> s.talking ? s.micLevel : s.remoteTalking ? s.remoteLevel : 0;
      box.addView(callWave, new LinearLayout.LayoutParams(-1, dp(56)));
      gap(box, 10);
      callHint = centered(text("Hold to talk. Release to listen.", 14, MUTED, false));
      box.addView(callHint);
      gap(box, 10);
      talkButton = button("Hold to\ntalk", ACCENT, INK, () -> {});
      talkButton.setTextSize(16);
      talkButton.setPadding(dp(24), dp(34), dp(24), dp(30));
      talkButton.setContentDescription("Hold to talk. Release to listen.");
      talkButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
          null, RelayStyle.icon(this, "mic", Color.WHITE, 26), null, null);
      talkButton.setCompoundDrawablePadding(dp(6));
      talkOrb = new RelayArt.Orb();
      talkButton.setBackground(talkOrb);
      talkButton.setStateListAnimator(null);
      talkButton.setOnTouchListener(
          (v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              v.animate().scaleX(.94f).scaleY(.94f).setDuration(120).start();
              s.talk(true);
              return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
              v.animate().scaleX(1).scaleY(1).setDuration(160).start();
              s.talk(false);
              v.performClick();
              return true;
            }
            return true;
          });
      talkButton.setMinHeight(0);
      LinearLayout.LayoutParams talkLayout = new LinearLayout.LayoutParams(dp(176), dp(176));
      talkLayout.gravity = Gravity.CENTER_HORIZONTAL;
      box.addView(talkButton, talkLayout);
      gap(box, 8);
      callRecording = centered(text("Encrypted · only paired devices", 12, MUTED, false));
      box.addView(callRecording);
      gap(box, 16);
      LinearLayout controls = new LinearLayout(this);
      speakerButton = button("Speaker on", BG, INK, () -> s.speaker(!s.speaker));
      recordButton =
          button(
              "Record call",
              BG,
              INK,
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
      addAction(box, "End call", RED, INK, () -> s.end("Completed", true));
      callError = centered(text(s.error, 13, RED, false));
      gap(box, 10);
      box.addView(callError);
    } else {
      note(box, "Calling… waiting for an answer", 15, MUTED, false);
      gap(box, 8);
      addAction(box, "Cancel call", BG, INK, () -> s.end("Cancelled", true));
    }
  }

  void settings(RelayService s) {
    heading("Settings", "Personalize Relay and stay connected.");
    LinearLayout identity = card(content, Color.WHITE);
    deviceHeader(identity, store.name(), "This device", null, null);
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
        ACCENT,
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
    if (hasShortcutService())
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
        ACCENT,
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

  /** The accessibility service is only declared in the adbOnly flavor. */
  boolean hasShortcutService() {
    try {
      getPackageManager()
          .getServiceInfo(new android.content.ComponentName(this, ButtonShortcutService.class), 0);
      return true;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return false;
    }
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
          null,
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
