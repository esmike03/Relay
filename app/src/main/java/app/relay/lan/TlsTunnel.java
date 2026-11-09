package app.relay.lan;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Peer-authenticated TLS over an arbitrary reliable duplex stream. The desktop only sees these
 * encrypted records. This class deliberately uses no Android APIs so its exact implementation can
 * be exercised by JVM interoperability tests.
 */
public final class TlsTunnel implements Closeable {
  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
  private static final int MAX_BUFFER = 65536;
  private final SSLEngine engine;
  private final InputStream wireIn;
  private final OutputStream wireOut;
  private final Closeable connection;
  private final Object readLock = new Object(), writeLock = new Object();
  private ByteBuffer encrypted;
  private ByteBuffer plaintext;
  private volatile boolean closed;
  public final String fingerprint;

  public TlsTunnel(SSLContext context, InputStream input, OutputStream output,
      Closeable connection, boolean client, String expectedFingerprint) throws Exception {
    this.wireIn = input;
    this.wireOut = output;
    this.connection = connection;
    engine = context.createSSLEngine();
    engine.setUseClientMode(client);
    if (!client) engine.setNeedClientAuth(true);
    engine.setEnabledProtocols(new String[] {"TLSv1.2"});
    engine.setEnabledCipherSuites(new String[] {
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});
    encrypted = ByteBuffer.allocate(Math.max(32768, engine.getSession().getPacketBufferSize()));
    plaintext = ByteBuffer.allocate(Math.max(32768, engine.getSession().getApplicationBufferSize()));
    try {
      handshake();
      fingerprint = fingerprint(engine.getSession().getPeerCertificates()[0].getEncoded());
      if (expectedFingerprint == null || !expectedFingerprint.matches("[A-F0-9]{64}")
          || !fingerprint.equals(expectedFingerprint))
        throw new SSLPeerUnverifiedException("Peer certificate does not match the requested device");
      plaintext.flip();
    } catch (Exception failure) {
      try { close(); } catch (IOException ignored) { }
      throw failure;
    }
  }

  private void handshake() throws IOException {
    engine.beginHandshake();
    int transitions = 0;
    while (true) {
      if (++transitions > 512) throw new SSLException("TLS handshake exceeded its work limit");
      SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
      switch (status) {
        case FINISHED:
        case NOT_HANDSHAKING:
          return;
        case NEED_TASK:
          Runnable task;
          while ((task = engine.getDelegatedTask()) != null) task.run();
          break;
        case NEED_WRAP:
          wrap(EMPTY.duplicate());
          break;
        case NEED_UNWRAP:
          SSLEngineResult result = unwrap(plaintext);
          if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) readEncrypted();
          else if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
            plaintext = grow(plaintext);
          else if (result.getStatus() == SSLEngineResult.Status.CLOSED)
            throw new EOFException("Peer closed during TLS handshake");
          break;
        default:
          throw new SSLException("Unsupported TLS handshake state");
      }
    }
  }

  private SSLEngineResult unwrap(ByteBuffer destination) throws SSLException {
    encrypted.flip();
    SSLEngineResult result = engine.unwrap(encrypted, destination);
    encrypted.compact();
    return result;
  }

  private void readEncrypted() throws IOException {
    if (!encrypted.hasRemaining()) encrypted = grow(encrypted);
    int count = wireIn.read(encrypted.array(), encrypted.position(), encrypted.remaining());
    if (count < 0) throw new EOFException("Encrypted peer connection closed");
    encrypted.position(encrypted.position() + count);
  }

  private static ByteBuffer grow(ByteBuffer original) throws SSLException {
    if (original.capacity() >= MAX_BUFFER) throw new SSLException("TLS record is too large");
    ByteBuffer larger = ByteBuffer.allocate(Math.min(MAX_BUFFER, original.capacity() * 2));
    original.flip();
    larger.put(original);
    return larger;
  }

  private SSLEngineResult wrap(ByteBuffer source) throws IOException {
    ByteBuffer packet = ByteBuffer.allocate(Math.max(32768, engine.getSession().getPacketBufferSize()));
    SSLEngineResult result = engine.wrap(source, packet);
    if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
      packet = grow(packet);
      result = engine.wrap(source, packet);
    }
    if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
      throw new SSLException("TLS output exceeds its limit");
    if (packet.position() > 0) {
      wireOut.write(packet.array(), 0, packet.position());
      wireOut.flush();
    }
    return result;
  }

  private void requireApplicationState(SSLEngineResult result) throws SSLException {
    SSLEngineResult.HandshakeStatus status = result.getHandshakeStatus();
    if (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        && status != SSLEngineResult.HandshakeStatus.FINISHED
        && result.getStatus() != SSLEngineResult.Status.CLOSED)
      throw new SSLException("TLS renegotiation is not supported on relay calls");
  }

  public InputStream input() {
    return new InputStream() {
      @Override public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
      }

      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > buffer.length - length)
          throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        synchronized (readLock) {
          while (!plaintext.hasRemaining()) {
            if (closed) return -1;
            plaintext.clear();
            SSLEngineResult result;
            while (true) {
              result = unwrap(plaintext);
              requireApplicationState(result);
              if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) readEncrypted();
              else if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
                plaintext = grow(plaintext);
              else break;
            }
            plaintext.flip();
            if (result.getStatus() == SSLEngineResult.Status.CLOSED && !plaintext.hasRemaining())
              return -1;
          }
          int count = Math.min(length, plaintext.remaining());
          plaintext.get(buffer, offset, count);
          return count;
        }
      }

      @Override public void close() throws IOException { TlsTunnel.this.close(); }
    };
  }

  public OutputStream output() {
    return new OutputStream() {
      @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }

      @Override public void write(byte[] buffer, int offset, int length) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(buffer, offset, length);
        synchronized (writeLock) {
          if (closed) throw new IOException("TLS tunnel is closed");
          while (source.hasRemaining()) {
            SSLEngineResult result = wrap(source);
            requireApplicationState(result);
            if (result.getStatus() == SSLEngineResult.Status.CLOSED)
              throw new IOException("TLS peer closed");
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0)
              throw new SSLException("TLS output made no progress");
          }
        }
      }

      @Override public void flush() throws IOException { wireOut.flush(); }
      @Override public void close() throws IOException { TlsTunnel.this.close(); }
    };
  }

  @Override public void close() throws IOException {
    if (closed) return;
    closed = true;
    // Closing the underlying socket interrupts both blocked directions. No lock acquisition here.
    connection.close();
  }

  public static String fingerprint(byte[] certificate) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
    StringBuilder result = new StringBuilder();
    for (byte value : digest) result.append(String.format(Locale.US, "%02X", value));
    return result.toString();
  }
}
