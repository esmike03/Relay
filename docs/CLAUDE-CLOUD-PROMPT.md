# Prompt for Claude — Relay Cloud

Attach `Relay-2.1-source.zip` and `RELAY-CLOUD-PLAN.md`, then send the following.

---

Extend the attached existing Relay 2.1 project with an optional online service
called **Relay Cloud**. Implement a deployable system in reviewable stages,
preserving the existing local Android and Windows features. Use the attached
RELAY-CLOUD-PLAN.md as the architectural brief, and verify it against the actual
source and current official documentation before choosing implementation details.

Start by inspecting the repository, applicable AGENTS.md instructions and these
files: README.md, docs/DESKTOP-ARCHITECTURE.md, docs/HUB-PROTOCOL.md,
docs/DISPATCH-PROTOCOL.md and docs/DESKTOP-VALIDATION.md. Preserve uncommitted work.
Produce the architecture, protocol, trust model, hosting matrix and phased file
plan first, then implement. Do not stop at a mockup or plan. If parallel agents
are available, agree on the protocol first and give cloud/backend, Android,
web/desktop and integration work clear file ownership.

## Goal

Android phones on different Wi-Fi networks or cellular connections can enroll,
pair and communicate through an always-on internet hub. A light-mode web
dashboard and the existing Windows app can manage devices, call one device,
Talk to all, send emergency voice/text and receive phone SOS. Phone-to-phone
cloud calls continue when the Windows desktop is closed.

Keep offline local direct calls and the local desktop hub fully usable. Cloud
accounts/enrollment must be optional and must not silently migrate existing
users or upload their local history/recordings.

## Hosting and stack

- Default to one Linux VPS or an always-on managed application/container host.
  Use cross-platform ASP.NET Core/.NET 10, PostgreSQL and a TypeScript web UI.
  Reuse existing .NET logic instead of rewriting the hub unnecessarily.
- Expose HTTPS/WSS on 443 through a correctly configured reverse proxy. Supply
  automatic public TLS renewal and documented WebSocket timeout/keepalive rules.
- A traditional shared-hosting account may serve the compiled frontend while
  the real-time backend runs elsewhere. Full shared hosting is conditional on
  verified runtime, persistent WebSockets, concurrency, timeout, process, storage
  and bandwidth support. A cPanel/Plesk or Node.js label alone is insufficient.
- If provider/package details are absent, list what must be verified and proceed
  with a local deployable VPS/container profile. Do not invent hosting support,
  prices or credentials. Do not substitute cron, PHP polling or request-lifetime
  processes for live voice transport.
- Do not purchase services or deploy into a live account without explicit
  authorization. Complete source, tests and deployment artifacts locally first.

## Existing implementation facts to verify

- Android is Java, minimum API 26, with availability foreground services.
- Windows uses .NET 10/WPF and NAudio. Core/Host currently target Windows;
  HubIdentity uses nonexportable CNG keys and administration uses a same-user
  Windows named pipe. These are not Linux/public-cloud components as written.
- HubQr and HubClient currently restrict local hub addresses to private IPv4.
- Direct peer TCP is 52743; the local hub uses 52744 and mutual certificates.
- Peer calls use pinned identities and inner end-to-end TLS through opaque
  tunnels. Control/peer messages use bounded four-byte big-endian UTF-8 JSON.
- Existing voice frames are 640 PCM bytes: 16 kHz, 16-bit little-endian mono,
  20 ms, presently base64 encoded.
- Dispatch supports one-device half-duplex calls, receive-only announcements,
  emergency priority and per-recipient states. SOS supports authenticated source,
  replay/cooldown protection, persistence, resolution and reconnect snapshots.
- There are 22 Android, 40 hub and 7 desktop lifecycle checks, plus Java/.NET
  interoperability checks. Treat these as a baseline to rerun, not proof of any
  new cloud feature.

## Architecture and transport

Extract transport, identity storage, durable state and dispatcher controller
interfaces where necessary. Keep Windows CNG, NAudio, service and UI adapters
separate from the cross-platform cloud host.

Add versioned authenticated WSS control and call/dispatch channels. Implement a
bounded ordered byte-stream adapter for opaque peer tunnels and test fragmented
WebSocket messages/TLS records. Preserve peer-to-peer inner certificate pinning
and TLS; the cloud must not impersonate peers or auto-pair enrolled devices.

For initial Windows/browser dispatch, explicitly document whether the cloud
terminates media. Transport encryption is not end-to-end encryption if the
service can read dispatch audio. Do not store voice by default. Do not implement
custom cryptography or a JavaScript TLS stack.

Use public PKI/hostname validation for cloud HTTPS/WSS. Separate renewable web
certificates from stable application/device trust; routine TLS renewal must not
force every device to re-enroll. Existing nonexportable Windows keys cannot be
copied to Linux. Provide durable cloud key storage and a real rotation/recovery
procedure.

Keep LAN policies intact. Add explicit Local/Cloud configuration and versioned
cloud QR enrollment with domain, workspace and short-lived single-use invitation.
Support cellular in Cloud mode. Preserve local QR version 1 and migrate saved
configuration additively without losing identities or contacts.

Never dial a remote site's private IP merely because it matches the local subnet.
Unrelated networks can use identical private address ranges. Only use permitted
authenticated local discovery candidates for local direct routing; otherwise
use the cloud. Keep peer identity, direct endpoints, workspace and cloud routes
separate. Do not hide authentication failures by switching routes.

Refactor Windows remote mode into a genuine authenticated cloud client. It must
not treat the cloud administrator as the holder of the local hub private key or
require a local listening hub for remote dispatch.

## Authentication and authorization

Provide workspace-scoped users, Admin/Dispatcher/Viewer roles, secure first-admin
bootstrap, MFA support and revocable sessions using maintained authentication
libraries. Enforce permissions on every HTTP and WebSocket action, including
device enrollment, pairing routes, dispatch recipients and emergency resolution.

Authenticate device possession of existing private keys using supported mutual
TLS or a maintained standards-based proof-of-possession scheme. Specify nonce,
expiry, audience, connection binding and replay rules. Never trust a fingerprint
or user/device/workspace ID merely supplied in JSON. Treat identity headers from
TLS-terminating proxies as trusted only when the proxy path is authenticated and
untrusted equivalents are stripped.

Use secure HttpOnly browser sessions, CSRF protection, explicit WebSocket origin
rules and appropriate CORS where the frontend is hosted separately. Origin is
not authentication. Do not put reusable secrets in URLs, logs or local storage.
Consume hashed invitations transactionally; bound attempts and session lifetimes.

Store durable workspace/user/device/enrollment/revocation/alert/audit data in the
database. Preserve SOS idempotency and cooldowns independently of bounded alert
history. Keep queues, buffers, pending calls and fanout explicitly bounded.

## Web, desktop and emergency behavior

Create a complete light-mode responsive dashboard: login, devices/presence,
approval/revocation, aliases, selected-device call, Talk to all, emergency
voice/text, SOS source/time/message, receipt/acknowledgment and resolution.
Preserve phone Answer and microphone consent. Broadcast receivers never transmit.
Normal announcements skip busy devices; emergency interruption must be visible
and follow documented policy.

Implement browser audio using supported secure-context media APIs. For the
initial compatible protocol, resample the actual AudioContext sample rate to
16 kHz PCM16 mono and use bounded 20 ms frames. Handle playback activation and
permission denial. A browser or desktop microphone opens only for an explicit
hold after the floor is granted. Release, pointer cancel, blur, hidden tabs,
disconnect and a permission/hardware start finishing after release must stop
tracks/capture. Preserve the tested rapid release/repress protections.

Use existing media framing first. Measure WAN loss/jitter/TCP blocking and
bandwidth before proposing a separate binary PCM/Opus/WebRTC/TURN upgrade. Do not
silently replace the protocol and break older local clients.

Show server acceptance, device receipt and human acknowledgment separately.
Persist/reconcile emergency state after reconnect. Do not claim that Online or
Connected means a message was heard. Do not replay queued live voice after an
offline device returns or restart a microphone automatically after reconnect.
Retain Android foreground-service, notification, DND, battery and force-stop
constraints. Keep optional push delivery separate from microphone activation.

## Reliability, deployment and validation

Implement heartbeats, jittered reconnect, cancellation, revocation, backpressure,
bounded fanout, message/frame limits, stale-session cleanup and metadata-only
audit. A slow or flooding client must not interrupt healthy receivers. Start as
one application instance; do not advertise autoscaling until live connection
ownership and cross-instance media routing are actually implemented and tested.

Deliver Dockerfiles/Compose, database migrations, secret templates without real
secrets, TLS/reverse-proxy configuration, health/readiness checks, persistent
volumes/key handling, backups/restore, upgrade/rollback and deployment guides.
Include a separate static-frontend shared-hosting guide and a provider capability
checklist. Do not deploy the production database publicly or commit signing keys.

Run the existing baseline and new Linux/container/integration/browser checks.
Verify independent-network/cellular calls with the local PC hub shut down;
overlapping private IPs; Windows/browser dispatch; multi-recipient announcements;
SOS/acknowledgment/resolution across reconnect; revoked/unknown/cross-workspace
identities; TLS renewal; long idle periods; network switching; slow receivers;
browser late permission/release races; and restart/backup recovery.

Report commands, results, measured load/egress/latency, and every hardware or
provider scenario not executed. Never claim that a proposed check passed. Do not
install on or operate connected personal devices, capture microphones or deploy
to production as an implicit part of automated tests without the relevant
authorization. Deliver working source, client builds, deployable containers,
protocol/migration documentation and a clear implementation/validation report.

Begin with the repository audit and architectural decisions, then implement the
phases in order with a short update at each meaningful milestone.
