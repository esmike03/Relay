package app.relay.lan;

/** Hub-authoritative floor plus local consent. A delayed grant can never revive a released mic. */
final class DispatchFloor {
  private final boolean duplex;
  private final String local, hub;
  private String owner = "";
  private boolean held, visible;

  DispatchFloor(String mode, String local, String hub) {
    if (!(mode.equals("call") || mode.equals("broadcast") || mode.equals("emergency")))
      throw new IllegalArgumentException("Invalid dispatch mode");
    this.duplex = mode.equals("call");
    this.local = local;
    this.hub = hub;
  }

  synchronized void visible(boolean value) { visible = value; if (!value) held = false; }
  synchronized void hold(boolean value) { held = duplex && visible && value; }
  synchronized boolean held() { return held; }
  synchronized boolean mayCapture() { return duplex && visible && held && owner.equals(local); }
  synchronized boolean remoteTalking() { return owner.equals(hub); }
  synchronized void owner(String value) {
    if (!(value.isEmpty() || value.equals(local) || value.equals(hub)))
      throw new IllegalArgumentException("Invalid dispatch floor owner");
    if (!duplex && value.equals(local))
      throw new IllegalArgumentException("Receive-only dispatch cannot grant microphone access");
    owner = value;
  }
}
