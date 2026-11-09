package app.relay.lan;

import java.net.*;
import java.util.*;

final class Lan {
  private static volatile List<NetworkPolicy.Cidr> approved = Collections.emptyList();

  static void approvedCidrs(String value) {
    approved = NetworkPolicy.parseCidrs(value);
  }

  static InetAddress privateAddress(String host) {
    return NetworkPolicy.privateAddress(host);
  }

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
      List<InterfaceAddress> local = interfaces();
      for (InterfaceAddress a : local)
        if (remote.equals(a.getAddress())) return false;
      for (InterfaceAddress a : local) {
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
      if (!local.isEmpty())
        for (NetworkPolicy.Cidr range : approved) if (range.contains(remote)) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  static InetAddress parse(String host) throws Exception {
    InetAddress a = privateAddress(host);
    if (!allowed(a))
      throw new Exception("Device must be on your local subnet or an explicitly approved routed subnet");
    return a;
  }

  static boolean sameSubnet(InetAddress remote) {
    try {
      for (InterfaceAddress local : interfaces()) {
        int prefix = local.getNetworkPrefixLength();
        if (prefix >= 1 && prefix <= 32) {
          int mask = (int) (0xffffffffL << (32 - prefix));
          if ((NetworkPolicy.integer(local.getAddress()) & mask)
              == (NetworkPolicy.integer(remote) & mask)) return true;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
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
