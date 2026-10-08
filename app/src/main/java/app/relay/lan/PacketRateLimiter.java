package app.relay.lan;

/** Limits sustained traffic while allowing bounded delivery bursts from Wi-Fi and audio buffers. */
final class PacketRateLimiter {
  private final double capacity;
  private final double refillPerMillis;
  private double tokens;
  private long lastRefill;

  PacketRateLimiter(int burstPackets, int packetsPerSecond, long now) {
    capacity = burstPackets;
    tokens = burstPackets;
    refillPerMillis = packetsPerSecond / 1000.0;
    lastRefill = now;
  }

  boolean acquire(long now) {
    if (now > lastRefill) {
      tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMillis);
      lastRefill = now;
    }
    if (tokens < 1) return false;
    tokens -= 1;
    return true;
  }
}
