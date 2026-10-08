package app.relay.lan;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashMap;
import java.util.Map;

/** Opt-in hardware key handling. No screen content is requested or inspected. */
public class ButtonShortcutService extends AccessibilityService {
  static volatile ButtonShortcutService connected;
  static volatile String lastKey = "Press a volume button to test.";
  boolean testing;
  final Handler actions = new Handler(Looper.getMainLooper());
  final Map<Integer, String> consumed = new HashMap<>();

  protected void onServiceConnected() {
    AccessibilityServiceInfo info = getServiceInfo();
    info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
    setServiceInfo(info);
    connected = this;
    android.util.Log.i("RelayShortcuts", "Connected; flags=" + info.flags);
  }

  public void onAccessibilityEvent(AccessibilityEvent event) {}

  public void onInterrupt() {
    reset();
  }

  public void onDestroy() {
    if (connected == this) connected = null;
    reset();
    super.onDestroy();
  }

  void reset() {
    testing = false;
    consumed.clear();
    RelayService s = RelayService.instance;
    if (s != null) {
      s.shortcutHeld = false;
      s.talk(false);
    }
  }

  protected boolean onKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
    RelayService s = RelayService.instance;
    lastKey =
        (key == KeyEvent.KEYCODE_VOLUME_UP ? "Volume up" : "Volume down")
            + (event.getAction() == KeyEvent.ACTION_UP ? " released" : " pressed");
    android.util.Log.d("RelayShortcuts", lastKey + "; state=" + (s == null ? "offline" : s.state));
    if (testing) return true;
    if (event.getAction() == KeyEvent.ACTION_UP) {
      String completed = consumed.remove(key);
      if (completed != null) {
        if (s != null && completed.equals("Hold to talk")) {
          actions.post(
              () -> {
                s.shortcutHeld = false;
                s.talk(false);
              });
        }
        return true;
      }
    }
    if (consumed.containsKey(key)) return true;
    if (event.getAction() != KeyEvent.ACTION_DOWN
        || s == null
        || (!s.state.equals("incoming")
            && !s.state.equals("active")
            && !s.state.equals("acceptedWaiting"))) return false;
    String action = s.store.shortcut(key == KeyEvent.KEYCODE_VOLUME_UP);
    if (action.equals("System volume")) return false;
    consumed.put(key, action);
    // Return the key-filter result before audio, network or activity work. Android
    // times out slow filters and can pass DOWN while a later UP is consumed.
    actions.post(() -> perform(s, action));
    return true;
  }

  void perform(RelayService s, String action) {
    if (action.equals("Answer call") && s.state.equals("incoming")) {
      // Accept independently of vendor restrictions on opening background activities.
      // The visible call screen enables the microphone; reception can start immediately.
      s.activate(true);
      open("callShortcut");
    } else if (action.equals("Hold to talk") && s.state.equals("active")) {
      s.shortcutHeld = true;
      if (s.microphoneEnabled) s.talk(true);
      else open("talkShortcut");
    } else if (action.equals("End/decline call"))
      s.end(s.state.equals("incoming") ? "Declined" : "Completed", true);
  }

  void open(String action) {
    try {
      startActivity(
          new Intent(this, MainActivity.class)
              .setAction(action)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    } catch (Exception e) {
      RelayService s = RelayService.instance;
      if (s != null) {
        s.shortcutHeld = false;
        s.problem("Open Relay’s call notification to use this shortcut");
      }
    }
  }
}
