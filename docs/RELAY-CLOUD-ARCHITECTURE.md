# Relay Cloud — Phase 1 audit, architecture and hosting requirements

Status: **design only, 6 November 2026.** No cloud code exists yet. No hosting
provider, domain, account or budget has been selected. This document answers
Phase 1 of `RELAY-CLOUD-PLAN.md` from the actual Relay 2.1 source, and is the
contract later phases implement. Items marked *verify* must be confirmed against
current official documentation or real hardware before they are relied on.

## 1. Source audit

### Facts confirmed in the source

| Area | What the source does | Where |
| --- | --- | --- |
| Hub engine | One `HubEngine` class owns the TCP listeners, TLS handshake, protocol handlers, device registry, enrollment tokens, tunnels, dispatch and JSON-file persistence. `Connection` wraps a `TcpClient` + `SslStream`. | `desktop/Relay.Hub.Core/HubEngine.cs` |
| Hub identity | RSA-2048 self-signed certificate; private key is a non-exportable CNG key (`CngExportPolicies.None`). Windows-only. | `HubIdentity.cs` |
| Targets | All seven .NET projects target `net10.0-windows`. | `*.csproj` |
| Admin | Headless host administration uses a `CurrentUserOnly` named pipe. | `Relay.Hub.Host/Program.cs` |
| Wire format | 4-byte big-endian length + UTF-8 JSON object, 2 B–64 KiB, JSON depth ≤ 16. After `ready` the socket becomes an opaque byte stream. | `HubWire.cs`, `HubClient.java` |
| Listener policy | Listeners and allowed sources must be private IPv4; per-source attempt buckets (120/min, 1024 buckets); 16 concurrent handshakes. | `HubOptions.cs`, `HubEngine.AcceptAsync` |
| Device identity | Android RSA-2048 key in AndroidKeyStore, `PURPOSE_SIGN`, PKCS#1 signature padding, digests NONE/SHA-256/384/512. Fingerprint = SHA-256 of the certificate DER. | `Identity.java` |
| Peer TLS | TLS 1.2, ECDHE-RSA AES-GCM, custom pinning trust manager. `TlsTunnel` runs an `SSLEngine` over **any** `InputStream`/`OutputStream`. | `Identity.java`, `TlsTunnel.java` |
| Hub enrollment QR | `relay://hub?v=1&host&port&fp&token`; host must be a private IPv4 literal. | `HubQr.java` |
| Hub config on phone | A single `hub` JSON object in `Store`. Emergency tombstones are keyed by hub fingerprint. | `Store.java` |
| Dispatch | One active dispatch session per hub. The hub certificate is the speaker identity (`from`, `hubId`). Bounded 64-message outbound channel per recipient; audio token bucket 150/70 per second. | `HubDispatch.cs` |
| Directory | Distributes each device's self-reported private addresses and port. | `HubEngine.SendDirectoryAsync` |
| Android dependencies | ZXing core only. No WebSocket client library. | `app/build.gradle` |

### Findings that change the plan's details

1. **Overlapping private networks already cause hard failures.**
   `RelayService.connectPeer` dials the contact's stored host, then
   `directEndpoints` from the directory, before any relay. Any `SSLException`
   (including reaching a *different* device or non-Relay TLS service at the same
   private address on another network) is treated as an identity failure and
   aborts without trying the hub. With phones moving between unrelated networks
   this becomes routine. The cloud routing rules in §5 resolve this without
   weakening the identity-failure rule.
2. **Device cloud authentication should not rely on TLS 1.3 client
   certificates.** TLS 1.3 requires RSA-PSS for `CertificateVerify`. The Android
   key is restricted to PKCS#1 signature padding; the source comment relies on
   Conscrypt producing PSS through a raw operation. That works for today's TLS
   1.2 sessions but is unproven for client authentication at a public TLS 1.3
   proxy across Android 8–15. An application-level challenge signed with
   **RS256 (PKCS#1 v1.5 + SHA-256)** is supported by this exact key, works behind
   any proxy and needs no trusted identity headers. See §4.2.
3. **The hub fingerprint doubles as the dispatcher identity and the emergency
   namespace.** Cloud dispatch has human dispatchers and a service identity, so
   phones need a distinct cloud namespace for alerts and offers.
4. **Directories leak private addresses.** Fine on one LAN, wrong for the
   internet. The cloud directory carries no addresses.
5. **`HubEngine` cannot simply be hosted on Linux.** Besides CNG, it binds raw
   TCP, persists one JSON file under a process lock, and assumes one hub = one
   dispatcher. Reuse is therefore *extract shared protocol and policy code*, not
   *run HubEngine in the cloud* (§3).

### Baseline status on this PC

| Check | Status |
| --- | --- |
| Android `testDebugUnitTest` | **22 passed, 0 failures** (run today, includes this session's UI theme change) |
| 40 hub checks, 7 desktop lifecycle checks, Java/.NET interop | **Not run** — .NET 10 SDK is not installed (SDKs 6/8/9 only) |
| Linux / container builds | **Not possible yet** — no Docker and no WSL distribution installed |
| Available | Node 24, npm 11, JDK 17, PostgreSQL 18 client |

## 2. Architecture

```
                         public internet, HTTPS/WSS :443 only
 Android phones ──┐
 (Wi-Fi/cellular) │   ┌───────────────────────── VPS / container host ───────────────────────┐
                  ├──►│ Caddy (auto TLS) ──► Relay.Cloud (ASP.NET Core, .NET 10, 1 instance)  │
 Windows desktop ─┤   │                        ├─ /            static dashboard (TypeScript)  │
 (remote mode)    │   │                        ├─ /api/*       cookie or bearer auth           │
 Browser          ┘   │                        ├─ /v1/device   device WSS (PoP auth)          │
 dispatcher           │                        ├─ /v1/dispatch dispatcher WSS (user auth)     │
                      │                        └─ /healthz /readyz                            │
                      │                     PostgreSQL 17/18 (private network only)            │
                      │                     volumes: db, service key, data-protection keys     │
                      └───────────────────────────────────────────────────────────────────────┘
 Local mode is unchanged: phone ⇄ phone TCP 52743, phone ⇄ Windows hub TCP 52744.
```

- **One application instance** plus PostgreSQL. Live sessions are in memory
  and owned by that instance. No autoscaling until cross-instance ownership and
  media routing exist and are tested.
- **Cloud mode is optional and additive.** Phones keep local identities,
  contacts, agreements, history and recordings. Nothing is uploaded on enabling
  cloud mode; enrollment is an explicit scan and approval.
- **Workspaces** isolate devices, users, alerts and dispatch. Membership does
  not pair phones; phone-to-phone trust stays the existing mutual pairing.

## 3. Components and reuse

| Project | Target | Content |
| --- | --- | --- |
| `desktop/Relay.Protocol` *(new)* | `net10.0` | Moved from Core without behavior change: `HubWire`, message/capability constants, fingerprint + certificate checks, `DispatchRate`, SOS idempotency/cooldown policy, floor arbitration rules. |
| `desktop/Relay.Hub.Core` | `net10.0-windows` | Unchanged behavior; references `Relay.Protocol`. Keeps CNG identity, TCP listeners, JSON state. |
| `cloud/Relay.Cloud` *(new)* | `net10.0`, Linux | ASP.NET Core host: Identity/auth, device PoP, workspace session manager (control, tunnel broker, dispatch), emergencies, EF Core + Npgsql, health checks. |
| `cloud/Relay.Cloud.Tests` *(new)* | `net10.0` | Protocol, auth, isolation, bounds and PostgreSQL integration tests. |
| `cloud/web` *(new)* | TypeScript, Vite | Light-mode dashboard; AudioWorklet capture/playback. Builds to static files served by Relay.Cloud or by a separate static host. |
| `cloud/deploy` *(new)* | — | Dockerfile, `compose.yaml`, `Caddyfile`, nginx alternative, `.env.example`, backup/restore scripts. |
| Android | Java, API 26+ | `WsByteStream`, `CloudClient`, cloud QR v2 in `HubQr`, additive `cloud` store key, route selection, Settings → Relay Cloud. Adds **OkHttp** for WebSocket. |
| `Relay.Desktop` | WPF | `IDispatchBackend` with `LocalHubBackend` (current `HubEngine`) and `CloudBackend` (HTTPS + WSS client); sign-in screen. |

The 40 hub and 7 desktop checks must pass unchanged after the `Relay.Protocol`
extraction before any cloud code depends on it.

## 4. Trust model

### 4.1 Layers

| Layer | Purpose | Rotates |
| --- | --- | --- |
| Web PKI certificate (Let's Encrypt via Caddy) | Authenticates the hostname for HTTPS/WSS; system trust store + hostname validation on every client | Automatically, ~every 60–90 days, no device impact |
| **Service identity key** (ECDSA P-256) | Stable application identity of a Relay Cloud deployment. Pinned at enrollment; signs every device-session challenge. Defends against a mis-issued web certificate or hijacked DNS. | Only by explicit rotation (§4.4) |
| Device key (existing Android RSA-2048) | Device identity = SHA-256(cert DER), unchanged from local mode | Never (reinstall = new identity, as today) |
| User accounts | Browser and Windows dispatchers; Admin / Dispatcher / Viewer | Sessions expire; revocable |
| Inner peer TLS | Phone-to-phone calls, pinned mutual TLS, unchanged | — |

### 4.2 Device session authentication (every device WSS connection)

Standards used: WebSocket (RFC 6455) over public TLS, JWS compact
serialization (RFC 7515) with RS256. Signing uses platform
`java.security.Signature`; the server validates with a maintained JOSE library
(`Microsoft.IdentityModel.JsonWebTokens`, *verify current version*). No custom
cryptography.

1. Device connects to `wss://HOST/v1/device` with subprotocol
   `relay.device.v1`, validating the web certificate normally.
2. Device sends `hello {cnonce: 32 random bytes, workspace}`.
3. Server replies `challenge {snonce: 32 random bytes, cid: connection id,
   origin, workspace, sig}` where `sig` = service key ECDSA-SHA256 over
   `cnonce‖snonce‖cid‖origin‖workspace`. Device verifies `sig` against the
   pinned service key fingerprint. Mismatch = identity failure, no fallback.
4. Device sends `proof`: a JWS with header `{alg: RS256, typ:
   "relay-device-proof+jwt", x5c: [device cert]}` and payload `{aud: origin +
   "/v1/device", snonce, cnonce, cid, workspace, purpose}`. `purpose` is
   `control`, `open`, `attach` or `dispatch_attach`.
5. Server accepts only if: `alg` is exactly RS256; one certificate, RSA ≥ 2048,
   currently valid (existing `ValidPeer` rule); signature verifies with that
   certificate's key; `snonce` was issued on **this** connection, unused, < 30 s
   old; `cid`, `aud` and `workspace` match exactly. The device identity is the
   certificate fingerprint; no JSON-supplied identity is trusted.

Binding and replay: the nonce exists only on one connection and is consumed on
first use, so a captured proof cannot authenticate any other connection. Clock
skew is irrelevant because freshness comes from the server nonce. After `proof`
succeeds, the existing hub-v1 messages run on that connection unchanged.

*Alternative considered:* mutual TLS at the reverse proxy with forwarded
client-certificate headers. Rejected for v1 because of the TLS 1.3 / PKCS#1 key
risk (§1, finding 2) and because it requires trusting proxy headers. It remains
an option after real-device testing.

### 4.3 Users

- ASP.NET Core Identity: password hashing, lockout, TOTP MFA, security stamps.
  No public self-registration. Admins invite users.
- Browser: `__Host-` cookie, HttpOnly, Secure, SameSite=Strict; antiforgery on
  every state-changing request; WebSocket upgrade checks the exact allowed
  `Origin` **and** the session (origin is not authentication).
- Windows client: short-lived bearer access token + refresh token from the
  Identity bearer-token handler; refresh token stored with DPAPI (CurrentUser).
  *Upgrade path:* OpenIddict authorization code + PKCE if SSO or third-party
  clients are needed.
- First administrator: `relay-cloud bootstrap-admin` (run inside the container)
  prints a one-time, 30-minute, hashed-at-rest setup link. No default passwords.
- Permissions are enforced per HTTP endpoint and per WebSocket message:

| Action | Admin | Dispatcher | Viewer |
| --- | --- | --- | --- |
| View devices, presence, alerts | ✓ | ✓ | ✓ |
| Create invitations, approve/revoke devices, aliases, manage users | ✓ | | |
| Call one device, Talk to all, emergency voice/text | ✓ | ✓ | |
| Resolve emergency alerts | ✓ | ✓ | |

### 4.4 Service key storage, rotation and recovery

- Generated on first start into a dedicated volume (`/var/lib/relay/keys`,
  mode 0600, owned by the app user), or loaded from a mounted secret. Never in
  the image, the database or the repository. Included in encrypted backups.
- **Rotation:** the operator runs `relay-cloud rotate-service-key`. The server
  creates a new key and a rotation statement `{old fp, new fp, issued}` signed
  by the old key. Connected and reconnecting devices receive it after
  authentication, verify it with the pinned old key, and pin both keys during a
  grace period, then only the new one.
- **Loss or compromise:** devices cannot be moved automatically. The operator
  creates a new deployment identity and new invitations; devices re-enroll. This
  is documented as an operational incident, not hidden.
- The existing Windows hub key is not, and cannot be, migrated to the cloud.

### 4.5 What the cloud can and cannot see

- **Phone-to-phone calls:** end-to-end. The cloud forwards inner TLS ciphertext
  between two authenticated device connections and cannot read signaling or
  voice. It sees metadata: who connected to whom, when, byte counts and timing.
- **Dispatch (browser/Windows ⇄ phones):** **not end-to-end.** The cloud is the
  dispatch service; it decrypts and forwards dispatch audio between separately
  encrypted connections. It never stores voice. The UI and docs say
  "encrypted in transit", never "end-to-end encrypted", for dispatch.
- Emergency text and metadata are stored in PostgreSQL.

## 5. Protocol

### 5.1 Endpoints

| Path | Who | Auth | Format |
| --- | --- | --- | --- |
| `GET /v1/device` → WSS, subprotocol `relay.device.v1` | Phones | §4.2 per connection | Binary WS messages forming one ordered byte stream |
| `GET /v1/dispatch` → WSS, subprotocol `relay.dispatch.v1` | Browser / Windows | Cookie + Origin, or bearer | Text WS messages, one JSON object each |
| `/api/*` | Browser / Windows | Cookie + antiforgery, or bearer | JSON |
| `/healthz`, `/readyz` | Monitoring | None; no data | 200/503 |

### 5.2 Device stream adapter

Binary WebSocket message boundaries carry **no meaning**. The concatenated
payloads form exactly the byte stream that TCP+TLS carries in local mode. After
authentication the existing 4-byte-length JSON frames run unchanged; after
`ready` the stream becomes opaque inner-TLS bytes, forwarded unchanged by the
server. This lets `HubWire`, `HubClient`, `DispatchConnection` and `TlsTunnel`
run unmodified over `WsByteStream`.

- Text messages, or binary messages > 64 KiB, close the connection (1003/1009).
- Each direction has a bounded buffer (256 KiB). The server pumps one message
  at a time and awaits the send before reading again, so backpressure reaches
  the slow side without blocking other sessions.
- Per device: at most 1 control, 1 tunnel leg and 1 dispatch connection. Per
  workspace: device, tunnel and dispatch caps as in `HubOptions`, plus a global
  connection cap and per-IP handshake rate limit.
- Keepalive: the existing 5 s app-level ping on control connections, 20 s read
  timeout. WebSocket-level ping every 15 s on tunnel/dispatch connections
  (*verify* ASP.NET Core `WebSocketOptions.KeepAliveTimeout` in .NET 10). Proxy
  idle timeouts must exceed 60 s.
- Close: either leg closing closes the tunnel; revocation closes all of a
  device's connections immediately (as today).

### 5.3 Messages added or changed for cloud

All hub-v1 and dispatch/emergency-v1 messages are reused. Changes:

- `registered.hubId` → the cloud namespace `cloud:<service-key-fp>:<workspace>`
  so cloud alerts and tombstones never collide with a local hub's.
- `register.addresses` is ignored in cloud mode; the cloud directory has no
  `addresses` or `port`.
- New capability `emergency-v2`: device sends `emergency_receipt {id}` when an
  alert is stored/shown, and `emergency_ack {id}` when a person taps
  Acknowledge. The dashboard shows **Accepted by server → Received by device →
  Acknowledged by person** separately. v1 devices show only the first.
- Cloud invitation: `relay://cloud?v=2&origin=https://HOST&ws=WORKSPACE&sk=SERVICE_KEY_FP&token=64HEX`.
  New authority, so 2.1 phones reject it cleanly; `relay://hub?v=1` is
  unchanged. Tokens: 32 random bytes, stored as SHA-256 hash, 10-minute expiry,
  consumed in a database transaction, bound to the first proving device.

### 5.4 Dispatcher channel (`relay.dispatch.v1`)

Client → server: `start {mode, targets[], message}`, `talk {on}`,
`audio {data}`, `end`, `resolve {alertId}`. Server → client: `state`
(participants: invited/attaching/connected/skipped/declined/ended, with
reason), `floor {owner}`, `audio {data, from}`, `alert`, `alert_update`, `error`.
Server-to-phone traffic is the existing dispatch-v1 protocol, with
`from`/`peer` = the cloud namespace. Audio is the existing format: 640 bytes =
20 ms, 16 kHz, PCM16 LE mono, base64.

Rules carried over: one active session per workspace; emergency preempts normal
sessions and normal phone calls; broadcasts skip busy phones and never grant a
phone the floor; a call requires Answer; capture starts only after the floor is
granted to a held control and stops on release, cancel, blur, hidden tab,
disconnect or a late permission grant after release.

## 6. Android routing and migration

- **Local mode only (cloud not enrolled):** behavior is unchanged, byte for byte.
- **Cloud enrolled, contact also in the cloud directory:**
  1. Direct only for a *fresh local candidate*: an mDNS result on the current
     network, seen in the last few minutes, whose advertised fingerprint matches
     the contact. A TLS identity mismatch on that candidate is a genuine
     identity failure and is reported.
  2. Otherwise, the enrolled local Windows hub if the contact is online there.
  3. Otherwise the cloud tunnel. Inner TLS pinning still applies; a mismatch
     there is a hard failure.
  Stored private addresses and directory addresses are **not** dialed
  speculatively while a cloud route exists, because unrelated networks reuse the
  same ranges.
- Storage: a new `cloud` object beside `hub` (origin, workspace, service key
  pins, token state). Contacts keep their direct addresses; cloud routes are
  never written into them. All changes are additive; downgrading ignores the new
  keys.
- Cloud mode may use cellular. The local private-IPv4 checks in `Lan` and
  `HubQr` stay in force for local transports and are not loosened.
- Foreground service, notification, microphone, battery and force-stop rules
  are unchanged. Reconnecting never resumes a call or the microphone.

## 7. Durable data (PostgreSQL)

`workspaces`, `users`/`roles` (Identity tables), `memberships`, `devices`
(fingerprint, certificate, name, alias, capabilities, approved, revoked,
last_seen), `enrollment_invitations` (token hash, expiry, consumed_by,
consumed_at), `emergency_alerts`, `emergency_deliveries` (per device: accepted,
received, acknowledged), `sos_limits` (per device last id + time; kept even
after alert history is pruned), `audit_events` (metadata only),
`service_keys` (public part and rotation statements only). Data Protection
keys persist to a volume so sessions survive restarts. EF Core migrations,
additive only, applied explicitly by an operator command.

Live, in-memory and bounded: connections, nonces, pending tunnels, dispatch
sessions, floors, audio queues. Raw audio is never written to disk.

## 8. Hosting requirements

### 8.1 What the host must provide

| Requirement | Why |
| --- | --- |
| Always-on process; no sleep or scale-to-zero | Phones keep a control connection open |
| Inbound 443 with WebSocket upgrade; idle timeout > 60 s; no short absolute connection limit (or one documented and handled) | Persistent device and dispatcher sessions |
| Linux x64 (or arm64), Docker Engine + Compose v2, or ability to run a systemd service with the .NET 10 runtime | Runs Relay.Cloud |
| PostgreSQL 16+ (bundled container or managed), not publicly reachable | Durable state |
| Persistent volumes for database, service key and Data Protection keys; snapshots or off-host backup target | Durability, recovery |
| A domain or subdomain with DNS control | Public TLS certificate |
| Outbound traffic allowance appropriate to voice (below) | Dispatch and tunnels are relayed |

Starting size, **not measured**: 2 vCPU, 2 GB RAM, 20 GB disk. Phase 5 load
tests replace this with measured figures.

Bandwidth (calculated, not measured): one receiving device ≈ 0.34 Mbit/s of
base64 PCM before JSON/TLS overhead; 50 simultaneous receivers ≈ 17 Mbit/s. A
relayed phone-to-phone call carries both directions through the server.

### 8.2 Hosting matrix

| Hosting | Fit | Conditions |
| --- | --- | --- |
| Linux VPS or VM with Docker Compose | **Recommended default** | Delivered as `cloud/deploy/compose.yaml` + Caddy |
| Managed container platform | Possible | Must confirm: WebSockets, minimum one always-on instance, connection duration limits, persistent volumes or managed PostgreSQL, egress pricing |
| PHP/MySQL shared hosting (cPanel/Plesk) | **Dashboard files only** | Serves `cloud/web` build output; the API/WSS service runs on a VPS. Cannot run the live relay. |
| Application-capable shared hosting | Unlikely | Only with written confirmation of .NET 10, persistent WebSockets, concurrency, long-lived processes and storage. A panel or "Node.js" label is not evidence. |

Separate static frontend: requires exact CORS origin, credentialed requests,
`SameSite=None; Secure` cookies (or bearer tokens) and the dashboard origin in
the WebSocket origin allowlist. Covered by a dedicated guide in Phase 6.

### 8.3 Provider checklist (fill in before choosing)

- Provider, package name, region, OS image, CPU/RAM/disk.
- Docker/Compose allowed? Root or sudo? Can ports 80 and 443 be bound?
- WebSocket support through any provider proxy or load balancer; its idle and
  maximum connection durations.
- Process recycling, maintenance reboots, sleep policy.
- Volume persistence and snapshot/backup options; restore procedure.
- Included transfer and price per extra GB (from the provider, not estimated).
- Firewall controls; DDoS or rate limiting at the edge.
- Managed PostgreSQL availability and version, if not self-hosting it.

## 9. Phased file plan

| Phase | Deliverables | Ownership if parallelized |
| --- | --- | --- |
| 2 Foundation | `Relay.Protocol` extraction (baseline rerun); `cloud/Relay.Cloud` host, EF Core schema + migration, Identity + roles + MFA + bootstrap, device PoP auth, service key store, health checks; `docs/CLOUD-PROTOCOL.md` | Backend |
| 3 Phone calls | `WsByteStream`, `CloudClient`, QR v2, Store `cloud` key, routing rules, Settings UI; cloud tunnel broker; tests for fragmentation, overlap, isolation | Android + backend |
| 4 Remote dispatch | Dispatch session manager; `cloud/web` dashboard + AudioWorklet PTT; `IDispatchBackend` + `CloudBackend` in WPF | Web/desktop + backend |
| 5 SOS + reliability | `emergency-v2` receipts/acks, snapshots, cooldowns, revocation, audit, slow-client and flood tests, load/egress measurement | Backend + Android |
| 6 Deployment | Dockerfile, Compose, Caddyfile (+ nginx alternative), `.env.example`, backup/restore, upgrade/rollback, key rotation, static-frontend guide, validation report | Integration |

The protocol in §4–5 is the shared contract; any change to it is made here
first.

## 10. Validation that needs hardware, providers or tools not yet available

Not executed and not claimed: Linux/container builds; .NET 10 baseline (40 + 7
checks, interop); real calls across separate networks and cellular; TLS
renewal; browser microphone races on real browsers; provider WebSocket limits;
measured latency, jitter and load. Each will be reported as run or not run in
the Phase 6 validation report.

## 11. Decisions needed

1. Hosting: a specific provider/domain, or proceed with the generic VPS +
   Docker Compose profile (recommended until one is chosen).
2. Local tooling for validation: .NET 10 SDK and Docker Desktop (WSL 2 backend)
   on this PC.
3. Android dependency: OkHttp for the WebSocket client (recommended over a
   hand-written WebSocket implementation).
