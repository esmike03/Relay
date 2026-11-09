package app.relay.lan;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.Test;

public class ConnectionCancellationTest {
  private static final class Resource implements Closeable {
    boolean closed;
    public void close() { closed = true; }
  }

  @Test public void resourcesRegisteredAfterCancellationAreClosedImmediately() throws Exception {
    ConnectionCancellation cancelled = new ConnectionCancellation();
    cancelled.close();
    Resource late = new Resource();
    try { cancelled.track(late); fail("Late resource accepted"); }
    catch (InterruptedIOException expected) { }
    assertTrue(late.closed);
  }

  @Test public void supersededAttemptsCannotLoseOrCloseNewAttemptResources() throws Exception {
    ConnectionCancellation oldAttempt = new ConnectionCancellation();
    ConnectionCancellation newAttempt = new ConnectionCancellation();
    Resource oldSocket = oldAttempt.track(new Resource());
    Resource newSocket = newAttempt.track(new Resource());
    oldAttempt.close();
    assertTrue(oldSocket.closed);
    assertFalse(newSocket.closed);
    Resource lateOldSocket = new Resource();
    try { oldAttempt.track(lateOldSocket); fail("Old attempt was revived"); }
    catch (InterruptedIOException expected) { }
    assertTrue(lateOldSocket.closed);
    newAttempt.close();
    assertTrue(newSocket.closed);
  }

  @Test(timeout = 5000) public void cancellingAttemptInterruptsBlockedSocketRead() throws Exception {
    ExecutorService reader = Executors.newSingleThreadExecutor();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket server = listener.accept()) {
      ConnectionCancellation cancellation = new ConnectionCancellation();
      cancellation.track(client);
      Future<Boolean> interrupted = reader.submit(() -> {
        try { client.getInputStream().read(); return false; }
        catch (IOException expected) { return true; }
      });
      cancellation.close();
      assertTrue(interrupted.get(2, TimeUnit.SECONDS));
    } finally { reader.shutdownNow(); }
  }
}
