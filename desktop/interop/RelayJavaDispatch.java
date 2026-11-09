import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;

/** Synthetic Android-format TLS endpoints; no microphone or audio hardware is used. */
public final class RelayJavaDispatch {
  static final ExecutorService workers = Executors.newFixedThreadPool(2);
  static final AtomicBoolean closing = new AtomicBoolean();
  static void signal(String text) { System.out.println(text); System.out.flush(); }
  static final class Device implements AutoCloseable {
    final RelayJavaPeer.Peer identity;
    final String tag;
    volatile SSLSocket data;
    Device(String file, String password, String tag) throws Exception {
      this.identity = new RelayJavaPeer.Peer(file, password); this.tag = tag;
    }
    void start() throws Exception {
      identity.control = identity.socket();
      send(identity.control, "{\"type\":\"register\",\"version\":1,\"name\":\"Java dispatch " + tag
          + "\",\"appVersion\":\"2.1-interop\",\"port\":52743,\"addresses\":[\"192.168.20.2\"],"
          + "\"capabilities\":[\"dispatch-v1\",\"emergency-v1\"]}");
      RelayJavaPeer.until(identity.control, "registered");
      Thread control = new Thread(() -> {
        try {
          while (!closing.get()) {
            String frame = RelayJavaPeer.read(identity.control.getInputStream());
            String type = RelayJavaPeer.field(frame, "type");
            if (type.equals("ping")) send(identity.control, "{\"type\":\"pong\"}");
            else if (type.equals("dispatch_offer")) workers.submit(() -> serve(frame));
            else if (type.equals("emergency")) {
              String from = RelayJavaPeer.field(frame, "from");
              RelayJavaPeer.check(from.matches("[A-F0-9]{64}"), "Invalid emergency sender");
              signal("ALERT_" + tag + "_" + RelayJavaPeer.field(frame, "id"));
            } else if (type.equals("emergency_resolved")) {
              RelayJavaPeer.check(RelayJavaPeer.field(frame, "by").equals(RelayJavaPeer.hubFp), "Resolver identity mismatch");
              signal("RESOLVED_" + tag + "_" + RelayJavaPeer.field(frame, "id"));
            } else if (type.equals("sos_ack")) signal("SOS_ACK_" + tag + "_" + RelayJavaPeer.field(frame, "id"));
            else if (type.equals("error")) throw new IOException("Hub error " + frame);
          }
        } catch (Exception e) { if (!closing.get()) { signal("FAIL_CONTROL_" + tag + ": " + e); } }
      }, "java-dispatch-control-" + tag);
      control.setDaemon(true); control.start();
    }
    void serve(String offer) {
      String mode = "";
      try {
        String id = RelayJavaPeer.field(offer, "id"); mode = RelayJavaPeer.field(offer, "mode");
        RelayJavaPeer.check(RelayJavaPeer.field(offer, "from").equals(RelayJavaPeer.hubFp), "Offer hub identity mismatch");
        try (SSLSocket socket = identity.socket()) {
          data = socket;
          send(socket, "{\"type\":\"dispatch_attach\",\"version\":1,\"id\":\"" + id + "\"}");
          String ready = RelayJavaPeer.until(socket, "dispatch_ready");
          RelayJavaPeer.check(RelayJavaPeer.field(ready, "id").equals(id)
              && RelayJavaPeer.field(ready, "peer").equals(RelayJavaPeer.hubFp), "Ready endpoint mismatch");
          signal("ATTACHED_" + tag + "_" + mode);
          int received = 0;
          boolean replyNeeded = false, replySent = false;
          while (!closing.get()) {
            String packet = RelayJavaPeer.read(socket.getInputStream());
            String type = RelayJavaPeer.field(packet, "type");
            if (type.equals("ping")) send(socket, "{\"type\":\"pong\"}");
            else if (type.equals("end")) break;
            else if (type.equals("audio")) {
              byte[] pcm = Base64.getDecoder().decode(RelayJavaPeer.field(packet, "data"));
              RelayJavaPeer.check(pcm.length == 640 && (pcm[0] & 255) == (received & 255), "Corrupt desktop PCM frame");
              received++;
              if (received == 100) {
                replyNeeded = mode.equals("call");
                signal("RX_" + tag + "_" + mode);
              }
            } else if (type.equals("floor")) {
              String owner = RelayJavaPeer.field(packet, "owner");
              if (replyNeeded && !replySent && owner.isEmpty()) send(socket, "{\"type\":\"talk\",\"on\":true}");
              else if (replyNeeded && !replySent && owner.equals(identity.fingerprint)) {
                replySent = true;
                for (int i = 0; i < 100; i++) {
                  byte[] pcm = new byte[640]; Arrays.fill(pcm, (byte) (i + 10));
                  send(socket, "{\"type\":\"audio\",\"data\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}");
                  Thread.sleep(10);
                }
                send(socket, "{\"type\":\"talk\",\"on\":false}");
                signal("TX_" + tag + "_call");
              }
            } else throw new IOException("Unexpected dispatch frame " + type);
          }
        } finally { data = null; }
        signal("END_" + tag + "_" + mode);
      } catch (Exception failure) { if (!closing.get()) signal("FAIL_DISPATCH_" + tag + "_" + mode + ": " + failure); }
    }
    void sos() throws Exception {
      String id = UUID.randomUUID().toString().replace("-", "");
      signal("SOS_ID_" + id);
      send(identity.control, "{\"type\":\"sos\",\"id\":\"" + id + "\",\"message\":\"Java SOS test\"}");
    }
    public void close() throws Exception { if (data != null) data.close(); identity.close(); }
  }
  static void send(SSLSocket socket, String json) throws Exception {
    synchronized (socket) { RelayJavaPeer.write(socket.getOutputStream(), json); }
  }
  public static void main(String[] args) throws Exception {
    RelayJavaPeer.host = args[0]; RelayJavaPeer.port = Integer.parseInt(args[1]); RelayJavaPeer.hubFp = args[2];
    try (Device a = new Device(args[3], args[5], "A"); Device b = new Device(args[4], args[5], "B")) {
      a.start(); b.start(); signal("DISPATCH_READY");
      BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
      String command;
      while ((command = commands.readLine()) != null) {
        if (command.equals("sos")) a.sos();
        else if (command.equals("quit")) break;
      }
      closing.set(true);
    } finally { closing.set(true); workers.shutdownNow(); }
  }
}
