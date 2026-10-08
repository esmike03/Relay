package app.relay.lan;

import java.net.*;
import java.util.*;

final class Lan {
  static List<InterfaceAddress> interfaces() throws SocketException {
    List<InterfaceAddress> out = new ArrayList<>();
    Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
    while (it != null && it.hasMoreElements()) {
      NetworkInterface n = it.nextElement();
      String name = n.getName();
      if (!n.isUp()
          || n.isLoopback()
          || !(name.startsWith("wlan")
              || name.startsWith("wifi")
              || name.startsWith("ap")
              || name.startsWith("swlan")
              || name.startsWith("eth"))) continue;
      for (InterfaceAddress a : n.getInterfaceAddresses())
        if (a.getAddress() instanceof Inet4Address && a.getAddress().isSiteLocalAddress())
          out.add(a);
    }
    return out;
  }

  static boolean allowed(InetAddress remote) {
    if (!(remote instanceof Inet4Address) || !remote.isSiteLocalAddress()) return false;
    try {
      for (InterfaceAddress a : interfaces()) {
        int prefix = a.getNetworkPrefixLength();
        if (prefix < 8 || prefix > 30) continue;
        byte[] x = a.getAddress().getAddress(), y = remote.getAddress();
        int bits = prefix;
        boolean match = true;
        for (int i = 0; i < 4; i++) {
          int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : (255 << (8 - bits)) & 255;
          if ((x[i] & mask) != (y[i] & mask)) match = false;
          bits -= 8;
        }
        if (match && !remote.equals(a.getAddress())) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  static InetAddress parse(String host) throws Exception {
    if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
      throw new Exception("Enter a local IPv4 address, such as 192.168.0.110");
    InetAddress a = InetAddress.getByName(host);
    if (!allowed(a))
      throw new Exception("Device must be on the same local Wi-Fi or Ethernet subnet");
    return a;
  }

  static String address() {
    try {
      List<InterfaceAddress> a = interfaces();
      return a.isEmpty() ? "No local Wi-Fi connection" : a.get(0).getAddress().getHostAddress();
    } catch (Exception e) {
      return "No local Wi-Fi connection";
    }
  }
}
