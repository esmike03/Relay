package app.relay.lan;

import static org.junit.Assert.*;

import org.junit.Test;

public class PacketRateLimiterTest {
  @Test
  public void continuousMicrophoneFramesRemainConnected() {
    PacketRateLimiter audio = new PacketRateLimiter(150, 70, 0);
    for (long now = 0; now < 300_000; now += 20) assertTrue(audio.acquire(now));
  }

  @Test
  public void bufferedMicrophoneBurstsRemainConnected() {
    PacketRateLimiter audio = new PacketRateLimiter(150, 70, 0);
    PacketRateLimiter all = new PacketRateLimiter(256, 100, 0);
    // 2.4 seconds of ordinary 20 ms audio delivered together. The old
    // 70-audio / 100-total fixed windows disconnected this valid stream.
    for (long now = 0; now < 300_000; now += 2400) {
      for (int packet = 0; packet < 120; packet++) {
        assertTrue(audio.acquire(now));
        assertTrue(all.acquire(now));
      }
      assertTrue(all.acquire(now)); // Keepalive or talk status alongside audio.
    }
  }

  @Test
  public void sustainedAudioFloodIsRejected() {
    PacketRateLimiter audio = new PacketRateLimiter(150, 70, 0);
    boolean rejected = false;
    for (long now = 0; now < 2000; now += 5) {
      if (!audio.acquire(now)) {
        rejected = true;
        break;
      }
    }
    assertTrue(rejected);
  }

  @Test
  public void unboundedPacketBurstIsRejected() {
    PacketRateLimiter all = new PacketRateLimiter(256, 100, 0);
    for (int packet = 0; packet < 256; packet++) assertTrue(all.acquire(0));
    assertFalse(all.acquire(0));
    assertTrue(all.acquire(10));
  }
}
