package app.relay.lan;

import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.json.*;

/** Availability-scoped hub presence. This connection never enters the voice-call state machine. */
final class HubClient implements Closeable {
  interface Listener {
    void status(String value);
    void directory(JSONArray devices);
    void offer(JSONObject offer);
    void capabilities(JSONArray capabilities);
    void event(JSONObject frame);
  }

  static final class HubException extends IOException {
    final String code;
    HubException(String code, String message) { super(message); this.code = code; }
    boolean fatal() {
      return code.equals("revoked") || code.equals("rejected") || code.equals("invalid_token")
          || code.equals("token_expired") || code.equals("certificate_mismatch");
    }
  }

  private final Identity identity;
  private final Store store;
  private final Listener listener;
  private final JSONObject config;
  private final int peerPort;
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
  private final Thread thread;
  private final Set<SSLSocket> sockets = ConcurrentHashMap.newKeySet();
  private final Random jitter = new Random();
  private final ThreadPoolExecutor controlWrites = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
  private volatile boolean closed, registered;
  private volatile SSLSocket control;

  private final String appVersion;

  HubClient(Identity identity, Store store, int port, String appVersion, Listener listener) {
    this.identity = identity;
    this.store = store;
    this.config = store.hub();
    this.peerPort = port;
    this.appVersion = appVersion;
    this.listener = listener;
    thread = new Thread(this::presence, "relay-hub-presence");
    thread.start();
  }

  boolean registered() { return registered && !closed; }
  String fingerprint() { return config.optString("fingerprint"); }

  private void presence() {
    long retry = 1000;
    try {
      while (!closed) {
        ScheduledFuture<?> ping = null;
        SSLSocket socket = null;
        try {
          listener.status("Connecting to desktop…");
          socket = connect();
          control = socket;
          JSONArray addresses = new JSONArray();
          for (InterfaceAddress address : Lan.interfaces())
            addresses.put(address.getAddress().getHostAddress());
          JSONObject request = Store.json("type", "register", "version", 1, "name", store.name(),
              "appVersion", appVersion, "addresses", addresses, "port", peerPort,
              "token", config.optString("token"), "capabilities",
              new JSONArray().put("dispatch-v1").put("emergency-v1"));
          write(socket, request);
          final SSLSocket active = socket;
          ping = heartbeat.scheduleAtFixedRate(() -> {
            try { write(active, Store.json("type", "ping")); }
            catch (Exception failure) { closeSocket(active); }
          }, 5, 5, TimeUnit.SECONDS);
          while (!closed) {
            JSONObject frame = read(socket);
            String type = frame.optString("type");
            if (type.equals("registered")) {
              if (!config.optString("fingerprint").equals(frame.optString("hubId")))
                throw new HubException("certificate_mismatch", "Desktop identity changed");
              registered = true;
              JSONArray features = frame.optJSONArray("capabilities");
              listener.capabilities(features == null ? new JSONArray() : features);
              retry = 1000;
              store.hubTokenAccepted(config.optString("fingerprint"), config.optString("token"));
              listener.status("Connected to desktop hub");
            } else if (type.equals("pending")) {
              store.hubTokenAccepted(config.optString("fingerprint"), config.optString("token"));
              listener.status("Waiting for approval in Relay Desktop Manager");
            } else if (type.equals("directory") && registered) {
              JSONArray devices = frame.optJSONArray("devices");
              if (devices == null || devices.length() > 1024)
                throw new IOException("Invalid desktop directory");
              listener.directory(devices);
            } else if (type.equals("offer") && registered) {
              if (!frame.optString("id").matches("[a-fA-F0-9]{32}")
                  || !frame.optString("from").matches("[A-F0-9]{64}"))
                throw new IOException("Invalid relay offer");
              listener.offer(frame);
            } else if (type.equals("ping")) {
              write(socket, Store.json("type", "pong"));
            } else if (type.equals("error")) {
              HubException failure = error(frame);
              if (!registered || failure.fatal()) throw failure;
              listener.event(frame);
            } else if (registered && (type.equals("dispatch_offer") || type.equals("emergency")
                || type.equals("emergency_resolved") || type.equals("emergency_snapshot") || type.equals("sos_ack"))) {
              listener.event(frame);
            } else if (!type.equals("pong")) {
              throw new IOException("Unexpected desktop message");
            }
          }
        } catch (Exception failure) {
          registered = false;
          listener.capabilities(new JSONArray());
          listener.event(Store.json("type", "hub_disconnected"));
          listener.directory(new JSONArray());
          if (!closed) {
            listener.status("Desktop: " + safeMessage(failure));
            if (failure instanceof HubException && ((HubException) failure).fatal()) break;
            if (failure instanceof SSLPeerUnverifiedException) break;
          }
        } finally {
          if (ping != null) ping.cancel(false);
          closeSocket(socket);
          control = null;
          registered = false;
        }
        if (!closed) {
          try { Thread.sleep(retry + jitter.nextInt(750)); }
          catch (InterruptedException interrupted) { break; }
          retry = Math.min(30000, retry * 2);
        }
      }
    } finally {
      heartbeat.shutdownNow();
      controlWrites.shutdownNow();
    }
  }

  private SSLSocket connect() throws Exception {
    return connect(null);
  }

  private SSLSocket connect(ConnectionCancellation cancellation) throws Exception {
    if (closed) throw new IOException("Desktop connection stopped");
    InetAddress host = Lan.privateAddress(config.optString("host"));
    if (Lan.interfaces().isEmpty()) throw new IOException("Connect to local Wi-Fi or Ethernet first");
    int port = config.optInt("port", 52744);
    Socket raw = new Socket();
    if (cancellation != null) cancellation.track(raw);
    SSLSocket socket = null;
    try {
      raw.connect(new InetSocketAddress(host, port), 6000);
      socket = (SSLSocket) identity.context.getSocketFactory()
          .createSocket(raw, host.getHostAddress(), port, true);
      sockets.add(socket);
      if (cancellation != null) cancellation.track(socket);
      socket.setEnabledProtocols(new String[] {"TLSv1.2"});
      socket.setEnabledCipherSuites(new String[] {
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});
      socket.setSoTimeout(20000);
      socket.setTcpNoDelay(true);
      socket.startHandshake();
      X509Certificate peer = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      peer.checkValidity();
      if (!Identity.fingerprint(peer).equals(config.optString("fingerprint")))
        throw new SSLPeerUnverifiedException("Desktop certificate mismatch. Verify its QR code again.");
      if (closed) throw new IOException("Desktop connection stopped");
      return socket;
    } catch (Exception failure) {
      if (socket != null) closeSocket(socket);
      else try { raw.close(); } catch (IOException ignored) { }
      throw failure;
    } finally {
      if (cancellation != null) {
        cancellation.release(raw);
        cancellation.release(socket);
      }
    }
  }

  TlsTunnel open(String fingerprint, String purpose, ConnectionCancellation cancellation) throws Exception {
    if (!registered()) throw new IOException("Desktop hub is not connected or approved");
    SSLSocket socket = connect(cancellation);
    try {
      cancellation.track(socket);
      write(socket, Store.json("type", "open", "version", 1,
          "target", fingerprint, "purpose", purpose));
      JSONObject response = read(socket);
      if (response.optString("type").equals("error")) throw error(response);
      if (!response.optString("type").equals("waiting"))
        throw new IOException("Invalid relay response");
      String id = response.optString("id");
      if (!id.matches("[a-fA-F0-9]{32}")) throw new IOException("Invalid relay session");
      JSONObject ready = read(socket);
      validateReady(ready, id, fingerprint, "client");
      TlsTunnel tunnel = new TlsTunnel(identity.context, socket.getInputStream(),
          socket.getOutputStream(), socket, true, fingerprint);
      // The call owns this socket now; hub revocation/server loss still closes it remotely.
      sockets.remove(socket);
      return tunnel;
    } catch (Exception failure) {
      closeSocket(socket);
      throw failure;
    } finally {
      cancellation.release(socket);
    }
  }

  TlsTunnel attach(JSONObject offer) throws Exception {
    if (!registered()) throw new IOException("Desktop hub is not connected");
    SSLSocket socket = connect();
    try {
      String id = offer.optString("id"), peer = offer.optString("from");
      write(socket, Store.json("type", "attach", "version", 1, "id", id));
      JSONObject ready = read(socket);
      validateReady(ready, id, peer, "server");
      TlsTunnel tunnel = new TlsTunnel(identity.context, socket.getInputStream(),
          socket.getOutputStream(), socket, false, peer);
      sockets.remove(socket);
      return tunnel;
    } catch (Exception failure) {
      closeSocket(socket);
      throw failure;
    }
  }

  SSLSocket attachDispatch(JSONObject offer, ConnectionCancellation cancellation) throws Exception {
    if (!registered()) throw new IOException("Desktop hub is not connected");
    String id = offer.optString("id"), mode = offer.optString("mode");
    if (!id.matches("[a-fA-F0-9]{32}") || !fingerprint().equals(offer.optString("from"))
        || !(mode.equals("call") || mode.equals("broadcast") || mode.equals("emergency")))
      throw new IOException("Invalid desktop dispatch offer");
    SSLSocket socket = connect(cancellation);
    try {
      cancellation.track(socket);
      write(socket, Store.json("type", "dispatch_attach", "version", 1, "id", id));
      JSONObject ready = read(socket);
      if (ready.optString("type").equals("error")) throw error(ready);
      if (!ready.optString("type").equals("dispatch_ready") || !id.equals(ready.optString("id"))
          || !mode.equals(ready.optString("mode")) || !fingerprint().equals(ready.optString("peer")))
        throw new IOException("Desktop dispatch endpoint identity mismatch");
      sockets.remove(socket);
      return socket;
    } catch (Exception failure) {
      closeSocket(socket);
      throw failure;
    } finally { cancellation.release(socket); }
  }

  void sendControl(JSONObject frame) throws IOException {
    SSLSocket socket = control;
    if (!registered() || socket == null) throw new IOException("Desktop hub is not connected or approved");
    try {
      controlWrites.execute(() -> {
        try { write(socket, frame); }
        catch (Exception failure) {
          listener.event(Store.json("type", "error", "code", "send_failed", "message", safeMessage(failure)));
        }
      });
    } catch (RejectedExecutionException busy) { throw new IOException("Desktop control queue is busy"); }
  }

  private static void validateReady(JSONObject frame, String id, String peer, String role)
      throws IOException {
    if (frame.optString("type").equals("error")) throw error(frame);
    if (!frame.optString("type").equals("ready") || !id.equals(frame.optString("id"))
        || !peer.equals(frame.optString("peer")) || !role.equals(frame.optString("role")))
      throw new IOException("Relay endpoint does not match the requested device");
  }

  private static HubException error(JSONObject frame) {
    return new HubException(frame.optString("code"), frame.optString("message", "Desktop refused request"));
  }

  static JSONObject read(SSLSocket socket) throws Exception {
    DataInputStream input = new DataInputStream(socket.getInputStream());
    int length = input.readInt();
    if (length < 2 || length > 65536) throw new IOException("Invalid desktop frame length");
    byte[] data = new byte[length];
    input.readFully(data);
    return new JSONObject(new String(data, StandardCharsets.UTF_8));
  }

  static void write(SSLSocket socket, JSONObject frame) throws IOException {
    byte[] data = frame.toString().getBytes(StandardCharsets.UTF_8);
    if (data.length > 65536) throw new IOException("Desktop frame is too large");
    synchronized (socket) {
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeInt(data.length);
      output.write(data);
      output.flush();
    }
  }

  private void closeSocket(SSLSocket socket) {
    if (socket == null) return;
    sockets.remove(socket);
    try { socket.close(); } catch (IOException ignored) { }
  }

  private static String safeMessage(Exception failure) {
    String value = failure.getMessage();
    return value == null ? "Connection lost" : value.replaceAll("[\\p{Cntrl}]", " ");
  }

  @Override public void close() {
    closed = true;
    registered = false;
    thread.interrupt();
    for (SSLSocket socket : new ArrayList<>(sockets)) closeSocket(socket);
    heartbeat.shutdownNow();
    controlWrites.shutdownNow();
  }
}
