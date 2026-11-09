package app.relay.lan;

import static org.junit.Assert.*;
import org.junit.Test;

/** Consent/floor regressions: a network grant is never sufficient to turn on the microphone. */
public class DispatchFloorTest {
  @Test public void holdingTalkWaitsForHubGrant() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    floor.visible(true);
    floor.hold(true);
    assertFalse(floor.mayCapture());
    floor.owner("hub");
    assertFalse(floor.mayCapture());
    floor.owner("phone");
    assertTrue(floor.mayCapture());
    floor.owner("");
    assertFalse(floor.mayCapture());
  }

  @Test public void grantAloneCannotStartCapture() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    floor.owner("phone");
    assertFalse(floor.mayCapture());
    floor.visible(true);
    assertFalse(floor.mayCapture());
  }

  @Test public void grantArrivingAfterReleaseCannotReviveCapture() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    floor.visible(true);
    floor.hold(true);
    floor.hold(false);
    floor.owner("phone");
    assertFalse(floor.mayCapture());
  }

  @Test public void leavingCallScreenReleasesConsentAndResumeRequiresNewPress() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    floor.visible(true);
    floor.hold(true);
    floor.owner("phone");
    assertTrue(floor.mayCapture());
    floor.visible(false);
    assertFalse(floor.mayCapture());
    floor.visible(true);
    assertFalse(floor.mayCapture());
    floor.hold(true);
    assertTrue(floor.mayCapture());
  }

  @Test public void hiddenScreenCannotAcquireLocalConsent() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    floor.hold(true);
    floor.owner("phone");
    floor.visible(true);
    assertFalse(floor.mayCapture());
  }

  @Test public void broadcastAndEmergencyNeverGrantDeviceMicrophone() {
    for (String mode : new String[] {"broadcast", "emergency"}) {
      DispatchFloor floor = new DispatchFloor(mode, "phone", "hub");
      floor.visible(true);
      floor.hold(true);
      floor.owner("hub");
      assertTrue(floor.remoteTalking());
      assertFalse(floor.mayCapture());
      try { floor.owner("phone"); fail("Receive-only mode accepted local microphone grant"); }
      catch (IllegalArgumentException expected) { }
      assertFalse(floor.mayCapture());
    }
  }

  @Test public void unrelatedDeviceCannotBeTheFloorOwner() {
    DispatchFloor floor = new DispatchFloor("call", "phone", "hub");
    try { floor.owner("unrelated"); fail("Unknown floor owner accepted"); }
    catch (IllegalArgumentException expected) { }
  }
}
