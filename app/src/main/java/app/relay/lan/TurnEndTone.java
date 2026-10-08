package app.relay.lan;

/** A short original two-note handover cue, matching the call's mono PCM format. */
final class TurnEndTone {
  static final byte[] PCM = create();

  private static byte[] create() {
    byte[] pcm = new byte[5760]; // 180 ms, 16 kHz, mono, signed 16-bit little endian.
    for (int i = 0; i < pcm.length / 2; i++) {
      double time = i / 16000.0;
      double start = time < .08 ? 0 : .10;
      double duration = .08;
      double elapsed = time - start;
      if (elapsed < 0 || elapsed >= duration) continue;
      double envelope = Math.min(1, Math.min(elapsed / .008, (duration - elapsed) / .012));
      double frequency = start == 0 ? 1000 : 1400;
      short sample = (short) (10000 * envelope * Math.sin(2 * Math.PI * frequency * elapsed));
      pcm[2 * i] = (byte) sample;
      pcm[2 * i + 1] = (byte) (sample >> 8);
    }
    return pcm;
  }
}
