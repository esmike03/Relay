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
  // Palette, switched by applyTheme(). LIME is the primary button fill (ON_LIME text on it);
  // as a text color it maps to the green ACCENT highlight. RED maps to DANGER as text.
  // CARD is the surface for cards, rows, dialogs and the nav bar.
  static int INK, MUTED, BG, CARD, SOFT, LINE, LIME, ON_LIME, TEXT, RED, ACCENT, HIGHLIGHT,
      DANGER, RIPPLE;
  static final int SOS_RED = 0xffd92b2b;
  static boolean dark;

  static {
    palette(false);
  }

  /** Reads the saved theme ("light", "dark" or "system") and loads the matching palette. */
  static void applyTheme(Context c) {
    String mode = c.getSharedPreferences("relay", 0).getString("theme", "system");
    boolean night =
        (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    palette(mode.equals("dark") || (mode.equals("system") && night));
  }

  static void palette(boolean night) {
    dark = night;
    if (night) {
      INK = 0xffdde2de;
      TEXT = 0xffe9ece9;
      MUTED = 0xff9ba39d;
      BG = 0xff121413;
      CARD = 0xff1c1f1d;
      SOFT = 0xff2a2e2b;
      LINE = 0xff2e322f;
      LIME = 0xffe3e7e4;
      ON_LIME = 0xff141614;
      RED = 0xff4a2224;
      ACCENT = 0xff5bd394;
      HIGHLIGHT = 0xff1e3b2b;
      DANGER = 0xffff7a70;
      RIPPLE = 0x29ffffff;
    } else {
      INK = 0xff2b2b2b;
      TEXT = 0xff1f1f1f;
      MUTED = 0xff6b6b6b;
      BG = 0xfff3f3f3;
      CARD = 0xffffffff;
      SOFT = 0xffe7e7e7;
      LINE = 0xffdddddd;
      LIME = 0xff3a3a3a;
      ON_LIME = 0xffffffff;
      RED = 0xfff6d5d5;
      ACCENT = 0xff1a8150;
      HIGHLIGHT = 0xffdcf2e5;
      DANGER = 0xffb3261e;
      RIPPLE = 0x1a000000;
    }
  }

  static int textColor(int color) {
    return color == INK ? TEXT : color == LIME ? ACCENT : color == RED ? DANGER : color;
  }
  Store store;
  LinearLayout root, content;
  String tab = "Devices", renderKey = "";
  TextView callClock, callHint, callRecording, callError;
  TextView dispatchClockView, dispatchHintView, dispatchStatusView, sosStatusView;
  Button dispatchTalkButton;
  Button talkButton, recordButton, speakerButton;
  CosmicDrawable.Orb talkOrb;
  LinearLayout navBar;
  // Walkie-talkie style status screen on the Devices tab.
  static final int LCD = 0xff0f1f17, LCD_INK = 0xff7ef0a8, LCD_LABEL = 0xff4f9a6c,
      LCD_GHOST = 0xff23402f;
  TextView lcdClock, lcdBattery, lcdSignal;
  View lcdView;
  CosmicDrawable.CrtOverlay lcdOverlay;
  long glitchUntil;
  final Random glitchRandom = new Random();
  /** Shakes and flickers the radio screen until {@link #glitchUntil}. */
  final Runnable glitchTick =
      new Runnable() {
        public void run() {
          View v = lcdView;
          if (v == null) return;
          if (SystemClock.uptimeMillis() >= glitchUntil) {
            v.setTranslationX(0);
            v.setTranslationY(0);
            v.setAlpha(1);
            return;
          }
          v.setTranslationX((glitchRandom.nextFloat() - .5f) * dp(10));
          v.setTranslationY(glitchRandom.nextInt(4) == 0 ? (glitchRandom.nextFloat() - .5f) * dp(4) : 0);
          v.setAlpha(glitchRandom.nextInt(5) == 0 ? .75f : 1f);
          handler.postDelayed(this, 35);
        }
      };
  static final int CUT = CosmicDrawable.Chamfer.TOP_LEFT | CosmicDrawable.Chamfer.BOTTOM_RIGHT;
  long lcdSampledAt;
  MediaPlayer player;
  Ringtone alertPreview;
  final Runnable stopAlertPreview = this::stopAlertPreview;
  boolean visible = false, answerPending = false, startPending = false;
  boolean dispatchAnswerPending;
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
    applyTheme(this);
    store = new Store(this);
    if (b != null) tab = b.getString("tab", tab);
    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
    RelayStyle.lightSystemBars(getWindow(), BG);
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(false);
      setTurnScreenOn(false);
    }
    consumeIntent(getIntent());
    if (store.available() && RelayService.instance == null) startReceiver();
  }

  /**
   * Saves the theme and swaps the palette in place. A snapshot of the old screen sits on top and
   * fades out, so light and dark dissolve into each other instead of snapping.
   */
  void switchTheme(String mode) {
    android.content.SharedPreferences prefs = getSharedPreferences("relay", 0);
    if (mode.equals(prefs.getString("theme", "system"))) return;
    prefs.edit().putString("theme", mode).apply();
    boolean wasDark = dark;
    applyTheme(this);
    ViewGroup decor = (ViewGroup) getWindow().getDecorView();
    ImageView cover = null;
    android.graphics.Bitmap shot = null;
    if (wasDark != dark
        && android.animation.ValueAnimator.areAnimatorsEnabled()
        && decor.getWidth() > 0
        && decor.getHeight() > 0) {
      try {
        shot =
            android.graphics.Bitmap.createBitmap(
                decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(shot));
        cover = new ImageView(this);
        cover.setImageBitmap(shot);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        decor.addView(cover, new FrameLayout.LayoutParams(-1, -1));
      } catch (OutOfMemoryError e) {
        cover = null;
        shot = null;
      }
    }
    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
    RelayStyle.lightSystemBars(getWindow(), BG);
    renderKey = "";
    refresh();
    if (cover != null) {
      ImageView fading = cover;
      android.graphics.Bitmap image = shot;
      fading
          .animate()
          .alpha(0)
          .setDuration(420)
          .setInterpolator(new android.view.animation.DecelerateInterpolator())
          .withEndAction(
              () -> {
                decor.removeView(fading);
                image.recycle();
              })
          .start();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("tab", tab);
  }

  void consumeIntent(Intent i) {
    if (i != null && "answer".equals(i.getAction())) answerPending = true;
    if (i != null && "dispatchAnswer".equals(i.getAction())) dispatchAnswerPending = true;
    if (i != null
        && ("answer".equals(i.getAction()) || "dispatchAnswer".equals(i.getAction())
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
    RelayService service = RelayService.instance;
    if (service != null) service.dispatchUiVisible(true);
    handler.post(poll);
  }

  public void onPause() {
    visible = false;
    handler.removeCallbacks(poll);
    RelayService s = RelayService.instance;
    if (s != null) { s.talk(false); s.dispatchUiVisible(false); }
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
    v.setTextColor(textColor(color));
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

  void deviceHeader(
      LinearLayout into, String top, String name, String detail, Runnable more) {
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.addView(
        channelTile(top, name.isEmpty() ? "R" : name.substring(0, 1).toUpperCase(Locale.ROOT)),
        new LinearLayout.LayoutParams(dp(50), dp(50)));
    LinearLayout label = column();
    TextView title = text(name, 18, INK, true);
    title.setLineSpacing(0, 1);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.addView(title);
    LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
    dl.topMargin = dp(4);
    label.addView(mono(detail, 11, MUTED, false), dl);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(12), 0, dp(6), 0);
    line.addView(label, lp);
    if (more != null) {
      ImageView options = new ImageView(this);
      options.setImageDrawable(RelayStyle.icon(this, "menu", MUTED, 20));
      options.setScaleType(ImageView.ScaleType.CENTER);
      options.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(RIPPLE), null, null));
      options.setContentDescription("Options for " + name);
      options.setOnClickListener(v -> more.run());
      line.addView(options, new LinearLayout.LayoutParams(dp(44), dp(48)));
    }
    into.addView(line, new LinearLayout.LayoutParams(-1, -2));
  }

  void refresh() {
    RelayService s = RelayService.instance;
    String state = s == null ? "offline" : s.state;
    if (s != null) s.dispatchUiVisible(visible);
    if (s != null && dispatchAnswerPending && state.equals("dispatchIncoming")) {
      dispatchAnswerPending = false;
      s.answerDispatch();
      state = s.state;
    }
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
            + (s == null ? "" : s.identity == null ? "" : s.identity.fingerprint)
            + store.emergencies().toString();
    if (!state.equals("active") && !state.equals("acceptedWaiting") && !state.equals("dispatchActive"))
      key +=
          (s == null ? "" : s.revision)
              + store.contacts().toString()
              + store.history().toString()
              + Lan.address()
              + store.name();
    if (!key.equals(renderKey)) {
      if (dispatchTalkButton != null && s != null) s.dispatchTalk(false);
      if (callClock != null && s != null && s.talking) s.talk(false);
      if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(
            state.equals("incoming") || state.equals("active") || state.equals("acceptedWaiting")
                || state.startsWith("dispatch"));
        setTurnScreenOn(state.equals("incoming") || state.equals("dispatchIncoming"));
      }
      renderKey = key;
      render(s, state);
    }
    if (s != null && sosStatusView != null) updateText(sosStatusView, s.sosStatus);
    updateLcd();
    if (s != null && state.equals("dispatchActive") && dispatchClockView != null) {
      long seconds = s.dispatchStartedAt == 0 ? 0 : (System.currentTimeMillis() - s.dispatchStartedAt) / 1000;
      updateText(dispatchClockView, String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
      updateText(dispatchHintView, s.dispatchTalking ? "You are transmitting to the desktop"
          : s.dispatchRemoteTalking ? "Desktop is speaking"
          : "call".equals(s.dispatchMode) ? "Hold TALK to reply to the desktop" : "Listening · this announcement cannot turn on your microphone");
      updateText(dispatchStatusView, s.dispatchError);
      if (dispatchTalkButton != null) {
        updateText(dispatchTalkButton, s.dispatchTalking ? "Talking…" : "Hold TALK to reply");
        dispatchTalkButton.setEnabled(!s.dispatchRemoteTalking);
      }
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
                  : "Encrypted · " + s.connectionRoute);
      callRecording.setTextColor(s.recorder != null || s.remoteRecording ? ACCENT : MUTED);
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
    dispatchClockView = null; dispatchHintView = null; dispatchStatusView = null;
    dispatchTalkButton = null; sosStatusView = null;
    lcdClock = null; lcdBattery = null; lcdSignal = null; lcdView = null; lcdOverlay = null;
    root = column();
    root.setClipChildren(true);
    root.setBackgroundColor(BG);
    root.setPadding(dp(18), dp(8), dp(18), dp(8));
    if (Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18), bars.top + dp(8), dp(18), bars.bottom + dp(8));
            fitNav();
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
    logo.setGravity(Gravity.CENTER_VERTICAL);
    top.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
    // Status chip: mini LCD with cut top-left and bottom-right corners.
    boolean online = s != null;
    TextView badge =
        online ? lcdText("ONLINE", 12, LCD_INK, true) : mono("OFFLINE", 12, LCD_LABEL, true);
    badge.setLetterSpacing(.12f);
    badge.setGravity(Gravity.CENTER_VERTICAL);
    GradientDrawable bar = shape(online ? 0xff3ddc84 : 0xff35503f, 1);
    bar.setSize(dp(3), dp(12));
    badge.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null);
    badge.setCompoundDrawablePadding(dp(9));
    badge.setBackground(new CosmicDrawable.Chamfer(LCD, dp(9), CUT));
    badge.setPadding(dp(16), 0, dp(18), 0);
    badge.setContentDescription(online ? "Status: online" : "Status: offline");
    top.addView(badge, new LinearLayout.LayoutParams(-2, dp(40)));
    Button sos = button("SOS", RED, INK, () -> sendSosDialog(RelayService.instance));
    boolean sosReady = s != null && s.hubSupportsEmergency;
    // Always tappable: when SOS can't be sent, it looks dimmed and explains why.
    sos.setAlpha(sosReady ? 1f : .5f);
    sos.setContentDescription(
        sosReady
            ? "Send an emergency SOS to the desktop and enrolled devices"
            : "SOS unavailable. Tap to see why");
    // Cut top-left and bottom-right corners, shorter than regular buttons.
    sos.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33ffffff),
            new CosmicDrawable.Chamfer(SOS_RED, dp(9), CUT),
            new CosmicDrawable.Chamfer(Color.WHITE, dp(9), CUT)));
    sos.setMinHeight(0);
    sos.setMinimumHeight(0);
    sos.setPadding(dp(16), 0, dp(18), 0);
    sos.setCompoundDrawablePadding(dp(7));
    sos.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, "alert", Color.WHITE, 17), null, null, null);
    sos.setTextColor(Color.WHITE);
    LinearLayout.LayoutParams sosLayout = new LinearLayout.LayoutParams(-2, dp(40));
    sosLayout.leftMargin = dp(8);
    top.addView(sos, sosLayout);
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
    emergencyBanner(s);
    if (state.startsWith("dispatch")) {
      dispatchScreen(s, state);
    } else if (!state.equals("idle") && !state.equals("offline")) {
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
    navBar = null;
    boolean showNav = state.equals("idle") || state.equals("offline");
    if (Build.VERSION.SDK_INT < 35) setNavigationBarColor(showNav ? CARD : BG);
    if (!showNav) return;
    LinearLayout nav = new LinearLayout(this);
    nav.setGravity(Gravity.CENTER_VERTICAL);
    // White bar with a hairline on top, drawn edge to edge under the system navigation bar.
    android.graphics.drawable.LayerDrawable navBg =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {shape(LINE, 0), shape(CARD, 0)});
    navBg.setLayerInset(1, 0, Math.max(1, dp(1) / 2), 0, 0);
    nav.setBackground(navBg);
    String[][] items = {{"Devices", "users"}, {"History", "history"}, {"Settings", "settings"}};
    for (int i = 0; i < items.length; i++) {
      String item = items[i][0];
      boolean selected = item.equals(tab);
      LinearLayout cell = column();
      cell.setGravity(Gravity.CENTER_HORIZONTAL);
      cell.setPadding(0, dp(4), 0, dp(4));
      cell.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(RIPPLE),
              null,
              shape(Color.WHITE, 16)));
      ImageView icon = new ImageView(this);
      icon.setImageDrawable(
          RelayStyle.icon(this, items[i][1], selected ? ACCENT : MUTED, 22));
      icon.setScaleType(ImageView.ScaleType.CENTER);
      if (selected) icon.setBackground(new CosmicDrawable.Chamfer(HIGHLIGHT, dp(8), CUT));
      cell.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(32)));
      String name = item.toUpperCase(Locale.ROOT);
      TextView label =
          selected ? mono(name, 11, ACCENT, true) : mono(name, 11, MUTED, false);
      label.setLetterSpacing(.12f);
      label.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-2, -2);
      labelLayout.topMargin = dp(6);
      cell.addView(label, labelLayout);
      cell.setClickable(true);
      cell.setFocusable(true);
      cell.setSelected(selected);
      cell.setContentDescription(item + ", tab " + (i + 1) + " of " + items.length);
      cell.setOnClickListener(
          v -> {
            if (item.equals(tab)) return;
            tab = item;
            renderKey = "";
            refresh();
          });
      nav.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    navBar = nav;
    root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
    fitNav();
  }

  /** Stretches the nav bar past the root padding so it reaches the screen edges and bottom. */
  void fitNav() {
    if (navBar == null || root == null) return;
    int side = root.getPaddingLeft(), bottom = root.getPaddingBottom();
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) navBar.getLayoutParams();
    p.setMargins(-side, dp(6), -root.getPaddingRight(), -bottom);
    navBar.setLayoutParams(p);
    navBar.setPadding(side - dp(8), dp(8), root.getPaddingRight() - dp(8), bottom);
  }

  @SuppressWarnings("deprecation")
  void setNavigationBarColor(int color) {
    getWindow().setNavigationBarColor(color);
  }

  /**
   * Turns a raw error into {title, message, hint, detail}. Known network failures get a plain
   * explanation and a fix; anything else is shown as-is.
   */
  static String[] describeError(String raw) {
    boolean desktop = raw.startsWith("Desktop:");
    String body = raw.replaceFirst("^(Desktop|Connection failed):\\s*", "");
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("to /?([0-9a-fA-F.:]+) \\(port (\\d+)\\)").matcher(body);
    String ip = "", where = "";
    if (m.find()) {
      ip = m.group(1);
      where = ip + ":" + m.group(2);
    }
    java.util.regex.Matcher errno = java.util.regex.Pattern.compile("\\b(E[A-Z]{4,})\\b").matcher(body);
    String code = errno.find() ? errno.group(1) : "";
    String target = ip.isEmpty() ? (desktop ? "the desktop" : "the other device") : ip;
    String lower = body.toLowerCase(Locale.ROOT);
    String title, message, hint;
    if (lower.contains("econnrefused") || lower.contains("connection refused")) {
      title = "Connection refused";
      message =
          desktop
              ? "Relay Desktop Manager isn't accepting connections at " + target + "."
              : "The other device isn't accepting calls at " + target + ".";
      hint =
          desktop
              ? "Check that it's running on the PC and allowed through Windows Firewall."
              : "Make sure Relay is open and set to available on that device.";
    } else if (lower.contains("etimedout") || lower.contains("timed out") || lower.contains("timeout")
        // Android's connect timeout reads "failed to connect to … after 5000ms" with no errno.
        || (code.isEmpty() && lower.matches("(?s).*failed to connect.*after \\d+ms.*"))) {
      title = "No response";
      message = (desktop ? "The desktop at " : "The device at ") + target + " didn't respond.";
      hint =
          desktop
              ? "Make sure the PC is awake and on the same network as this phone."
              : "Make sure it's on the same Wi-Fi and Relay is available.";
    } else if (lower.contains("unreach") || lower.contains("no route")) {
      title = "Network unreachable";
      message = "This phone can't reach " + target + ".";
      hint =
          "Connect both devices to the same Wi-Fi. Some networks block devices from seeing each"
              + " other.";
    } else if (lower.contains("certificate") || lower.contains("ssl") || lower.contains("handshake")
        || lower.contains("trust")) {
      title = "Security check failed";
      message = desktop ? "The desktop's identity didn't match." : "The other device's identity didn't match.";
      hint =
          desktop
              ? "Scan the desktop QR code again under Desktop connection."
              : "Pair the devices again if the other phone was reset.";
    } else if (lower.contains("connection lost") || lower.contains("reset") || lower.contains("broken pipe")) {
      title = "Connection lost";
      message = desktop ? "The connection to the desktop dropped." : "The connection dropped.";
      hint = "Check that both devices are awake and on the same network.";
    } else {
      title = "Problem";
      message = body;
      hint = null;
    }
    String detail = where + (code.isEmpty() ? "" : (where.isEmpty() ? "" : " · ") + code);
    return new String[] {title.toUpperCase(Locale.ROOT), message, hint, detail};
  }

  /** Tinted error card: red tick, title, explanation, hint, and an expandable technical line. */
  LinearLayout errorPanel(String raw) {
    String[] e = describeError(raw);
    LinearLayout box = new LinearLayout(this);
    box.setBackground(new CosmicDrawable.Chamfer(dark ? 0xff2b1a1a : 0xfffbecea, dp(9), CUT));
    box.setPadding(dp(14), dp(14), dp(16), dp(14));
    View tick = new View(this);
    tick.setBackground(shape(DANGER, 1));
    box.addView(tick, new LinearLayout.LayoutParams(dp(3), -1));
    LinearLayout col = column();
    TextView title = mono(e[0], 12, DANGER, true);
    title.setLetterSpacing(.12f);
    title.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, "alert", DANGER, 14), null, null, null);
    title.setCompoundDrawablePadding(dp(7));
    col.addView(title);
    gap(col, 6);
    col.addView(text(e[1], 14, TEXT, false));
    if (e[2] != null) {
      gap(col, 4);
      col.addView(text(e[2], 13, MUTED, false));
    }
    if (!e[3].isEmpty() || !e[1].equals(raw)) {
      gap(col, 8);
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setMinimumHeight(dp(28));
      TextView code = mono(e[3].isEmpty() ? "TECHNICAL DETAILS" : e[3], 11, MUTED, false);
      row.addView(code, new LinearLayout.LayoutParams(0, -2, 1));
      TextView toggle = mono("DETAILS", 11, ACCENT, true);
      toggle.setLetterSpacing(.1f);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
      tl.leftMargin = dp(10);
      row.addView(toggle, tl);
      row.setClickable(true);
      row.setFocusable(true);
      row.setContentDescription("Show technical details");
      row.setOnClickListener(
          v -> {
            boolean open = toggle.getText().toString().equals("HIDE");
            code.setSingleLine(open);
            code.setEllipsize(open ? android.text.TextUtils.TruncateAt.END : null);
            code.setText(open ? (e[3].isEmpty() ? "TECHNICAL DETAILS" : e[3]) : raw);
            code.setTextIsSelectable(!open);
            toggle.setText(open ? "DETAILS" : "HIDE");
            row.setContentDescription(open ? "Show technical details" : "Hide technical details");
          });
      col.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
    cl.leftMargin = dp(12);
    box.addView(col, cl);
    return box;
  }

  /** Explains why SOS can't be sent right now and offers the step that fixes it. */
  void sosUnavailable(RelayService service) {
    RelayDialog.Builder dialog =
        new RelayDialog.Builder(this).setTitle("SOS unavailable").setNegativeButton("Close", null);
    if (!store.hub().optBoolean("enabled")) {
      dialog
          .setMessage(
              "SOS is delivered through Relay Desktop Manager on your PC. Set up a desktop"
                  + " connection first.")
          .setPositiveButton("Desktop connection", (d, w) -> desktopSettings());
    } else if (service == null) {
      dialog
          .setMessage(
              "You're offline. Become available so this phone can connect to the desktop.")
          .setPositiveButton(
              "Become available",
              (d, w) -> {
                glitch(350);
                startReceiver();
              });
    } else if (service.hubStatus.startsWith("Connected")) {
      dialog.setMessage(
          "The connected desktop manager doesn't support emergency alerts. Update Relay"
              + " Desktop Manager on the PC.");
    } else {
      dialog
          .setMessage("This phone isn't connected to the desktop, so an SOS can't be delivered.")
          .setView(errorPanel(service.hubStatus))
          .setPositiveButton("Desktop connection", (d, w) -> desktopSettings());
    }
    dialog.show();
  }

  void sendSosDialog(RelayService service) {
    if (service == null || !service.hubSupportsEmergency) {
      sosUnavailable(service);
      return;
    }
    LinearLayout body = column();
    body.addView(text("Send your device name, time and message to the desktop and available "
        + "enrolled devices. Your microphone stays off.", 15, MUTED, false));
    EditText message = field("Emergency message (optional)", "");
    message.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(240)});
    body.addView(message);
    new RelayDialog.Builder(this).setTitle("Send emergency SOS?").setView(body)
        .setPositiveButton("Send SOS", (d, w) -> {
          service.sendSos(message.getText().toString());
          renderKey = "";
        }).setNegativeButton("Cancel", null).show();
  }

  void emergencyBanner(RelayService service) {
    JSONArray alerts = store.emergencies();
    ArrayList<JSONObject> active = new ArrayList<>();
    for (int i = 0; i < alerts.length(); i++) {
      JSONObject alert = alerts.optJSONObject(i);
      if (alert != null && alert.optBoolean("active", true) && !alert.optBoolean("resolved")
          && !alert.optBoolean("dismissed")) active.add(alert);
    }
    if (!active.isEmpty()) {
      active.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
      JSONObject latest = active.get(0);
      LinearLayout card = card(content, Color.WHITE);
      TextView alarm = mono("EMERGENCY · " + latest.optString("name", "Relay device").toUpperCase(Locale.ROOT), 14, DANGER, true);
      alarm.setLetterSpacing(.08f);
      card.addView(alarm);
      gap(card, 6);
      if (!latest.optString("message").isEmpty())
        card.addView(text(latest.optString("message"), 14, INK, false));
      card.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
          .format(new Date(latest.optLong("at", System.currentTimeMillis())))
          + (active.size() > 1 ? " · " + active.size() + " active alerts" : ""), 12, MUTED, false));
      addButton(card, "Review emergency alerts", RED, INK, () -> emergencyDetails(active));
    }
    if (service != null && !service.sosStatus.isEmpty()) {
      sosStatusView = text(service.sosStatus, 13, MUTED, false);
      content.addView(sosStatusView);
      gap(content, 10);
    }
  }

  void emergencyDetails(ArrayList<JSONObject> alerts) {
    LinearLayout body = column();
    AlertDialog dialog = new RelayDialog.Builder(this).setTitle("Emergency alerts")
        .setView(body).setNegativeButton("Close", null).create();
    for (JSONObject alert : alerts) {
      body.addView(text(alert.optString("name", "Relay device"), 17, RED, true));
      body.addView(text(alert.optString("message", "Emergency assistance requested"), 15, INK, false));
      body.addView(text(DateFormat.getDateTimeInstance().format(new Date(alert.optLong("at"))),
          12, MUTED, false));
      addButton(body, "Dismiss on this phone", BG, INK, () -> {
        RelayService service = RelayService.instance;
        if (service != null) service.dismissEmergency(alert.optString("id"));
        else store.dismissEmergency(alert.optString("id"));
        renderKey = "";
        dialog.dismiss();
      });
      gap(body, 14);
    }
    body.addView(text("Dismiss hides an alert on this phone. The desktop operator resolves it "
        + "for the network. Delivery requires a connection to the hub.", 13, MUTED, false));
    dialog.show();
  }

  void dispatchScreen(RelayService service, String state) {
    String mode = service.dispatchMode;
    heading("emergency".equals(mode) ? "Emergency broadcast"
        : "broadcast".equals(mode) ? "Desktop announcement" : "Desktop call", "Encrypted connection to your enrolled hub");
    LinearLayout box = card(content, INK);
    box.addView(text(service.dispatchName.isEmpty() ? "Relay desktop" : service.dispatchName, 23, INK, true));
    if (!service.dispatchMessage.isEmpty()) {
      gap(box, 8);
      box.addView(text(service.dispatchMessage, 15, MUTED, false));
    }
    gap(box, 12);
    if (state.equals("dispatchIncoming")) {
      box.addView(text("The desktop wants to talk. Answer to listen, then hold TALK to reply.", 15, MUTED, false));
      addButton(box, "Answer desktop", LIME, INK, service::answerDispatch);
      addButton(box, "Decline", RED, INK, service::declineDispatch);
    } else if (state.equals("dispatchActive")) {
      dispatchClockView = mono("00:00", 30, ACCENT, true);
      box.addView(dispatchClockView);
      gap(box, 14);
      dispatchHintView = text("Connected to the desktop", 15, MUTED, false);
      box.addView(dispatchHintView);
      if ("call".equals(mode)) {
        gap(box, 14);
        dispatchTalkButton = button("Hold TALK to reply", LIME, INK, () -> {});
        dispatchTalkButton.setMinHeight(dp(94));
        dispatchTalkButton.setContentDescription("Hold to talk to the desktop, release to listen");
        dispatchTalkButton.setOnTouchListener((v, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (audioPermission()) service.dispatchTalk(true);
            else requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
            return true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            service.dispatchTalk(false); v.performClick(); return true;
          }
          return true;
        });
        dispatchTalkButton.setOnFocusChangeListener((v, focused) -> { if (!focused) service.dispatchTalk(false); });
        dispatchTalkButton.setOnKeyListener((v, keyCode, event) -> {
          if (keyCode != KeyEvent.KEYCODE_SPACE && keyCode != KeyEvent.KEYCODE_ENTER
              && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) return false;
          if (event.getAction() == KeyEvent.ACTION_UP) service.dispatchTalk(false);
          else if (event.getRepeatCount() == 0 && audioPermission()) service.dispatchTalk(true);
          return true;
        });
        box.addView(dispatchTalkButton);
      } else {
        gap(box, 12);
        box.addView(text("Receive only · microphone off", 15, LIME, true));
      }
      addButton(box, service.speaker ? "Speaker on" : service.headsetRoute ? "Headset" : "Earpiece",
          BG, INK, () -> { service.speaker(!service.speaker); renderKey = ""; });
      addButton(box, "call".equals(mode) ? "End desktop call" : "Stop listening", RED, INK, service::endDispatch);
      dispatchStatusView = text(service.dispatchError, 13, RED, false);
      box.addView(dispatchStatusView);
    } else {
      box.addView(text("Connecting to desktop…", 15, MUTED, false));
      addButton(box, "Cancel", RED, INK, service::endDispatch);
    }
  }

  /** Walkie-talkie style display: status light, speaker grille and an LCD with live details. */
  void radioScreen(LinearLayout into, RelayService s) {
    boolean online = s != null;
    String ip = Lan.address();
    boolean hasIp = !ip.startsWith("No ");
    android.net.NetworkCapabilities caps = activeNetwork();
    String net =
        caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            ? "WI-FI"
            : caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                ? "ETHERNET"
                : hasIp ? "LAN" : "NO NETWORK";
    int peers = store.contacts().length();

    LinearLayout lcd = column();
    lcd.setPadding(dp(16), dp(14), dp(16), dp(16));
    lcd.setBackground(new CosmicDrawable.CrtGlass(LCD, LCD_INK, dp(18), CUT));
    lcdOverlay =
        new CosmicDrawable.CrtOverlay(
            LCD_INK, getResources().getDisplayMetrics().density, true, dp(18), CUT);
    lcd.setForeground(lcdOverlay);
    lcdView = lcd;

    LinearLayout strip = new LinearLayout(this);
    strip.setGravity(Gravity.CENTER_VERTICAL);
    View led = new View(this);
    led.setBackground(shape(online ? 0xff3ddc84 : 0xff35503f, 1));
    led.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    strip.addView(led, new LinearLayout.LayoutParams(dp(4), dp(14)));
    TextView model = lcdText("RELAY  LAN RADIO", 11, LCD_LABEL, true);
    model.setLetterSpacing(.12f);
    model.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams modelLayout = new LinearLayout.LayoutParams(0, -2, 1);
    modelLayout.leftMargin = dp(10);
    strip.addView(model, modelLayout);
    for (int i = 0; i < 6; i++) {
      View slot = new View(this);
      slot.setBackground(shape(LCD_GHOST, 2));
      LinearLayout.LayoutParams slotLayout = new LinearLayout.LayoutParams(dp(4), dp(14));
      slotLayout.leftMargin = dp(4);
      strip.addView(slot, slotLayout);
    }
    lcd.addView(strip);
    gap(lcd, 14);

    LinearLayout indicators = new LinearLayout(this);
    indicators.setGravity(Gravity.CENTER_VERTICAL);
    lcdSignal = lcdText("", 13, LCD_INK, true);
    indicators.addView(lcdSignal);
    TextView mode = lcdText(online ? "STBY" : "OFF", 11, LCD, true);
    mode.setBackground(shape(LCD_INK, 3));
    mode.setPadding(dp(5), dp(2), dp(5), dp(2));
    mode.setContentDescription(online ? "Standby" : "Off");
    LinearLayout.LayoutParams modeLayout = new LinearLayout.LayoutParams(-2, -2);
    modeLayout.leftMargin = dp(8);
    indicators.addView(mode, modeLayout);
    indicators.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
    lcdBattery = lcdText("", 13, LCD_INK, true);
    indicators.addView(lcdBattery);
    lcdClock = lcdText("", 13, LCD_INK, true);
    LinearLayout.LayoutParams clockLayout = new LinearLayout.LayoutParams(-2, -2);
    clockLayout.leftMargin = dp(10);
    indicators.addView(lcdClock, clockLayout);
    lcd.addView(indicators);

    View rule = new View(this);
    rule.setBackgroundColor(LCD_GHOST);
    LinearLayout.LayoutParams ruleLayout = new LinearLayout.LayoutParams(-1, dp(1));
    ruleLayout.topMargin = dp(8);
    ruleLayout.bottomMargin = dp(10);
    lcd.addView(rule, ruleLayout);

    TextView big = lcdText(online ? "ONLINE" : "OFFLINE", 28, LCD_INK, true);
    big.setLetterSpacing(.06f);
    lcd.addView(big);
    gap(lcd, 4);
    lcd.addView(lcdText(store.name().toUpperCase(Locale.ROOT), 14, LCD_LABEL, true));
    gap(lcd, 10);
    lcdRow(lcd, "IP", hasIp ? ip : "---.---.---.---", "PORT", online ? String.valueOf(s.port) : "-----");
    lcdRow(lcd, "NET", net, "DESK", deskStatus(s));
    lcdRow(lcd, "PEERS", peers + " PAIRED", null, null);
    into.addView(lcd, new LinearLayout.LayoutParams(-1, -2));
    lcdSampledAt = 0;
    updateLcd();
    if (SystemClock.uptimeMillis() < glitchUntil) startGlitch();
  }

  /** Glitches the radio screen for a moment; it carries over if the screen re-renders meanwhile. */
  void glitch(long millis) {
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
    glitchUntil = SystemClock.uptimeMillis() + millis;
    startGlitch();
  }

  void startGlitch() {
    if (lcdOverlay != null) lcdOverlay.glitch(glitchUntil);
    handler.removeCallbacks(glitchTick);
    handler.post(glitchTick);
  }

  TextView lcdText(String value, int size, int color, boolean bold) {
    TextView t = mono(value, size, color, bold);
    if (color != LCD) t.setShadowLayer(dp(5), 0, 0, (color & 0x00ffffff) | 0x80000000);
    return t;
  }

  TextView mono(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    t.setSingleLine(true);
    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    return t;
  }

  /** One LCD line holding two label/value pairs; the left column is wider for IP addresses. */
  void lcdRow(LinearLayout lcd, String label, String value, String label2, String value2) {
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(3), 0, dp(3));
    row.addView(lcdCell(label, value), new LinearLayout.LayoutParams(0, -2, 1.15f));
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, .85f);
    right.leftMargin = dp(10);
    row.addView(label2 == null ? new Space(this) : lcdCell(label2, value2), right);
    lcd.addView(row);
  }

  LinearLayout lcdCell(String label, String value) {
    LinearLayout cell = new LinearLayout(this);
    cell.setGravity(Gravity.CENTER_VERTICAL);
    cell.addView(lcdText(label, 12, LCD_LABEL, false), new LinearLayout.LayoutParams(dp(44), -2));
    cell.addView(lcdText(value, 13, LCD_INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    return cell;
  }

  String deskStatus(RelayService s) {
    if (!store.hub().optBoolean("enabled")) return "NOT SET UP";
    if (s == null) return "OFF";
    String h = s.hubStatus;
    if (h.startsWith("Connected")) return "LINKED";
    if (h.startsWith("Connecting")) return "SEARCHING";
    if (h.startsWith("Waiting")) return "PENDING";
    if (h.startsWith("Desktop:")) return "ERROR";
    return "STANDBY";
  }

  android.net.NetworkCapabilities activeNetwork() {
    try {
      android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
      return cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
    } catch (Exception e) {
      return null;
    }
  }

  int signalBars() {
    android.net.NetworkCapabilities caps = activeNetwork();
    if (caps == null) return 0;
    if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
      return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? 4 : 0;
    int rssi = Build.VERSION.SDK_INT >= 29 ? caps.getSignalStrength() : Integer.MIN_VALUE;
    if (rssi == Integer.MIN_VALUE) rssi = wifiRssi();
    return rssi >= -55 ? 4 : rssi >= -66 ? 3 : rssi >= -77 ? 2 : rssi >= -88 ? 1 : 0;
  }

  @SuppressWarnings("deprecation")
  int wifiRssi() {
    try {
      android.net.wifi.WifiManager wm =
          getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
      return wm == null ? Integer.MIN_VALUE : wm.getConnectionInfo().getRssi();
    } catch (Exception e) {
      return Integer.MIN_VALUE;
    }
  }

  /** Ticks the LCD clock every poll; samples signal and battery every 10 seconds. */
  void updateLcd() {
    if (lcdClock == null) return;
    Calendar now = Calendar.getInstance();
    boolean colon = now.get(Calendar.SECOND) % 2 == 0;
    int hour = now.get(Calendar.HOUR_OF_DAY);
    boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
    String clock =
        String.format(
            Locale.US,
            "%02d%s%02d%s",
            h24 ? hour : hour % 12 == 0 ? 12 : hour % 12,
            colon ? ":" : " ",
            now.get(Calendar.MINUTE),
            h24 ? "" : hour < 12 ? "AM" : "PM");
    updateText(lcdClock, clock);
    long t = SystemClock.elapsedRealtime();
    if (lcdSampledAt != 0 && t - lcdSampledAt < 10000) return;
    lcdSampledAt = t;
    int bars = signalBars();
    android.text.SpannableString signal =
        new android.text.SpannableString("▂▄▆█");
    signal.setSpan(new android.text.style.ForegroundColorSpan(LCD_GHOST), bars, 4, 0);
    lcdSignal.setText(signal);
    lcdSignal.setContentDescription("Signal " + bars + " of 4");
    BatteryManager bm = getSystemService(BatteryManager.class);
    int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    if (level < 0 || level > 100) {
      updateText(lcdBattery, "");
      return;
    }
    int cells = Math.max(level > 0 ? 1 : 0, Math.round(level / 25f));
    android.text.SpannableString battery =
        new android.text.SpannableString(
            "████ " + level + "%" + (bm.isCharging() ? "+" : ""));
    battery.setSpan(new android.text.style.ForegroundColorSpan(LCD_GHOST), cells, 4, 0);
    lcdBattery.setText(battery);
    lcdBattery.setContentDescription(
        "Battery " + level + " percent" + (bm.isCharging() ? ", charging" : ""));
  }

  void heading(String title, String sub) {
    sectionLabel(title.toUpperCase(Locale.ROOT), null);
    if (sub != null && !sub.isEmpty()) {
      content.addView(text(sub, 13, MUTED, false));
      gap(content, 14);
    }
  }

  /** Small monospace title inside a card. */
  void cardTitle(LinearLayout into, String title) {
    TextView t = mono(title.toUpperCase(Locale.ROOT), 13, TEXT, true);
    t.setLetterSpacing(.12f);
    into.addView(t);
  }

  void devices(RelayService s) {
    LinearLayout hero = column();
    content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    radioScreen(hero, s);
    gap(hero, 10);
    LinearLayout controls = new LinearLayout(this);
    Button primary =
        cutButton(
            s == null ? "BECOME AVAILABLE" : "PAIR",
            s == null ? "power" : "link",
            () -> {
              if (s == null) {
                glitch(350);
                startReceiver();
              } else pairing(null);
            });
    primary.setTextSize(s == null ? 15 : 14);
    primary.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, s == null ? "power" : "link", dark ? 0xff1a8150 : LCD_INK, 20), null, null, null);
    primary.setCompoundDrawablePadding(dp(10));
    primary.setContentDescription(s == null ? "Become available" : "Pair a device");
    controls.addView(primary, new LinearLayout.LayoutParams(0, dp(52), 1));
    if (s != null) {
      Button offline =
          cutButton(
              "GO OFFLINE",
              "power",
              () -> {
                glitch(350);
                store.available(false);
                stopService(new Intent(this, RelayService.class));
                renderKey = "";
              });
      offline.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(RIPPLE),
              new CosmicDrawable.Chamfer(SOFT, dp(8), CUT),
              new CosmicDrawable.Chamfer(Color.WHITE, dp(8), CUT)));
      offline.setTextColor(TEXT);
      offline.setTextSize(14);
      offline.setCompoundDrawablesRelativeWithIntrinsicBounds(
          RelayStyle.icon(this, "power", TEXT, 18), null, null, null);
      offline.setContentDescription("Go offline");
      LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, dp(52), 1);
      ol.leftMargin = dp(10);
      controls.addView(offline, ol);
    }
    hero.addView(controls, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 18);
    if (s != null && !s.error.isEmpty()) {
      content.addView(errorPanel(s.error), new LinearLayout.LayoutParams(-1, -2));
      gap(content, 14);
    }
    JSONArray contacts = store.contacts();
    sectionLabel("PAIRED CHANNELS", String.format(Locale.US, "%02d", contacts.length()));
    if (contacts.length() == 0) {
      LinearLayout empty = column();
      empty.setPadding(dp(18), dp(16), dp(18), dp(18));
      empty.setBackground(RelayStyle.surface(this, Color.WHITE, 18));
      TextView none = mono("NO CHANNELS YET", 13, TEXT, true);
      none.setLetterSpacing(.1f);
      empty.addView(none);
      gap(empty, 6);
      empty.addView(
          text(
              "Open Relay on another device and scan its QR code. Both devices approve the"
                  + " connection.",
              14,
              MUTED,
              false));
      content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
      gap(content, 10);
    }
    for (int i = 0; i < contacts.length(); i++) {
      JSONObject c = contacts.optJSONObject(i);
      boolean live = s != null && inRange(s, c.optString("fingerprint"));
      String host = c.optString("host").isEmpty() ? "VIA DESKTOP" : c.optString("host");
      channelRow(
          c.optBoolean("auto") ? "AUTO" : "CH",
          String.format(Locale.US, "%02d", i + 1),
          c.optString("name"),
          host,
          live,
          (live ? "In range, " : "") + (c.optBoolean("auto") ? "automatic calls, " : "") + host,
          "CALL",
          "phone",
          () -> {
            if (s == null) {
              toast("Become available first");
              return;
            }
            s.connectPeer(c, false);
          },
          () -> contactOptions(c, s));
    }
    if (s != null && !s.nearby.isEmpty()) {
      List<JSONObject> found = new ArrayList<>();
      for (JSONObject n : new ArrayList<>(s.nearby.values()))
        if (store.contact(n.optString("fingerprint")) == null) found.add(n);
      if (!found.isEmpty()) {
        gap(content, 8);
        sectionLabel("NEARBY", String.format(Locale.US, "%02d", found.size()));
        for (JSONObject n : found)
          channelRow(
              "NEW", "+", n.optString("name"), n.optString("host"), true,
              "Nearby, " + n.optString("host"), "PAIR", "link", () -> pairing(n), null);
      }
    }
    if (s != null && !s.hubPeers.isEmpty()) {
      List<JSONObject> peers = new ArrayList<>();
      for (JSONObject peer : new ArrayList<>(s.hubPeers.values()))
        if (!peer.optString("fingerprint").equals(s.identity == null ? "" : s.identity.fingerprint))
          peers.add(peer);
      if (!peers.isEmpty()) {
        gap(content, 8);
        sectionLabel("DESKTOP DIRECTORY", String.format(Locale.US, "%02d", peers.size()));
      }
      for (int i = 0; i < peers.size(); i++) {
        JSONObject peer = peers.get(i);
        boolean online = peer.optBoolean("online");
        boolean canPair = store.contact(peer.optString("fingerprint")) == null && online;
        channelRow(
            "HUB",
            String.format(Locale.US, "%02d", i + 1),
            peer.optString("name", "Relay device"),
            online ? "ONLINE · VIA HUB" : "OFFLINE",
            online,
            online ? "Online through the desktop hub" : "Offline, last known status",
            canPair ? "PAIR" : null,
            "link",
            () ->
                new RelayDialog.Builder(this).setTitle("Pair this Relay device?")
                    .setMessage(peer.optString("name") + "\n\nCertificate:\n"
                        + peer.optString("fingerprint")
                        + "\n\nThe other device must approve. Desktop enrollment does not pair devices.")
                    .setPositiveButton("Request pairing", (d, w) -> s.connectPeer(peer, true))
                    .setNegativeButton("Cancel", null).show(),
            null);
      }
    }
  }

  boolean inRange(RelayService s, String fp) {
    for (JSONObject n : new ArrayList<>(s.nearby.values()))
      if (fp.equals(n.optString("fingerprint"))) return true;
    JSONObject h = s.hubPeers.get(fp);
    return h != null && h.optBoolean("online");
  }

  /** Monospace section label with an accent tick and a right-aligned count. */
  void sectionLabel(String title, String count) {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    l.setPadding(dp(2), 0, dp(4), 0);
    View tick = new View(this);
    tick.setBackground(shape(ACCENT, 2));
    l.addView(tick, new LinearLayout.LayoutParams(dp(4), dp(14)));
    TextView t = mono(title, 12, TEXT, true);
    t.setLetterSpacing(.14f);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
    tl.leftMargin = dp(8);
    l.addView(t, tl);
    if (count != null) {
      TextView c = mono(count, 12, MUTED, true);
      c.setLetterSpacing(.1f);
      l.addView(c);
    }
    content.addView(l, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 10);
  }

  /** Small CRT badge used as a channel number. */
  View channelTile(String top, String big) {
    LinearLayout tile = column();
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(new CosmicDrawable.CrtGlass(LCD, LCD_INK, dp(9), CUT));
    tile.setForeground(
        new CosmicDrawable.CrtOverlay(
            LCD_INK, getResources().getDisplayMetrics().density, false, dp(9), CUT));
    TextView a = lcdText(top, 8, LCD_LABEL, true);
    a.setLetterSpacing(.15f);
    a.setGravity(Gravity.CENTER);
    tile.addView(a, new LinearLayout.LayoutParams(-1, -2));
    gap(tile, 2);
    TextView b = lcdText(big, 17, LCD_INK, true);
    b.setGravity(Gravity.CENTER);
    tile.addView(b, new LinearLayout.LayoutParams(-1, -2));
    tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    return tile;
  }

  /** One compact device row: channel badge, name, presence dot + detail, slanted action, options. */
  void channelRow(
      String top,
      String big,
      String name,
      String detail,
      boolean live,
      String spokenDetail,
      String action,
      String icon,
      Runnable run,
      Runnable more) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(12), dp(12), more == null ? dp(12) : dp(4), dp(12));
    row.setBackground(RelayStyle.surface(this, Color.WHITE, 18));
    row.addView(channelTile(top, big), new LinearLayout.LayoutParams(dp(50), dp(50)));
    LinearLayout label = column();
    TextView title = text(name, 17, TEXT, true);
    title.setLineSpacing(0, 1);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.addView(title);
    LinearLayout meta = new LinearLayout(this);
    meta.setGravity(Gravity.CENTER_VERTICAL);
    View presence = new View(this);
    presence.setBackground(shape(live ? 0xff2fbf71 : 0xffc4c4c4, 1));
    meta.addView(presence, new LinearLayout.LayoutParams(dp(3), dp(11)));
    TextView d = mono(detail, 11, MUTED, false);
    d.setContentDescription(spokenDetail);
    LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1);
    dl.leftMargin = dp(6);
    meta.addView(d, dl);
    LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
    ml.topMargin = dp(4);
    label.addView(meta, ml);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(12), 0, dp(8), 0);
    row.addView(label, lp);
    if (action != null) {
      Button go = cutButton(action, icon, run);
      go.setContentDescription(action.charAt(0) + action.substring(1).toLowerCase(Locale.ROOT) + " " + name);
      row.addView(go, new LinearLayout.LayoutParams(-2, dp(38)));
    }
    if (more != null) {
      ImageView m = new ImageView(this);
      m.setImageDrawable(RelayStyle.icon(this, "menu", MUTED, 20));
      m.setScaleType(ImageView.ScaleType.CENTER);
      m.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(RIPPLE), null, null));
      m.setContentDescription("Options for " + name);
      m.setOnClickListener(v -> more.run());
      row.addView(m, new LinearLayout.LayoutParams(dp(44), dp(48)));
    }
    content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 10);
  }

  /** Dark monospace action button with cut top-left and bottom-right corners. */
  Button cutButton(String label, String icon, Runnable action) {
    Button b = button(label, LIME, ON_LIME, action);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((ON_LIME & 0x00ffffff) | 0x33000000),
            new CosmicDrawable.Chamfer(LIME, dp(8), CUT),
            new CosmicDrawable.Chamfer(Color.WHITE, dp(8), CUT)));
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setTextSize(13);
    b.setLetterSpacing(.1f);
    b.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    b.setCompoundDrawablesRelativeWithIntrinsicBounds(
        icon == null ? null : RelayStyle.icon(this, icon, ON_LIME, 15), null, null, null);
    b.setCompoundDrawablePadding(dp(6));
    b.setPadding(dp(16), 0, dp(16), 0);
    return b;
  }

  EditText field(String hint, String value) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(TEXT);
    e.setHighlightColor(0x551a8150);
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
    if (request == 32 && result == RESULT_OK && data != null) {
      try { confirmDesktop(HubQr.parse(data.getStringExtra("pairing"))); }
      catch (Exception e) { toast(e.getMessage()); }
      return;
    }
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
    if (s != null && s.state.startsWith("dispatch")) {
      String shortcut = store.shortcut(key == KeyEvent.KEYCODE_VOLUME_UP);
      if (shortcut.equals("System volume")) return super.dispatchKeyEvent(event);
      boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
      if (shortcut.equals("Hold to talk")) {
        if (!pressed || event.getRepeatCount() == 0) {
          if (!pressed || audioPermission()) s.dispatchTalk(pressed);
        }
      } else if (pressed && event.getRepeatCount() == 0) {
        if (shortcut.equals("Answer call") && s.state.equals("dispatchIncoming")) s.answerDispatch();
        else if (shortcut.equals("End/decline call")) s.endDispatch();
      }
      return true;
    }
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
    box.addView(text(s.peerName, 23, INK, true));
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
              MUTED,
              false));
      addButton(box, "Agree and enable", LIME, INK, s::approveAutomatic);
      addButton(box, "Decline", BG, INK, () -> s.end("Agreement declined", true));
    } else if (state.startsWith("auto") || state.equals("connectingAuto")) {
      box.addView(text("Waiting for the other device to agree…", 16, MUTED, false));
      addButton(
          box, "Cancel request", BG, INK, () -> s.end("Agreement cancelled", true));
    } else if (state.equals("pairIncoming")) {
      box.addView(text("Wants to pair with your device", 16, MUTED, false));
      gap(box, 10);
      box.addView(text("Requesting device key\n" + s.peerFp.substring(0, 12), 18, LIME, true));
      box.addView(
          text(
              "Approve only if you recognize the device and requested this connection.",
              14,
              MUTED,
              false));
      addButton(box, "Approve pairing", LIME, INK, s::approvePair);
      addButton(box, "Decline", BG, INK, () -> s.end("Declined", true));
    } else if (state.startsWith("pair") || state.equals("connectingPair")) {
      box.addView(text("Waiting for pairing approval…", 16, MUTED, false));
      addButton(box, "Cancel", BG, INK, () -> s.end("Pairing cancelled", true));
    } else if (state.equals("incoming")) {
      box.addView(text("Incoming local call", 17, LIME, true));
      gap(box, 16);
      box.addView(text("Answer to connect, then hold TALK to speak.", 15, MUTED, false));
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
      addButton(box, "Decline", BG, INK, () -> s.end("Declined", true));
    } else if (state.equals("active") || state.equals("acceptedWaiting")) {
      callClock = mono("00:00", 30, ACCENT, true);
      box.addView(callClock);
      gap(box, 14);
      callHint = text("Hold to talk. Release to listen.", 15, MUTED, false);
      box.addView(callHint);
      gap(box, 14);
      talkButton = button("Hold to\ntalk", LIME, INK, () -> {});
      talkButton.setTextSize(18);
      talkButton.setPadding(dp(24), dp(24), dp(24), dp(24));
      talkButton.setContentDescription("Hold to talk. Release to listen.");
      talkButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
          null, RelayStyle.icon(this, "mic", ON_LIME, 24), null, null);
      talkButton.setCompoundDrawablePadding(dp(6));
      talkOrb = new CosmicDrawable.Orb(dp(22));
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
      callRecording = text("Encrypted · " + s.connectionRoute, 12, MUTED, false);
      box.addView(callRecording);
      gap(box, 14);
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
      addButton(box, "End call", RED, INK, () -> s.end("Completed", true));
      callError = text(s.error, 13, RED, false);
      gap(box, 10);
      box.addView(callError);
    } else {
      box.addView(text("Calling… waiting for an answer", 16, MUTED, false));
      addButton(box, "Cancel call", BG, INK, () -> s.end("Cancelled", true));
    }
  }

  void settings(RelayService s) {
    sectionLabel("THIS DEVICE", null);
    LinearLayout identity = card(content, Color.WHITE);
    deviceHeader(identity, "ID", store.name(), Lan.address().toUpperCase(Locale.ROOT), null);
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
      addButton(identity, "Show my QR code", INK, INK, this::showQr);
    addButton(identity, "Connection details", BG, INK, () -> identityDetails(s));

    sectionLabel("APPEARANCE", null);
    LinearLayout appearance = card(content, Color.WHITE);
    appearance.addView(
        text("Light, dark, or follow the phone's setting.", 14, MUTED, false));
    gap(appearance, 10);
    LinearLayout modes = new LinearLayout(this);
    String current = getSharedPreferences("relay", 0).getString("theme", "system");
    String[][] options = {{"light", "Light"}, {"dark", "Dark"}, {"system", "Auto"}};
    for (int i = 0; i < options.length; i++) {
      String mode = options[i][0];
      boolean on = mode.equals(current);
      Button pick =
          button(
              options[i][1],
              on ? LIME : BG,
              on ? ON_LIME : INK,
              () -> switchTheme(mode));
      pick.setCompoundDrawables(null, null, null, null);
      pick.setPadding(dp(4), 0, dp(4), 0);
      pick.setSelected(on);
      pick.setContentDescription(options[i][1] + " theme" + (on ? ", selected" : ""));
      LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(46), 1);
      if (i > 0) pl.leftMargin = dp(8);
      modes.addView(pick, pl);
    }
    appearance.addView(modes);

    sectionLabel("AVAILABILITY", null);
    LinearLayout availability = card(content, Color.WHITE);
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

    sectionLabel("DESKTOP MANAGER", null);
    LinearLayout desktop = card(content, Color.WHITE);
    JSONObject hub = store.hub();
    if (hub.optBoolean("enabled") && s != null && s.hubStatus.startsWith("Desktop:"))
      desktop.addView(errorPanel(s.hubStatus), new LinearLayout.LayoutParams(-1, -2));
    else
      desktop.addView(text(hub.optBoolean("enabled")
          ? (s == null ? "Go available to connect to " + hub.optString("host") : s.hubStatus)
          : "Manage Relay devices and connect across approved local subnets.", 14, MUTED, false));
    addButton(desktop, "Desktop connection", BG, INK, this::desktopSettings);
    addButton(desktop, "Approved routed subnets", BG, INK, this::routedNetworks);

    sectionLabel("CALL FEEDBACK", null);
    LinearLayout feedback = card(content, Color.WHITE);
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

    sectionLabel("BUTTON SHORTCUTS", null);
    LinearLayout shortcuts = card(content, Color.WHITE);
    shortcuts.addView(text("Use volume buttons to answer or hold to talk.", 14, MUTED, false));
    addButton(
        shortcuts, "Volume up · " + store.shortcut(true), BG, INK, () -> shortcutOptions(true));
    addButton(
        shortcuts, "Volume down · " + store.shortcut(false), BG, INK, () -> shortcutOptions(false));
    addButton(shortcuts, "Background shortcuts", BG, INK, this::backgroundShortcuts);

    sectionLabel("PRIVATE BY DESIGN", null);
    LinearLayout about = card(content, Color.WHITE);
    about.addView(
        text("Calls stay on your approved local network. Recordings stay on this device.", 14, MUTED, false));
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

  void desktopSettings() {
    RelayService s = RelayService.instance;
    JSONObject hub = store.hub();
    LinearLayout body = column();
    if (s != null && s.hubStatus.startsWith("Desktop:"))
      body.addView(errorPanel(s.hubStatus), new LinearLayout.LayoutParams(-1, -2));
    else
      body.addView(text(s == null ? "Become available to connect to a desktop." : s.hubStatus,
          15, MUTED, false));
    gap(body, 8);
    body.addView(text("Every phone must be able to reach the PC. The PC must stay awake. "
        + "Pairing between phones still requires approval on both devices.", 14, MUTED, false));
    if (!hub.optString("host").isEmpty()) {
      TextView details = text("Desktop: " + hub.optString("host") + ":" + hub.optInt("port", 52744)
          + "\nCertificate: " + hub.optString("fingerprint"), 13, MUTED, false);
      details.setTextIsSelectable(true);
      body.addView(details);
    }
    AlertDialog dialog = new RelayDialog.Builder(this).setTitle("Desktop manager")
        .setView(body).setNegativeButton("Done", null).create();
    addButton(body, "Scan desktop QR code", LIME, INK, () -> {
      dialog.dismiss();
      startActivityForResult(new Intent(this, ScannerActivity.class).putExtra("desktop", true), 32);
    });
    addButton(body, "Paste enrollment link", BG, INK, () -> {
      dialog.dismiss();
      EditText link = field("relay://hub?v=1&host=…", "");
      AlertDialog entry = new RelayDialog.Builder(this).setTitle("Desktop enrollment link")
          .setView(link).setPositiveButton("Review connection", null)
          .setNegativeButton("Cancel", null).create();
      entry.setOnShowListener(ignored -> entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        try {
          JSONObject config = HubQr.parse(link.getText().toString());
          entry.dismiss();
          confirmDesktop(config);
        } catch (Exception e) { link.setError(e.getMessage()); }
      }));
      entry.show();
    });
    addButton(body, "Enter desktop details", BG, INK, () -> { dialog.dismiss(); manualDesktop(); });
    if (!hub.optString("host").isEmpty()) {
      addButton(body, hub.optBoolean("enabled") ? "Disconnect desktop" : "Reconnect desktop",
          BG, INK, () -> {
        try { hub.put("enabled", !hub.optBoolean("enabled")); store.hub(hub); }
        catch (Exception e) { toast(e.getMessage()); return; }
        if (s != null) s.reconnectHub();
        renderKey = "";
        dialog.dismiss();
      });
      addButton(body, "Forget desktop", BG, INK, () -> {
        new RelayDialog.Builder(this).setTitle("Forget desktop manager?")
            .setMessage("Remove this desktop's enrollment settings from this phone. Your paired "
                + "devices, call history and recordings stay available. The desktop can revoke its own enrollment record.")
            .setPositiveButton("Forget", (d, w) -> {
              store.hub(new JSONObject());
              if (s != null) s.reconnectHub();
              renderKey = "";
              dialog.dismiss();
            }).setNegativeButton("Cancel", null).show();
      });
    }
    dialog.show();
  }

  void manualDesktop() {
    JSONObject existing = store.hub();
    LinearLayout body = column();
    body.addView(text("Generate an enrollment code in Relay Desktop Manager. Enter its full "
        + "certificate and fresh single-use token.", 14, MUTED, false));
    EditText host = field("Desktop IPv4 address", existing.optString("host"));
    EditText port = field("Desktop port", "" + existing.optInt("port", 52744));
    port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    EditText fp = field("Full certificate · 64 hex characters", "");
    EditText token = field("Enrollment token · 64 hex characters", "");
    token.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    body.addView(host); body.addView(port); body.addView(fp); body.addView(token);
    AlertDialog dialog = new RelayDialog.Builder(this).setTitle("Enter desktop details")
        .setView(body).setPositiveButton("Review connection", null)
        .setNegativeButton("Cancel", null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        JSONObject config = HubQr.configuration(host.getText().toString(), port.getText().toString(),
            fp.getText().toString(), token.getText().toString());
        dialog.dismiss(); confirmDesktop(config);
      } catch (Exception e) { toast(e.getMessage()); }
    }));
    dialog.show();
  }

  void confirmDesktop(JSONObject config) {
    new RelayDialog.Builder(this).setTitle("Approve this desktop manager?")
        .setMessage("Address: " + config.optString("host") + ":" + config.optInt("port")
            + "\n\nCertificate:\n" + config.optString("fingerprint")
            + "\n\nCompare this with the desktop screen. The manager can call this phone, send "
            + "announcements and emergency alerts while Available, and see connection metadata. "
            + "Your microphone stays off until you hold TALK in a call. Peer calls remain encrypted between devices. "
            + "An operator must also approve this phone on the desktop.")
        .setPositiveButton("Approve and connect", (d, w) -> {
          store.hub(config);
          RelayService s = RelayService.instance;
          if (s != null) s.reconnectHub();
          else toast("Saved. Become available to send the enrollment request.");
          renderKey = "";
        }).setNegativeButton("Cancel", null).show();
  }

  void routedNetworks() {
    LinearLayout body = column();
    body.addView(text("Allow direct connections to these private IPv4 subnets in addition to "
        + "your own subnet. Enter one CIDR per line, for example 192.168.20.0/24. "
        + "The router must permit communication. Desktop relay access is configured separately.",
        14, MUTED, false));
    EditText networks = field("Approved private IPv4 CIDRs", store.approvedCidrs());
    networks.setSingleLine(false);
    networks.setMinLines(3);
    networks.setMaxLines(7);
    networks.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    body.addView(networks);
    AlertDialog dialog = new RelayDialog.Builder(this).setTitle("Approved routed subnets")
        .setView(body).setPositiveButton("Save policy", null).setNegativeButton("Cancel", null).create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        store.approvedCidrs(networks.getText().toString());
        renderKey = "";
        dialog.dismiss();
        toast("Routed subnet policy saved");
      } catch (Exception e) { networks.setError(e.getMessage()); }
    }));
    dialog.show();
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
    JSONArray history = store.history();
    sectionLabel("CALL LOG", String.format(Locale.US, "%02d", Math.min(history.length(), 100)));
    if (history.length() == 0) {
      LinearLayout empty = card(content, Color.WHITE);
      cardTitle(empty, "No calls yet");
      gap(empty, 8);
      empty.addView(text("Your calls and recordings will appear here.", 14, MUTED, false));
    }
    for (int i = 0; i < Math.min(history.length(), 100); i++) {
      JSONObject call = history.optJSONObject(i);
      LinearLayout entry = card(content, Color.WHITE);
      String direction = call.optString("direction");
      deviceHeader(
          entry,
          direction.startsWith("In") ? "IN" : direction.startsWith("Out") ? "OUT" : "LOG",
          call.optString("name"),
          (direction + " · " + call.optString("result")).toUpperCase(Locale.ROOT),
          () -> historyOptions(call));
      gap(entry, 10);
      entry.addView(
          mono(
              DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                      .format(new Date(call.optLong("time")))
                  + " · "
                  + call.optLong("duration")
                  + " SEC",
              11,
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
