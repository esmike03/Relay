package app.relay.lan;

import static org.junit.Assert.*;
import org.junit.Test;

/** Raw connection errors are turned into a short title, explanation and technical line. */
public class ErrorDescriptionTest {
  @Test public void desktopRefusedNamesTheHostAndFirewall() {
    String[] e = MainActivity.describeError(
        "Desktop: failed to connect to /192.168.0.130 (port 52744) from /192.168.0.110 (port 45582)"
            + " after 6000ms: isConnected failed: ECONNREFUSED (Connection refused)");
    assertEquals("CONNECTION REFUSED", e[0]);
    assertTrue(e[1].contains("Relay Desktop Manager") && e[1].contains("192.168.0.130"));
    assertTrue(e[2].contains("Windows Firewall"));
    assertEquals("192.168.0.130:52744 · ECONNREFUSED", e[3]);
  }

  @Test public void peerTimeoutTalksAboutTheOtherDevice() {
    String[] e = MainActivity.describeError(
        "Connection failed: failed to connect to /192.168.0.159 (port 52743) after 5000ms");
    assertEquals("NO RESPONSE", e[0]);
    assertTrue(e[1].startsWith("The device at 192.168.0.159"));
    assertEquals("192.168.0.159:52743", e[3]);
  }

  @Test public void unknownMessagesPassThroughUnchanged() {
    String[] e = MainActivity.describeError("That device is busy. Try again shortly.");
    assertEquals("PROBLEM", e[0]);
    assertEquals("That device is busy. Try again shortly.", e[1]);
    assertNull(e[2]);
    assertEquals("", e[3]);
  }
}
