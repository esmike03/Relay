package app.relay.lan;

import android.content.*;
import java.util.*;
import org.json.*;

final class Store {
  private final SharedPreferences prefs;

  Store(Context c) {
    prefs = c.getSharedPreferences("relay", 0);
  }

  String name() {
    return prefs.getString("name", android.os.Build.MODEL);
  }

  void name(String n) {
    prefs
        .edit()
        .putString(
            "name",
            n.trim().isEmpty()
                ? android.os.Build.MODEL
                : n.trim().substring(0, Math.min(32, n.trim().length())))
        .apply();
  }

  boolean available() {
    return prefs.getBoolean("available", false);
  }

  void available(boolean b) {
    prefs.edit().putBoolean("available", b).apply();
  }

  String automaticSound() {
    return prefs.getString("automaticSound", null);
  }

  void automaticSound(String uri) {
    prefs.edit().putString("automaticSound", uri == null ? "" : uri).apply();
  }

  int automaticSoundSeconds() {
    return prefs.getInt("automaticSoundSeconds", 5);
  }

  void automaticSoundSeconds(int seconds) {
    prefs.edit().putInt("automaticSoundSeconds", seconds).apply();
  }

  boolean turnEndBeep() {
    return prefs.getBoolean("turnEndBeep", true);
  }

  void turnEndBeep(boolean enabled) {
    prefs.edit().putBoolean("turnEndBeep", enabled).apply();
  }

  boolean turnEndVibration() {
    return prefs.getBoolean("turnEndVibration", true);
  }

  void turnEndVibration(boolean enabled) {
    prefs.edit().putBoolean("turnEndVibration", enabled).apply();
  }

  synchronized JSONArray contacts() {
    try {
      return new JSONArray(prefs.getString("contacts", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  synchronized JSONObject contact(String fp) {
    JSONArray a = contacts();
    for (int i = 0; i < a.length(); i++)
      if (a.optJSONObject(i).optString("fingerprint").equals(fp)) return a.optJSONObject(i);
    return null;
  }

  synchronized void saveContact(String fp, String name, String host, int port) {
    JSONArray a = contacts();
    JSONArray b = new JSONArray();
    for (int i = 0; i < a.length(); i++)
      if (!a.optJSONObject(i).optString("fingerprint").equals(fp)) b.put(a.optJSONObject(i));
    JSONObject existing = contact(fp);
    b.put(
        json(
            "fingerprint",
            fp,
            "name",
            name,
            "host",
            host,
            "port",
            port,
            "auto",
            existing != null && existing.optBoolean("auto")));
    prefs.edit().putString("contacts", b.toString()).commit();
  }

  synchronized void address(String fp, String host, int port) {
    JSONObject c = contact(fp);
    if (c != null) saveContact(fp, c.optString("name"), host, port);
  }

  synchronized boolean automatic(String fp) {
    JSONObject c = contact(fp);
    return c != null && c.optBoolean("auto", false);
  }

  synchronized void automatic(String fp, boolean enabled) {
    JSONArray a = contacts();
    for (int i = 0; i < a.length(); i++) {
      JSONObject c = a.optJSONObject(i);
      if (c.optString("fingerprint").equals(fp))
        try {
          c.put("auto", enabled);
        } catch (Exception ignored) {
        }
    }
    prefs.edit().putString("contacts", a.toString()).commit();
  }

  String shortcut(boolean up) {
    return prefs.getString(up ? "keyUp" : "keyDown", up ? "Answer call" : "Hold to talk");
  }

  void shortcut(boolean up, String action) {
    prefs.edit().putString(up ? "keyUp" : "keyDown", action).apply();
  }

  synchronized void forget(String fp) {
    JSONArray a = contacts(), b = new JSONArray();
    for (int i = 0; i < a.length(); i++)
      if (!a.optJSONObject(i).optString("fingerprint").equals(fp)) b.put(a.optJSONObject(i));
    prefs.edit().putString("contacts", b.toString()).commit();
  }

  synchronized JSONArray history() {
    try {
      return new JSONArray(prefs.getString("history", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  synchronized void history(JSONObject row) {
    JSONArray a = history(), b = new JSONArray();
    b.put(row);
    for (int i = 0; i < a.length(); i++)
      if (!a.optJSONObject(i).optString("id").equals(row.optString("id")))
        b.put(a.optJSONObject(i));
    prefs.edit().putString("history", b.toString()).commit();
  }

  synchronized JSONObject pending() {
    try {
      return new JSONObject(prefs.getString("pending", ""));
    } catch (Exception e) {
      return null;
    }
  }

  synchronized void pending(JSONObject row) {
    SharedPreferences.Editor e = prefs.edit();
    if (row == null) e.remove("pending");
    else e.putString("pending", row.toString());
    e.commit();
  }

  synchronized void deleteHistory(String id) {
    JSONArray a = history(), b = new JSONArray();
    for (int i = 0; i < a.length(); i++)
      if (!a.optJSONObject(i).optString("id").equals(id)) b.put(a.optJSONObject(i));
    prefs.edit().putString("history", b.toString()).commit();
  }

  static JSONObject json(Object... args) {
    JSONObject j = new JSONObject();
    try {
      for (int i = 0; i < args.length; i += 2) j.put((String) args[i], args[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return j;
  }
}
