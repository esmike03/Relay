# Relay Desktop Manager

## Goal and trust boundaries

Relay provides local, one-to-one peer push-to-talk and desktop dispatch. A Windows manager
maintains an approved device directory and optionally forwards calls between
private IPv4 networks. Android peer identity and mutual pairing are retained.
Enrolling with a hub does not automatically pair phones with each other. The
enrolled hub can offer desktop calls and announcements. Normal desktop calls
require Answer; only a held local TALK control can activate a microphone.

The manager is an application-level relay, not an IP router. Every participating
phone must have a TCP path to the selected desktop listener. VLAN policies may
allow phones to reach the hub while prohibiting phone-to-phone connections.
No operating-system forwarding, adapter bridging, router changes, or cloud
service is required or configured by Relay.

## Components

- `desktop/Relay.Hub.Core`: persistent Windows certificate identity, local device
  registry, explicit interface/range policy, enrollment, presence, directory, and
  bounded opaque tunnels.
- `desktop/Relay.Hub.Host`: headless host, independent of the WPF window.
- `desktop/Relay.Desktop`: Windows management interface and offline QR enrollment.
- `HubDispatch` and `HubEmergencies`: dedicated voice sessions with an
  authoritative floor, bounded fanout, capability negotiation, SOS, persistent
  alerts and reconnect reconciliation.
- `DesktopAudio` / `IDesktopAudio`: Windows microphone and playback hardware
  boundary. Capture starts only after a held button obtains the floor. Serialized
  capture operations prevent a delayed old attempt from stopping a newer hold.
- Android: existing availability foreground service owns a separate hub control
  connection; peer calls use a direct transport or an inner TLS stream through
  the hub. Management messages do not enter the single-call state machine.
  Dedicated desktop dispatch sockets use outer pinned mutual TLS because the
  desktop is the actual speaker/listener. Receive-only announcements never
  acquire an Android microphone floor.

## Implementation stages

1. Management, persistent identity, explicit enrollment approval, device presence.
2. Approved private IPv4 routed connections, preserving same-subnet defaults.
3. End-to-end TLS byte tunnels, route selection, cancellation and diagnostics.
4. Cross-runtime protocol tests, Android builds/lint, desktop package and setup.

## Peer transport and storage

TCP 52743 and the version-1 peer protocol remain unchanged for direct calls.
Trust uses the SHA-256 fingerprint of the actual peer certificate. Hub routes
are separate from direct host/port records; a relay address must never replace a
peer's direct address. Contacts, automatic-answer agreements, call journals,
history and recordings are retained by additive storage changes.

Direct dialing is preferred when policy and reachability permit. Reachability
failures may fall back to an enrolled hub. Identity or authorization failures
must not trigger an alternative route that hides the failure. An interrupted
active call ends cleanly; automatic mid-call migration is outside this release.

## Network and lifecycle

Same-subnet private IPv4 access remains enabled by default. Additional routed
peer access requires explicit private IPv4 CIDRs. Hub endpoints are explicitly
configured and pinned, independent of peer direct-access CIDRs. mDNS remains
useful on each local link; hub enrollment and the authenticated directory handle
cross-subnet discovery. Multicast reflection is not required.

The desktop must remain awake. Closing the management window can leave the hub
running in the tray; Quit stops it. A headless host provides a separate deployment
option. A host and UI must not concurrently own the same hub data directory.
Android availability, force-stop, microphone, foreground service and battery
restrictions continue to apply.

## Validation boundaries

Automated tests can exercise real TCP/TLS peers, control messages, inner TLS,
revocation and malformed input. They cannot establish behavior on a particular
physical VLAN, firewall, OEM battery manager, Bluetooth headset, or locked phone.
Build results and executed tests are recorded separately from pending field tests.

Full group conversations, general Android device management,
remote APK installation, silent microphone activation, and cloud delivery are
outside this version's scope.

## Dispatch and emergency behavior

One-device desktop calls use a single half-duplex floor. Talk to all is a
one-way announcement to eligible online devices, with pending/connected/skipped
statuses. A slow recipient is dropped without blocking healthy recipients.
Normal announcements skip busy phones; emergency voice can interrupt an existing
normal call. The desktop operator must hold TALK to send microphone audio.

Emergency alerts and voice sessions have separate identities. An announcement
must never create a second synthetic alert that cannot be resolved. SOS identity
comes from the authenticated phone certificate. Duplicates are acknowledged
without repeating notifications, and persisted per-device cooldowns limit new
alerts. Active alert snapshots reconcile a reconnecting phone's old local state,
including alerts removed from the hub's bounded history after resolution.

The desktop uses a light palette. Android retains its existing theme and gains
SOS, emergency banners and a dedicated desktop call/announcement screen.
