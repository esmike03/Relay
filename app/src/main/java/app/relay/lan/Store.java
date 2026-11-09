package app.relay.lan;

import android.content.*;
import java.util.*;
import org.json.*;

final class Store {
  private final SharedPreferences prefs;

  Store(Context c) {
    prefs = c.getSharedPreferences("relay", 0);
    try {
      Lan.approvedCidrs(approvedCidrs());
    } catch (IllegalArgumentException ignored) {
      Lan.approvedCidrs("");
    }
  }

  String approvedCidrs() {
    return prefs.getString("approvedCidrs", "");
  }

  void approvedCidrs(String value) {
    List<NetworkPolicy.Cidr> ranges = NetworkPolicy.parseCidrs(value);
    StringBuilder normalized = new StringBuilder();
    for (NetworkPolicy.Cidr range : ranges) {
      if (normalized.length() > 0) normalized.append('\n');
      normalized.append(range);
    }
    prefs.edit().putString("approvedCidrs", normalized.toString()).commit();
    Lan.approvedCidrs(normalized.toString());
  }

  synchronized JSONObject hub() {
    try {
      return new JSONObject(prefs.getString("desktopHub", "{}"));
    } catch (JSONException ignored) {
      return new JSONObject();
    }
  }

  synchronized void hub(JSONObject config) {
    if (config.optBoolean("enabled")) {
      Lan.privateAddress(config.optString("host"));
      int port = config.optInt("port", 52744);
      if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid desktop port");
      if (!config.optString("fingerprint").matches("[A-F0-9]{64}"))
        throw new IllegalArgumentException("Use the desktop's full certificate fingerprint");
      if (!config.optString("token").isEmpty()
          && !config.optString("token").matches("[a-fA-F0-9]{64}"))
        throw new IllegalArgumentException("Invalid enrollment token");
    }
    prefs.edit().putString("desktopHub", config.toString()).commit();
  }

  synchronized void hubTokenAccepted(String expectedFingerprint, String expectedToken) {
    JSONObject config = hub();
    if (!expectedFingerprint.equals(config.optString("fingerprint"))
        || !expectedToken.equals(config.optString("token"))) return;
    config.remove("token");
    prefs.edit().putString("desktopHub", config.toString()).commit();
  }

  synchronized JSONArray emergencies() {
    try { return new JSONArray(prefs.getString("emergencies", "[]")); }
    catch (JSONException ignored) { return new JSONArray(); }
  }

  synchronized boolean emergency(JSONObject event, String hubFingerprint) {
    JSONArray previous = emergencies(), updated = new JSONArray();
    String id = event.optString("id");
    boolean fresh = true;
    JSONObject value = json("id", id, "from", event.optString("from"),
        "name", RelayService.safeName(event.optString("name", "Relay emergency")),
        "message", cleanMessage(event.optString("message"), 280),
        "at", event.optLong("at", System.currentTimeMillis()), "hub", hubFingerprint,
        "active", true, "resolved", false, "dismissed", false);
    for (int i = 0; i < previous.length(); i++) {
      JSONObject existing = previous.optJSONObject(i);
      if (existing != null && id.equals(existing.optString("id"))
          && hubFingerprint.equals(existing.optString("hub"))) {
        value = existing; // Duplicate delivery cannot resurrect a dismissed or resolved emergency.
        fresh = false;
        break;
      }
    }
    updated.put(value);
    for (int i = 0; i < previous.length() && updated.length() < 64; i++) {
      JSONObject existing = previous.optJSONObject(i);
      if (existing != null && !(id.equals(existing.optString("id"))
          && hubFingerprint.equals(existing.optString("hub")))) updated.put(existing);
    }
    prefs.edit().putString("emergencies", updated.toString()).commit();
    return fresh;
  }

  synchronized void resolveEmergency(String id, String hubFingerprint, long at) {
    JSONArray events = emergencies();
    boolean found = false, changed = false;
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (event != null && id.equals(event.optString("id"))
          && hubFingerprint.equals(event.optString("hub")))
        try {
          found = true;
          if (!event.optBoolean("resolved") || event.optBoolean("active", true)) {
            event.put("active", false); event.put("resolved", true); event.put("resolvedAt", at);
            changed = true;
          }
        }
        catch (JSONException ignored) { }
    }
    if (!found) {
      JSONArray withTombstone = new JSONArray();
      withTombstone.put(json("id", id, "hub", hubFingerprint, "active", false,
          "resolved", true, "dismissed", false, "resolvedAt", at));
      for (int i = 0; i < events.length() && withTombstone.length() < 64; i++) withTombstone.put(events.opt(i));
      events = withTombstone;
      changed = true;
    }
    if (changed) prefs.edit().putString("emergencies", events.toString()).apply();
  }

  synchronized void dismissEmergency(String id) {
    JSONArray events = emergencies();
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (event != null && id.equals(event.optString("id")))
        try { event.put("dismissed", true); } catch (JSONException ignored) { }
    }
    prefs.edit().putString("emergencies", events.toString()).commit();
  }

  static String cleanMessage(String message, int maximum) {
    String clean = message == null ? "" : message.replaceAll("[\\p{Cntrl}]", " ").trim();
    return clean.substring(0, Math.min(maximum, clean.length()));
  }

  synchronized void reconcileEmergencies(String hubFingerprint, Set<String> activeIds) {
    JSONArray events = emergencies();
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (event != null && hubFingerprint.equals(event.optString("hub"))
          && event.optBoolean("active", true) && !activeIds.contains(event.optString("id"))) {
        try {
          event.put("active", false);
          event.put("resolved", true);
          event.put("resolvedAt", System.currentTimeMillis());
        } catch (JSONException ignored) { }
      }
    }
    prefs.edit().putString("emergencies", events.toString()).commit();
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
    JSONObject updated = existing == null ? json("auto", false) : existing;
    try {
      updated.put("fingerprint", fp);
      updated.put("name", name);
      // A relay address is never a peer address. Keep direct endpoints when pairing via a hub.
      if (host != null && !host.isEmpty()) {
        NetworkPolicy.privateAddress(host);
        updated.put("host", host);
        updated.put("port", port);
        JSONArray endpoints = new JSONArray();
        endpoints.put(json("host", host, "port", port));
        JSONArray previous = updated.optJSONArray("directEndpoints");
        if (previous != null)
          for (int i = 0; i < previous.length() && endpoints.length() < 8; i++) {
            JSONObject endpoint = previous.optJSONObject(i);
            if (endpoint != null && !host.equals(endpoint.optString("host"))) endpoints.put(endpoint);
          }
        updated.put("directEndpoints", endpoints);
      } else if (!updated.has("host")) {
        updated.put("host", "");
        updated.put("port", 52743);
      }
    } catch (JSONException impossible) {
      throw new IllegalArgumentException(impossible);
    }
    b.put(updated);
    prefs.edit().putString("contacts", b.toString()).commit();
  }

  synchronized void relayRoute(String fp, String hubFingerprint) {
    JSONArray contacts = contacts();
    for (int i = 0; i < contacts.length(); i++) {
      JSONObject contact = contacts.optJSONObject(i);
      if (contact != null && fp.equals(contact.optString("fingerprint")))
        try {
          contact.put("relayHub", hubFingerprint);
          contact.put("relayPeer", fp);
        } catch (JSONException ignored) {
        }
    }
    prefs.edit().putString("contacts", contacts.toString()).commit();
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
