package app.relay.lan;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.Test;

public class TlsTunnelTest {
  private static SSLContext context() throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream fixture = TlsTunnelTest.class.getResourceAsStream("/tunnel-test.p12")) {
      keys.load(fixture, "relay-test-only".toCharArray());
    }
    KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "relay-test-only".toCharArray());
    TrustManager trust = new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
        if (chain.length == 0) throw new java.security.cert.CertificateException();
        chain[0].checkValidity();
      }
      public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
        checkClientTrusted(chain, auth);
      }
    };
    SSLContext context = SSLContext.getInstance("TLSv1.2");
    context.init(managers.getKeyManagers(), new TrustManager[] {trust}, new SecureRandom());
    return context;
  }

  private static String pin() throws Exception {
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream fixture = TlsTunnelTest.class.getResourceAsStream("/tunnel-test.p12")) {
      keys.load(fixture, "relay-test-only".toCharArray());
    }
    return TlsTunnel.fingerprint(keys.getCertificate("test").getEncoded());
  }

  private static InputStream fragmented(InputStream input) {
    return new FilterInputStream(input) {
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return super.read(buffer, offset, Math.min(37, length));
      }
    };
  }

  @Test(timeout = 20000) public void mutuallyAuthenticatedTunnelHandlesFragmentedRecordsAndLargePayloads() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket server = listener.accept()) {
      client.setSoTimeout(10000);
      server.setSoTimeout(10000);
      String fingerprint = pin();
      Future<TlsTunnel> receiver = executor.submit(() -> new TlsTunnel(context(), fragmented(server.getInputStream()),
          server.getOutputStream(), server, false, fingerprint));
      try (TlsTunnel sender = new TlsTunnel(context(), fragmented(client.getInputStream()),
          client.getOutputStream(), client, true, fingerprint);
          TlsTunnel target = receiver.get(10, TimeUnit.SECONDS)) {
        assertEquals(fingerprint, sender.fingerprint);
        byte[] expected = new byte[100000];
        new SecureRandom().nextBytes(expected);
        Future<byte[]> received = executor.submit(() -> target.input().readNBytes(expected.length));
        sender.output().write(expected);
        assertArrayEquals(expected, received.get(10, TimeUnit.SECONDS));
        Future<byte[]> response = executor.submit(() -> sender.input().readNBytes(expected.length));
        target.output().write(expected);
        assertArrayEquals(expected, response.get(10, TimeUnit.SECONDS));
      }
    } finally { executor.shutdownNow(); }
  }

  @Test(timeout = 20000) public void wrongPinnedCertificateIsRejectedBeforeApplicationData() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket server = listener.accept()) {
      client.setSoTimeout(10000);
      server.setSoTimeout(10000);
      String valid = pin();
      Future<TlsTunnel> target = executor.submit(() -> new TlsTunnel(context(), server.getInputStream(),
          server.getOutputStream(), server, false, valid));
      try {
        new TlsTunnel(context(), client.getInputStream(), client.getOutputStream(), client, true,
            "0000000000000000000000000000000000000000000000000000000000000000");
        fail("Wrong peer certificate was accepted");
      } catch (SSLPeerUnverifiedException expected) { }
      try (TlsTunnel ignored = target.get(10, TimeUnit.SECONDS)) { }
    } finally { executor.shutdownNow(); }
  }
}
