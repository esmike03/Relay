package app.relay.lan;

import static org.junit.Assert.*;
import java.net.InetAddress;
import org.junit.Test;

public class NetworkPolicyTest {
  @Test public void emptyPolicyDoesNotApproveEveryPrivateNetwork() {
    assertTrue(NetworkPolicy.parseCidrs("").isEmpty());
  }

  @Test public void explicitRangesAreNormalizedAndMatchOnlyTheirAddresses() throws Exception {
    NetworkPolicy.Cidr cidr = new NetworkPolicy.Cidr("192.168.20.42/24");
    assertEquals("192.168.20.0/24", cidr.toString());
    assertTrue(cidr.contains(InetAddress.getByName("192.168.20.250")));
    assertFalse(cidr.contains(InetAddress.getByName("192.168.21.1")));
    assertFalse(cidr.contains(InetAddress.getByName("::1")));
  }

  @Test public void singleHostApprovalDoesNotIncludeNeighbors() throws Exception {
    NetworkPolicy.Cidr cidr = new NetworkPolicy.Cidr("10.1.2.3/32");
    assertTrue(cidr.contains(InetAddress.getByName("10.1.2.3")));
    assertFalse(cidr.contains(InetAddress.getByName("10.1.2.4")));
  }

  @Test public void broadRangesCrossingPublicAddressesAreRejected() {
    for (String cidr : new String[] {"10.0.0.0/7", "172.16.0.0/11", "192.168.0.0/15",
        "0.0.0.0/0", "127.0.0.1/32", "169.254.1.1/32", "8.8.8.8/32"}) {
      try { new NetworkPolicy.Cidr(cidr); fail(cidr); }
      catch (IllegalArgumentException expected) { }
    }
  }

  @Test public void parserRejectsDnsOctalShortFormsAndInvalidOctets() {
    for (String address : new String[] {"localhost", "127.1", "10.2.3.256", "10.0.0.-1",
        "0x0a000001", "10.1.2.3:52744", "::1"}) {
      try { NetworkPolicy.privateAddress(address); fail(address); }
      catch (IllegalArgumentException expected) { }
    }
  }

  @Test public void allRfc1918BoundaryRangesAreSupported() {
    assertEquals(3, NetworkPolicy.parseCidrs("10.0.0.0/8\n172.16.0.0/12,192.168.0.0/16").size());
  }
}
