# Hub protocol, version 1

## Outer authenticated connection

The default listener is TCP 52744. Connections use TLS 1.2 with mutual
certificates. Device identities are uppercase SHA-256 certificate fingerprints
(64 hex characters), not device names, IP addresses, or supplied JSON identity
fields. Android pins the hub certificate before sending enrollment secrets.

Control messages are a 4-byte unsigned big-endian byte count, then UTF-8 JSON.
The maximum control payload is 65536 bytes. Unknown versions and invalid first
messages are rejected. An error is `{"type":"error","code":"...","message":"..."}`.

## Enrollment and presence

The manager creates a short-lived, single-use, cryptographically random token.
It encodes an offline QR/manual link:

`relay://hub?v=1&host=192.168.1.10&port=52744&fp=FULL_SHA256&token=64_HEX_TOKEN`

The phone reviews and approves the hub before storing the configuration. The
initial control frame is:

```json
{"type":"register","version":1,"name":"Phone","appVersion":"2.0","addresses":["192.168.2.20"],"port":52743,"token":"64_HEX_TOKEN"}
```

An unknown certificate requires a valid token and becomes pending. The token is
consumed and bound to that certificate; retrying a pending enrollment must not
allow a different certificate to reuse it. Approval on the manager is required
before receiving the directory or opening tunnels. Existing approved devices
reconnect by their authenticated certificate, without a new token.

Pending controls receive `{"type":"pending"}`. Approved controls receive
`{"type":"registered","hubId":"HUB_FINGERPRINT"}`, followed by directory
updates:

```json
{"type":"directory","devices":[{"fingerprint":"PEER_FINGERPRINT","name":"Phone","appVersion":"2.0","addresses":["192.168.2.20"],"port":52743,"online":true,"lastSeen":1793912400000}]}
```

`lastSeen` is UTC Unix time in milliseconds. Reported names, addresses, versions, and availability are metadata; the actual
TLS certificate supplies identity. The directory never creates a trusted peer
contact automatically. Ping/pong control messages keep presence current.

## Opaque peer tunnels

Each tunnel uses two separate outer TLS connections, in addition to the devices'
long-lived control connections:

1. Caller sends `{"type":"open","version":1,"target":"PEER_FINGERPRINT","purpose":"pair"}`
   or purpose `call` on a new authenticated connection.
2. Hub checks both devices' enrollment and online state, allocates a bounded
   pending tunnel with a 15-second deadline, and sends caller
   `{"type":"waiting","id":"32_HEX_ID"}`.
3. Target's control receives
   `{"type":"offer","id":"32_HEX_ID","from":"CALLER_FINGERPRINT","name":"Caller","purpose":"pair"}`.
4. Target sends `{"type":"attach","version":1,"id":"32_HEX_ID"}` on a new
   outer TLS connection. Only the expected target certificate may attach.
5. Hub sends both connections
   `{"type":"ready","id":"32_HEX_ID","peer":"OTHER_FINGERPRINT","role":"client"}`
   to caller and role `server` to target.
6. Immediately after each complete ready frame, the payload becomes an opaque
   byte stream. The two Android endpoints negotiate a new mutual TLS session
   inside that stream. The hub forwards bytes without terminating inner TLS.
7. Inner certificates must match the expected offer/directory fingerprints.
   The original peer pairing/call protocol runs inside inner TLS unchanged.

Do not mix framed control messages into a connection after ready. Each direction
has bounded buffering and cancellation. Closing either side closes the tunnel.
Revoking membership immediately closes its control and all pending/active
tunnels. Hub revocation affects hub access; existing independent direct peer
trust is managed on the phones.

The hub can observe connection metadata and traffic lengths/timing, but cannot
read inner call messages or voice. Reconnection does not silently resume an
interrupted call or authorize microphone capture.
