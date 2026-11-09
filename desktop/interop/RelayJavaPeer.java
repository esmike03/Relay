import app.relay.lan.TlsTunnel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.ssl.*;

/** Runs the exact Android inner-TLS implementation against the real .NET hub. */
public final class RelayJavaPeer {
  static String host, hubFp;
  static int port;
  static final class Peer implements AutoCloseable {
    final SSLContext context;
    final String fingerprint;
    SSLSocket control;
    Peer(String file, String password) throws Exception {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      try (InputStream input = Files.newInputStream(Paths.get(file))) { ks.load(input, password.toCharArray()); }
      String alias = ks.aliases().nextElement();
      fingerprint = TlsTunnel.fingerprint(ks.getCertificate(alias).getEncoded());
      KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(ks, password.toCharArray());
      context = SSLContext.getInstance("TLSv1.2");
      context.init(km.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException { c[0].checkValidity(); }
        public void checkServerTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException { c[0].checkValidity(); }
      }}, new SecureRandom());
    }
    SSLSocket socket() throws Exception {
      SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
      socket.setEnabledProtocols(new String[] {"TLSv1.2"});
      socket.setSoTimeout(15000); socket.setTcpNoDelay(true); socket.startHandshake();
      if (!hubFp.equals(TlsTunnel.fingerprint(socket.getSession().getPeerCertificates()[0].getEncoded()))) {
        socket.close(); throw new SSLPeerUnverifiedException("Hub pin mismatch");
      }
      return socket;
    }
    void register(String token, String name) throws Exception {
      control = socket();
      write(control.getOutputStream(), "{\"type\":\"register\",\"version\":1,\"name\":\"" + name
          + "\",\"appVersion\":\"interop\",\"addresses\":[\"192.168.20.2\"],\"port\":52743,\"token\":\"" + token + "\"}");
      until(control, "registered");
    }
    public void close() throws Exception { if (control != null) control.close(); }
  }
  static void write(OutputStream output, String json) throws Exception {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    DataOutputStream stream = new DataOutputStream(output);
    stream.writeInt(bytes.length); stream.write(bytes); stream.flush();
  }
  static String read(InputStream input) throws Exception {
    DataInputStream stream = new DataInputStream(input);
    int size = stream.readInt();
    if (size < 2 || size > 65536) throw new IOException("Invalid framed packet size");
    byte[] bytes = new byte[size]; stream.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
  static String field(String json, String name) throws IOException {
    Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
    if (!m.find()) throw new IOException("Missing field " + name + " in " + json);
    String value = m.group(1);
    StringBuilder decoded = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char next = value.charAt(i);
      if (next != '\\') { decoded.append(next); continue; }
      if (++i >= value.length()) throw new IOException("Invalid JSON escape");
      switch (value.charAt(i)) {
        case '"': decoded.append('"'); break;
        case '\\': decoded.append('\\'); break;
        case '/': decoded.append('/'); break;
        case 'b': decoded.append('\b'); break;
        case 'f': decoded.append('\f'); break;
        case 'n': decoded.append('\n'); break;
        case 'r': decoded.append('\r'); break;
        case 't': decoded.append('\t'); break;
        case 'u':
          if (i + 4 >= value.length()) throw new IOException("Invalid Unicode escape");
          decoded.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
          i += 4;
          break;
        default: throw new IOException("Invalid JSON escape");
      }
    }
    return decoded.toString();
  }
  static String until(SSLSocket socket, String type) throws Exception {
    for (int i = 0; i < 100; i++) {
      String packet = read(socket.getInputStream()), actual = field(packet, "type");
      if (actual.equals("error")) throw new IOException("Hub rejected request: " + packet);
      if (actual.equals(type)) return packet;
      if (actual.equals("ping")) write(socket.getOutputStream(), "{\"type\":\"pong\"}");
    }
    throw new IOException("Expected " + type);
  }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void round(Peer caller, Peer target, boolean wrongPin) throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> receiving = worker.submit(() -> {
      try {
        String offer = until(target.control, "offer"), id = field(offer, "id");
        check(field(offer, "from").equals(caller.fingerprint), "Offer identity mismatch");
        try (SSLSocket socket = target.socket()) {
          write(socket.getOutputStream(), "{\"type\":\"attach\",\"version\":1,\"id\":\"" + id + "\"}");
          String ready = until(socket, "ready");
          check(field(ready, "peer").equals(caller.fingerprint), "Target ready identity mismatch");
          try (TlsTunnel inner = new TlsTunnel(target.context, socket.getInputStream(), socket.getOutputStream(),
              socket, false, caller.fingerprint)) {
            if (wrongPin) {
              try { inner.input().read(); } catch (IOException expected) { }
              return;
            }
            check(field(read(inner.input()), "type").equals("call"), "Expected call");
            write(inner.output(), "{\"type\":\"accept\"}");
            check(field(read(inner.input()), "type").equals("ready"), "Expected ready");
            for (int i = 0; i < 300; i++) {
              String audio = read(inner.input());
              check(field(audio, "type").equals("audio"), "Expected audio");
              byte[] pcm = Base64.getDecoder().decode(field(audio, "data"));
              check(pcm.length == 640 && (pcm[0] & 255) == (i & 255), "Corrupt PCM frame " + i);
              write(inner.output(), audio); // Full-duplex response exercises independent SSLEngine wrap/unwrap.
            }
            check(field(read(inner.input()), "type").equals("talk"), "Expected final release");
            write(inner.output(), "{\"type\":\"end\"}");
          }
        }
      } catch (Exception e) { throw new RuntimeException(e); }
    });
    try (SSLSocket socket = caller.socket()) {
      write(socket.getOutputStream(), "{\"type\":\"open\",\"version\":1,\"target\":\"" + target.fingerprint + "\",\"purpose\":\"call\"}");
      until(socket, "waiting");
      String ready = until(socket, "ready");
      check(field(ready, "peer").equals(target.fingerprint), "Caller ready identity mismatch");
      try (TlsTunnel inner = new TlsTunnel(caller.context, socket.getInputStream(), socket.getOutputStream(),
          socket, true, wrongPin ? String.join("", Collections.nCopies(64, "0")) : target.fingerprint)) {
        check(!wrongPin, "Mismatched peer pin was accepted");
        write(inner.output(), "{\"type\":\"call\",\"version\":1,\"name\":\"JVM caller\",\"port\":52743,\"auto\":false}");
        check(field(read(inner.input()), "type").equals("accept"), "Expected accept");
        write(inner.output(), "{\"type\":\"ready\"}");
        byte[] pcm = new byte[640];
        for (int i = 0; i < 300; i++) {
          Arrays.fill(pcm, (byte) i);
          String json = "{\"type\":\"audio\",\"data\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}";
          write(inner.output(), json);
          check(json.equals(read(inner.input())), "Audio echo differs");
        }
        write(inner.output(), "{\"type\":\"talk\",\"on\":false}");
        check(field(read(inner.input()), "type").equals("end"), "Expected end");
      } catch (SSLPeerUnverifiedException e) {
        if (!wrongPin) throw e;
      }
      receiving.get(20, TimeUnit.SECONDS);
    } finally { worker.shutdownNow(); }
  }
  public static void main(String[] args) throws Exception {
    host = args[0]; port = Integer.parseInt(args[1]); hubFp = args[2];
    try (Peer a = new Peer(args[5], args[7]); Peer b = new Peer(args[6], args[7])) {
      a.register(args[3], "Java caller"); b.register(args[4], "Java receiver");
      round(a, b, false);
      System.out.println("PASS: Java Android TLS transport through .NET hub: mutual TLS, call handshake, 300 bidirectional PCM frames, ordered release");
      round(a, b, true);
      System.out.println("PASS: mismatched inner peer certificate rejected through authenticated hub");
    }
  }
}
