# Relay Cloud — implementation and hosting plan

Prepared for the existing Relay 2.1 Android/Windows project. This is a plan, not
an implemented cloud service. No hosting provider, account, domain or budget has
been selected.

## Recommended deployment

Run one always-on cloud hub behind HTTPS/WSS on port 443. Android phones,
Windows dispatchers and an optional browser dispatcher connect outward to it.
Phones on separate Wi-Fi networks or cellular connections can use the same hub
without forwarding ports on their routers. The Windows desktop need not remain
running for phone-to-phone cloud calls.

Use ASP.NET Core on .NET 10, a light-mode TypeScript web interface, PostgreSQL,
and a reverse proxy with automatic public TLS certificate renewal. Deploy the
first version as one application instance plus its database. Reuse Relay's
tested identity, pairing, floor, dispatch and emergency behavior through
platform-independent interfaces.

HTTPS/WSS is the public transport; the current raw LAN TCP listener is not the
public API. Configure WebSocket upgrades, bounded message sizes, heartbeat and
proxy timeouts explicitly. Nginx requires explicit upgrade forwarding, and idle
connections need appropriate timeout/keepalive handling.
([Nginx documentation](https://nginx.org/en/docs/http/websocket.html),
[ASP.NET Core documentation](https://learn.microsoft.com/en-us/aspnet/core/fundamentals/websockets?view=aspnetcore-10.0))

## Hosting choices

| Hosting | Intended use | Conditions |
| --- | --- | --- |
| Linux VPS or dedicated VM | Full hub, dashboard and database | Recommended default; persistent service, public TLS, backups and monitoring |
| Managed container/application hosting | Full hub and dashboard | Verify WebSockets, no unsuitable sleep policy, connection-duration limits, persistent state and egress limits |
| Traditional PHP/MySQL shared hosting | Dashboard/static frontend; relay hosted separately | Do not assume request-based PHP hosting can run the live relay |
| Application-capable shared hosting | Potentially full backend | Provider must explicitly support the selected runtime, persistent WebSockets, concurrency and process lifecycle requirements |

cPanel's application features depend on software installed and enabled by the
provider; a cPanel logo alone does not establish runtime or WebSocket support.
Plesk also leaves relevant runtime/proxy configuration to the hosting setup.
([cPanel documentation](https://docs.cpanel.net/cpanel/software/application-manager/),
[Plesk documentation](https://support.plesk.com/hc/en-us/articles/12377246437399-Does-Node-js-on-Plesk-support-WebSockets-socket-io))

For an existing shared-hosting account, the simplest fallback is to host the
compiled web interface there and connect it to the cloud API/WSS service.
Do not create a second independent PHP authentication/database system merely
to serve the frontend. Configure allowed web origins and credentials correctly;
WebSocket origin validation is separate from CORS and is not authentication.
([ASP.NET Core documentation](https://learn.microsoft.com/en-us/aspnet/core/fundamentals/websockets?view=aspnetcore-10.0))

Before selecting a plan, verify:

- Provider and exact hosting package, OS, runtime versions and deployment access.
- Long-lived WSS connections on 443, idle and absolute connection timeouts,
  connection limits, proxy upgrades and process recycling/sleep behavior.
- Persistent storage, database availability, backup/restore and secrets handling.
- Memory/CPU limits, allowed concurrent connections, transfer allowances and
  outbound bandwidth pricing. Do not estimate provider prices without evidence.
- Ability to configure the necessary reverse proxy or obtain provider support.

## Existing project changes required

| Current implementation | Cloud requirement |
| --- | --- |
| `HubQr.java` accepts private IPv4 local hub addresses | Add a versioned cloud invitation with HTTPS origin, workspace ID and expiring enrollment token; preserve local QR version 1 |
| `HubClient.java` calls `Lan.privateAddress` and requires local interfaces | Add a distinct cloud transport supporting DNS, public PKI and cellular; retain local restrictions for local mode |
| `Relay.Hub.Core` / `Relay.Hub.Host` target `net10.0-windows` | Extract reusable core interfaces/logic into a cross-platform target; keep Windows-specific adapters separate |
| `HubIdentity.cs` uses nonexportable Windows CNG keys | Use a durable Linux/cloud identity provider backed by protected secrets or managed key storage |
| WPF directly owns a local `HubEngine` | Introduce a remote hub/dispatch client abstraction while retaining the local hub option |
| JSON files and in-memory live connections | Transactional persistent cloud state plus explicit live connection ownership |
| Named-pipe administration restricted to one Windows account | Authenticated HTTPS administration and scoped WebSocket sessions |
| LAN certificate fingerprint pin tied to the local endpoint | Public TLS hostname validation, device proof of possession and a separate application identity/key-rotation design |

CNG APIs do not operate on non-Windows platforms. Uploading the current Windows
host binary or copying its public certificate is not a Linux deployment or a
transfer of its nonexportable private key.
([Microsoft documentation](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.cngkey?view=net-10.0))

## Functional scope

Preserve local direct calls and the local desktop hub. Add optional Cloud mode
without changing existing phone identities, trusted contacts, recordings or
automatic-answer agreements. Cloud enrollment must be explicit.

Cloud features:

- Workspace-scoped device enrollment, approval, revocation, aliases and presence.
- Existing one-to-one phone calls across unrelated networks.
- Windows and browser dispatch: call one device, Talk to all, emergency voice.
- Phone SOS, active alert history, delivery state, acknowledgment and resolution.
- Existing emergency priority rules, receive-only announcements and consent rules.
- A light-mode web dashboard with Admin, Dispatcher and Viewer roles.

Preserve peer trust separately from cloud membership. Being in one workspace
does not automatically pair every phone. Server-side permissions must constrain
both ordinary calls and dispatch/broadcast recipients to the correct workspace.

## Transport and encryption

Use separate authenticated control and call/dispatch connections. Define a
versioned WSS subprotocol and a bounded byte-stream adapter that handles binary
messages, fragmentation, ordering, close and cancellation. WebSocket message
boundaries must not corrupt the existing framed protocol or inner TLS records.

Retain inner peer-authenticated TLS for phone-to-phone relays. The cloud forwards
opaque ciphertext and must not substitute its own certificate for the peer.

For the initial remote/browser dispatch design, the cloud is the trusted
dispatch service and may terminate/forward dispatch audio over separately
encrypted connections. Document that distinction clearly; do not call these
dispatch sessions end-to-end encrypted if the cloud can read them. Do not store
voice by default. Stronger dispatcher-to-device media encryption is a separate,
explicit protocol project, not a claim based on HTTPS alone.

Use system PKI validation and hostname checking for public HTTPS/WSS. Do not
reuse the LAN trust manager as a public-server trust policy. Separate renewing
web certificates from long-lived application/device trust, so normal certificate
renewal does not require enrolling every phone again.

Authenticate devices using their existing private keys through supported mutual
TLS or a maintained, standards-based proof-of-possession implementation. A
fingerprint supplied in JSON alone is not authentication. Specify nonce,
audience, expiry, connection binding and replay protection. If a proxy terminates
client TLS, trust certificate identity headers only from the configured trusted
proxy, stripping user-supplied equivalents.

## Routing and migration

Do not globally remove LAN address restrictions. Keep local policy separate from
explicitly configured cloud endpoints.

Do not try a remote phone's reported private IP as if it were local. Two unrelated
networks can both use `192.168.1.0/24`. Prefer an authenticated local discovery
candidate only when it identifies the actual peer on a permitted local network;
otherwise route through the cloud. Neither equal subnets nor equal Wi-Fi names
prove that devices share a network.

Keep stable peer fingerprints separate from local endpoint addresses, cloud
origins, workspace membership and relay session routes. Do not overwrite a
contact's direct address with a cloud socket address. Identity failures must be
reported rather than hidden by fallback.

Introduce cloud invitation/configuration versions and additive storage
migrations. Keep local QR pairing and current local clients interoperable.
Changing to a new cloud hub requires its own enrollment; existing nonexportable
Windows hub keys are not silently migrated.

## Authentication and durable state

Use established account/session libraries. Bootstrap the first administrator
securely, support MFA, and enforce Admin/Dispatcher/Viewer permissions on every
HTTP and WebSocket operation. Use secure HttpOnly browser sessions, CSRF
protection, explicit WebSocket origin rules, expiring sessions and revocation.
Do not put reusable secrets in query strings or browser local storage.

Suggested durable entities: workspaces, users, roles, devices, device memberships,
enrollment invitations, revocations, emergency alerts, alert delivery/acknowledgment
records and metadata audit events. Store invitation secrets hashed, consume them
transactionally and bind enrollment to the authenticated device identity.

Keep voice buffers and session floor ownership in bounded live state. Do not
persist raw audio by default. Persist SOS idempotency/cooldowns independently of
bounded alert history, preserving the current replay and offline-resolution
fixes. A cloud revocation closes cloud access; it does not erase independent LAN
pairings on phones.

## Browser and Android behavior

Browser microphone access requires HTTPS and user permission.
([MDN documentation](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia))

Implement browser PTT with a granted floor, explicit hold and a visible transmit
indicator. Handle pointer cancellation, key release, blur, hidden tabs, permission
prompts resolving after release and connection loss. Stop media tracks promptly;
do not keep capturing while merely suppressing outbound packets. Handle browser
playback activation requirements with an explicit Enable audio/Answer flow.

For the first compatible protocol, use Web Audio/AudioWorklet to produce the
existing 16 kHz PCM16 mono 20 ms frames. Resample from the actual browser audio
sample rate. Keep buffers bounded. Account for the current base64 PCM cost:
about 0.34 Mbit/s per actively receiving device before JSON/TLS overhead, or
roughly 17 Mbit/s for 50 simultaneous receivers. These are calculated transport
estimates, not hosting-capacity guarantees.

Reuse the existing media format initially. Evaluate binary PCM, Opus or
WebRTC/TURN later as an independently tested versioned optimization. Do not
promise low-latency internet audio from TCP/WSS without measuring loss, jitter
and head-of-line blocking.

Android Cloud mode may use cellular and other validated internet connections.
Retain foreground-service, microphone, notification, battery and force-stop
constraints. Optional push notifications can be assessed separately; they must
not silently activate microphones or promise delivery after force-stop.

## Availability and emergency semantics

Differentiate: accepted by server, received by device, and acknowledged by a
person. A connected socket does not prove an announcement was heard.

Persist active alerts with lifecycle/expiry rules. Replay state snapshots after
reconnect so alerts resolved while offline do not remain active locally. Do not
queue old live voice for automatic playback after a device reconnects. A dropped
call ends cleanly; reconnection never resumes microphone capture automatically.

## Phased execution

1. **Audit and hosting decision:** inspect source, confirm provider capabilities,
   document trust boundaries, WSS framing, browser audio and migration design.
2. **Cloud foundation:** cross-platform service, database migrations, account
   roles, device proof/enrollment, public TLS, health checks and persistent keys.
3. **Cloud phone calls:** Android cloud profile, WSS transport and opaque inner
   TLS relaying; preserve offline/local behavior and existing pairing.
4. **Remote dispatch:** Windows remote mode plus browser one-device PTT,
   announcements, floor handling and precise recipient status.
5. **SOS and reliability:** emergency delivery/acknowledgment, resolution snapshots,
   reconnect, bounds, revocation, audit and operational failure reporting.
6. **Deployment:** one-instance Docker Compose or documented system service,
   reverse proxy/TLS renewal, database backup/restore, key rotation, upgrade and
   rollback. Static shared-hosting frontend instructions are a separate profile.

Do not enable multi-instance autoscaling until connection/session ownership,
cross-instance messaging and media routing have explicit implementation and
tests. A shared database or Redis installation alone does not make in-memory
WebSocket sessions distributable.

## Acceptance evidence

- Existing 69 checks and local Java/.NET interoperability remain passing.
- Real cloud calls across separate networks, including cellular, with the local
  Windows hub shut down; wrong identities and cross-workspace access rejected.
- One-to-one PTT, multi-recipient announcement and emergency/SOS flows across
  Android, Windows and supported browsers.
- Overlapping private IP ranges do not misroute peers or overwrite contacts.
- Proxy/TLS renewal, long idle periods, network changes, stale tokens, revoked
  devices, duplicate SOS and offline alert resolution behave correctly.
- A slow/flooding client cannot interrupt healthy listeners; all queues and
  session counts are bounded. Report measured supported load and egress.
- Browser permission denial, late microphone grants, release/repress, tab
  changes, background suspension and disconnect never leave capture running.
- Linux builds and container startup, durable state/key persistence, backup
  restore, upgrade and rollback are actually exercised.
- Mark hardware, carrier, provider and browser scenarios not executed. Do not
  present proposed tests or assumed hosting features as validated results.
