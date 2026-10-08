package app.relay.lan;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

final class Identity {
  final SSLContext context;
  final String fingerprint;

  Identity() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    String alias = "relay-identity-v3";
    if (!ks.containsAlias(alias)) {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
      gen.initialize(
          new KeyGenParameterSpec.Builder(
                  alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setKeySize(2048)
              .setDigests(
                  KeyProperties.DIGEST_NONE,
                  KeyProperties.DIGEST_SHA256,
                  KeyProperties.DIGEST_SHA384,
                  KeyProperties.DIGEST_SHA512)
              .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
              // Conscrypt may prepare RSA-PSS padding itself, then sign the
              // already padded digest with a raw private-key operation.
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setCertificateSubject(new X500Principal("CN=Relay local device"))
              .setCertificateNotAfter(new GregorianCalendar(2046, 0, 1).getTime())
              .build());
      gen.generateKeyPair();
    }
    X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
    fingerprint = fingerprint(cert);
    PrivateKey key = (PrivateKey) ks.getKey(alias, null);
    X509ExtendedKeyManager km =
        new X509ExtendedKeyManager() {
          public String[] getClientAliases(String t, Principal[] p) {
            return "RSA".equals(t) ? new String[] {alias} : null;
          }

          public String chooseClientAlias(String[] t, Principal[] p, java.net.Socket s) {
            for (String type : t) if ("RSA".equals(type)) return alias;
            return null;
          }

          public String[] getServerAliases(String t, Principal[] p) {
            return "RSA".equals(t) ? new String[] {alias} : null;
          }

          public String chooseServerAlias(String t, Principal[] p, java.net.Socket s) {
            return "RSA".equals(t) ? alias : null;
          }

          public X509Certificate[] getCertificateChain(String a) {
            return new X509Certificate[] {cert};
          }

          public PrivateKey getPrivateKey(String a) {
            return key;
          }
        };
    // Self-signed certificates are admitted to TLS only. The application then pins
    // their fingerprints before sending a call, accepting audio, or storing a contact.
    TrustManager tm =
        new X509TrustManager() {
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }

          public void checkClientTrusted(X509Certificate[] c, String a)
              throws java.security.cert.CertificateException {
            check(c);
          }

          public void checkServerTrusted(X509Certificate[] c, String a)
              throws java.security.cert.CertificateException {
            check(c);
          }

          private void check(X509Certificate[] c) throws java.security.cert.CertificateException {
            if (c.length == 0) throw new java.security.cert.CertificateException();
            c[0].checkValidity();
          }
        };
    context = SSLContext.getInstance("TLSv1.2");
    context.init(new KeyManager[] {km}, new TrustManager[] {tm}, new SecureRandom());
  }

  static String fingerprint(java.security.cert.Certificate cert) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
    StringBuilder b = new StringBuilder();
    for (byte v : bytes) b.append(String.format(Locale.US, "%02X", v));
    return b.toString();
  }

  static String clean(String s) {
    return s.replaceAll("[\\s:-]", "").toUpperCase(Locale.US);
  }
}
