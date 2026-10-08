package app.relay.lan;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;

final class WavRecorder implements Closeable {
  private final RandomAccessFile file;
  final String name;
  private final long started = SystemClock.elapsedRealtime();
  private long dataSize;
  private long lastHeader;
  private long localNext, remoteNext;

  WavRecorder(Context c) throws IOException {
    File dir = new File(c.getFilesDir(), "recordings");
    dir.mkdirs();
    if (dir.getUsableSpace() < 64L * 1024 * 1024)
      throw new IOException("Not enough storage for a recording");
    name = "relay_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID() + ".wav";
    file = new RandomAccessFile(new File(dir, name), "rw");
    file.setLength(44);
  }

  synchronized void append(byte[] pcm, boolean local) throws IOException {
    if (SystemClock.elapsedRealtime() - started > 30 * 60 * 1000)
      throw new IOException("Recording reached the 30-minute limit; start another segment");
    long frame =
        Math.max((SystemClock.elapsedRealtime() - started) * 16, local ? localNext : remoteNext);
    long offset = frame * 4;
    int samples = pcm.length / 2;
    if (local) localNext = frame + samples;
    else remoteNext = frame + samples;
    byte[] stereo = new byte[samples * 4];
    if (offset < dataSize) {
      file.seek(44 + offset);
      file.read(stereo, 0, (int) Math.min(stereo.length, dataSize - offset));
    }
    for (int i = 0; i < samples; i++) {
      int p = i * 4 + (local ? 0 : 2);
      stereo[p] = pcm[2 * i];
      stereo[p + 1] = pcm[2 * i + 1];
    }
    file.seek(44 + offset);
    file.write(stereo);
    dataSize = Math.max(dataSize, offset + stereo.length);
    if (SystemClock.elapsedRealtime() - lastHeader > 1000) {
      header(file, dataSize);
      lastHeader = SystemClock.elapsedRealtime();
    }
  }

  private static void le(RandomAccessFile f, int x, int size) throws IOException {
    for (int i = 0; i < size; i++) f.write((x >>> (8 * i)) & 255);
  }

  private static void header(RandomAccessFile f, long size) throws IOException {
    f.seek(0);
    f.writeBytes("RIFF");
    le(f, (int) size + 36, 4);
    f.writeBytes("WAVEfmt ");
    le(f, 16, 4);
    le(f, 1, 2);
    le(f, 2, 2);
    le(f, 16000, 4);
    le(f, 64000, 4);
    le(f, 4, 2);
    le(f, 16, 2);
    f.writeBytes("data");
    le(f, (int) size, 4);
  }

  static void recover(File f) throws IOException {
    try (RandomAccessFile a = new RandomAccessFile(f, "rw")) {
      header(a, Math.max(0, a.length() - 44));
    }
  }

  public synchronized void close() throws IOException {
    dataSize =
        Math.max(dataSize, Math.min(SystemClock.elapsedRealtime() - started, 30 * 60 * 1000) * 64);
    try {
      file.setLength(44 + dataSize);
      header(file, dataSize);
    } finally {
      file.close();
    }
  }
}
