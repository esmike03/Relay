package app.relay.lan;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import org.json.JSONObject;

/** A dedicated pinned hub-participant session, distinct from opaque end-to-end peer tunnels. */
final class DispatchConnection implements Closeable {
  interface Listener { void frame(JSONObject frame); void closed(String reason); }
  private final SSLSocket socket;
  private final Listener listener;
  private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(96);
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile boolean drain;

  DispatchConnection(SSLSocket socket, Listener listener) {
    this.socket = socket;
    this.listener = listener;
  }

  void start() {
    new Thread(() -> {
      long lastPing = System.nanoTime();
      try {
        while (!closed.get()) {
          JSONObject frame = queue.poll(250, TimeUnit.MILLISECONDS);
          if (frame != null) HubClient.write(socket, frame);
          else if (drain) break;
          if (!drain && System.nanoTime() - lastPing > TimeUnit.SECONDS.toNanos(5)) {
            HubClient.write(socket, Store.json("type", "ping"));
            lastPing = System.nanoTime();
          }
        }
      } catch (Exception failure) {
        finish("Desktop dispatch connection lost");
      } finally { finish("Desktop dispatch ended"); }
    }, "relay-dispatch-send").start();
    new Thread(() -> {
      long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
      PacketRateLimiter packets = new PacketRateLimiter(256, 100, now);
      PacketRateLimiter audio = new PacketRateLimiter(150, 70, now);
      try {
        while (!closed.get()) {
          JSONObject frame = HubClient.read(socket);
          now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
          if (!packets.acquire(now)) throw new IOException("Dispatch packet rate exceeded");
          String type = frame.optString("type");
          if (type.equals("audio") && !audio.acquire(now)) throw new IOException("Dispatch audio rate exceeded");
          if (type.equals("ping")) send(Store.json("type", "pong"));
          else if (!type.equals("pong")) listener.frame(frame);
        }
      } catch (Exception failure) {
        finish("Desktop dispatch connection lost");
      }
    }, "relay-dispatch-receive").start();
  }

  void send(JSONObject frame) {
    if (!closed.get() && !queue.offer(frame)) finish("Desktop dispatch send queue is full");
  }

  void closeAfterWrite() { drain = true; }
  private void finish(String reason) {
    if (!closed.compareAndSet(false, true)) return;
    try { socket.close(); } catch (IOException ignored) { }
    queue.clear();
    listener.closed(reason);
  }
  @Override public void close() { finish("Desktop dispatch ended"); }
}
