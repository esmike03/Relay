package app.relay.lan;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict IPv4 parsing and explicit administrator-selected RFC1918 network ranges. */
final class NetworkPolicy {
  static InetAddress privateAddress(String value) {
    if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
      throw new IllegalArgumentException("Enter a private IPv4 address, such as 192.168.0.110");
    String[] parts = value.split("\\.");
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      int part = Integer.parseInt(parts[i]);
      if (part > 255) throw new IllegalArgumentException("IPv4 address contains an invalid number");
      bytes[i] = (byte) part;
    }
    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      if (!address.isSiteLocalAddress())
        throw new IllegalArgumentException("Only private IPv4 local-network addresses are supported");
      return address;
    } catch (java.net.UnknownHostException impossible) {
      throw new IllegalArgumentException(impossible);
    }
  }

  static final class Cidr {
    final int network, mask, prefix;

    Cidr(String value) {
      String[] parts = value.trim().split("/", -1);
      if (parts.length != 2) throw new IllegalArgumentException("Use CIDR notation, such as 192.168.20.0/24");
      InetAddress address = privateAddress(parts[0]);
      try {
        prefix = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid CIDR prefix");
      }
      if (prefix < 1 || prefix > 32) throw new IllegalArgumentException("Invalid CIDR prefix");
      mask = (int) (0xffffffffL << (32 - prefix));
      network = integer(address) & mask;
      long first = network & 0xffffffffL;
      long last = first | (~mask & 0xffffffffL);
      if (!privateNumber(first) || !privateNumber(last))
        throw new IllegalArgumentException("The entire approved CIDR must be within a private IPv4 range");
    }

    boolean contains(InetAddress address) {
      return address instanceof Inet4Address && (integer(address) & mask) == network;
    }

    @Override
    public String toString() {
      return ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "."
          + ((network >>> 8) & 255) + "." + (network & 255) + "/" + prefix;
    }
  }

  static List<Cidr> parseCidrs(String input) {
    List<Cidr> result = new ArrayList<>();
    if (input != null && !input.trim().isEmpty()) {
      for (String item : input.trim().split("[\\s,;]+")) {
        if (result.size() >= 32) throw new IllegalArgumentException("Approve at most 32 network ranges");
        result.add(new Cidr(item));
      }
    }
    return Collections.unmodifiableList(result);
  }

  static int integer(InetAddress address) {
    byte[] a = address.getAddress();
    return ((a[0] & 255) << 24) | ((a[1] & 255) << 16) | ((a[2] & 255) << 8) | (a[3] & 255);
  }

  private static boolean privateNumber(long address) {
    return (address & 0xff000000L) == 0x0a000000L
        || (address & 0xfff00000L) == 0xac100000L
        || (address & 0xffff0000L) == 0xc0a80000L;
  }
}
