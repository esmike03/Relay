# Dispatch and emergency extension — Relay 2.1

This extends hub protocol version 1 without changing existing peer calls or
opaque peer tunnels. New phones advertise `capabilities` containing
`dispatch-v1` and `emergency-v1` in each registration. A new hub advertises those
same capabilities in its `registered` reply. Capability decisions use the
current authenticated control connection, never stale persisted app versions.
Legacy clients continue to receive only the original protocol messages.

## Hub as a voice participant

The desktop can make a half-duplex call to one phone, send a receive-only
announcement to all eligible online phones, or send a priority emergency
announcement. This traffic terminates at the hub because the hub is an actual
speaker/listener. It uses pinned mutual TLS between each phone and the hub.
Independent phone-to-phone relayed calls retain inner end-to-end TLS.

The hub sends an invited, approved, capable device a control message:

```json
{"type":"dispatch_offer","id":"32_HEX_ID","mode":"call","name":"Relay dispatch","message":"","from":"HUB_FINGERPRINT"}
```

Modes are `call`, `broadcast`, and `emergency`. A call requires Answer on the
phone. Normal announcements automatically attach only when the phone is idle.
Emergency voice can end an existing normal phone call before attaching. No
announcement can turn on a phone microphone. Busy, unsupported, declined, and
offline recipients must be represented accurately in the desktop session.

Acceptance uses a new outer TLS connection to the same listening port, with the
same approved phone certificate and pinned hub certificate:

```json
{"type":"dispatch_attach","version":1,"id":"32_HEX_ID"}
```

Only the invited certificate can attach, once, within the session deadline. The
hub sends `dispatch_ready` with the same id, mode, and `peer` equal to its own
fingerprint. All subsequent messages remain length-prefixed JSON on this
dedicated socket, separate from management and heartbeat traffic.

- `audio`: `data` is base64 containing exactly 640 PCM bytes, mono signed 16-bit
  little-endian at 16 kHz (20 milliseconds).
- `talk`: phone requests or releases the floor with boolean `on`.
- `floor`: hub announces `owner` as its own fingerprint, the phone fingerprint,
  or an empty string. The hub grants one participant at a time. Only a phone in
  `call` mode can request a transmitting floor.
- `end`: closes the session, optionally with a human-readable `reason`.
- `ping` / `pong`: keep the dedicated connection alive while nobody is speaking.

The desktop captures its microphone only while TALK is held and it owns the
floor. A phone captures only while its user holds TALK, it owns the floor, and
Android permits the microphone foreground service. Release, cancellation,
focus/visibility loss, floor loss, disconnect, or session end stops capture.
Broadcast and emergency receivers never acquire a microphone floor.

A phone can decline through its control connection:
`{"type":"dispatch_decline","id":"32_HEX_ID","reason":"busy"}`.
Bounded per-recipient queues prevent a slow device from delaying every other
recipient. Revocation, hub stop, and control loss close applicable voice sockets.

## Emergency alerts and phone SOS

An approved capable phone sends on its control connection:

```json
{"type":"sos","id":"32_HEX_ID","message":"Assistance needed at loading bay"}
```

The hub derives sender identity and name from the authenticated certificate and
enrollment record. Supplied sender fields cannot impersonate another device.
The hub acknowledges using `sos_ack` and distributes a bounded, persistent alert
to approved `emergency-v1` devices and the desktop:

```json
{"type":"emergency","id":"32_HEX_ID","from":"SENDER_FINGERPRINT","name":"Loading bay","message":"Assistance needed","at":1793912400000,"active":true}
```

The same alert id is idempotent; it must not replay notification sounds. New
phone alerts are rate limited per authenticated identity. Hub-originated alerts
use the hub fingerprint. Active alerts can be sent to a reconnecting capable
device after its registration response. This is not general offline message
queuing.

Only local hub administration resolves an alert. The hub sends
`emergency_resolved` with id, resolution time, and `by` equal to its fingerprint.
Phone dismissal hides its local copy without resolving the network alert.
Resolution and dismissal do not authorize any microphone operation.

On registration, capable phones receive an authoritative
`{"type":"emergency_snapshot","hubId":"HUB_FINGERPRINT","activeIds":["32_HEX_ID"]}`
after registration/directory and before active replay. The phone validates the
pinned hub identity and resolves locally retained alerts from that hub that are
absent from this list. Snapshots and subsequent alerts/resolutions are serialized
per control, so an outdated snapshot cannot overtake a newer alert transition.
This also clears stale local alerts evicted from the hub's bounded history.

Android displays visible alerts and uses an ordinary high-importance emergency
notification channel subject to system notification, silent-mode, and Do Not
Disturb settings. No component changes system volume or bypasses force-stop.
Delivery requires devices to be available and connected to the hub. A hub
emergency voice action also raises an alert even when no audio recipient is
currently available.
