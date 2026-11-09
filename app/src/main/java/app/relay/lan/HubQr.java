package app.relay.lan;

import android.net.Uri;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.json.JSONObject;

/** Explicit, offline trust bootstrap for a desktop manager, separate from peer pairing. */
final class HubQr {
  static JSONObject parse(String value) throws Exception {
    if (value == null || value.length() > 2048)
      throw new Exception("Enter or scan a Relay desktop enrollment link");
    Uri u = Uri.parse(value.trim());
    if (!"relay".equals(u.getScheme()) || !"hub".equals(u.getAuthority())
        || !"1".equals(u.getQueryParameter("v")))
      throw new Exception("This is not a Relay desktop enrollment code");
    return configuration(u.getQueryParameter("host"), u.getQueryParameter("port"),
        u.getQueryParameter("fp"), u.getQueryParameter("token"));
  }

  static JSONObject configuration(String host, String portText, String fingerprint, String token)
      throws Exception {
    host = host == null ? "" : host.trim();
    if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
      throw new Exception("Use the desktop's private IPv4 address");
    String[] octets = host.split("\\.");
    for (String part : octets)
      if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.charAt(0) == '0'))
        throw new Exception("Invalid IPv4 address");
    InetAddress address = InetAddress.getByName(host);
    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress())
      throw new Exception("The desktop must use a private local-network IPv4 address");
    int port;
    try { port = Integer.parseInt(portText == null ? "52744" : portText.trim()); }
    catch (NumberFormatException e) { throw new Exception("Use a port from 1024 to 65535"); }
    if (port < 1024 || port > 65535) throw new Exception("Use a port from 1024 to 65535");
    String fp = Identity.clean(fingerprint == null ? "" : fingerprint);
    if (!fp.matches("[A-F0-9]{64}"))
      throw new Exception("Use the desktop's full 64-character certificate fingerprint");
    String secret = token == null ? "" : token.trim();
    if (!secret.matches("[a-fA-F0-9]{64}"))
      throw new Exception("Generate a new enrollment code on the desktop (64-character token)");
    return Store.json("host", host, "port", port, "fingerprint", fp,
        "token", secret, "enabled", true);
  }
}
