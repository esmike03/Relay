tunnel-test.p12 contains a public test-only RSA identity generated with the JDK keytool.
Password: relay-test-only. It is never referenced by production code or packaged in the APK.
The tests use it to exercise actual mutual TLS handshakes, certificate pinning, fragmentation,
and bidirectional large messages through the same TlsTunnel class used on Android.
