package app.relay.lan;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Resources belong to one connection attempt, including resources registered after cancellation. */
final class ConnectionCancellation implements Closeable {
  private final Set<Closeable> resources = new HashSet<>();
  private volatile boolean cancelled;

  <T extends Closeable> T track(T resource) throws IOException {
    synchronized (this) {
      if (!cancelled) {
        resources.add(resource);
        return resource;
      }
    }
    try { resource.close(); } catch (IOException ignored) { }
    throw new InterruptedIOException("Connection cancelled");
  }

  synchronized void release(Closeable resource) { resources.remove(resource); }
  boolean isCancelled() { return cancelled; }

  @Override public void close() {
    ArrayList<Closeable> close;
    synchronized (this) {
      if (cancelled) return;
      cancelled = true;
      close = new ArrayList<>(resources);
      resources.clear();
    }
    for (Closeable resource : close) try { resource.close(); } catch (IOException ignored) { }
  }
}
