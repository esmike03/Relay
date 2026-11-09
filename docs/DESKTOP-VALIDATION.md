# Relay 2.1 validation — 6 November 2026

## Executed checks

Environment: Windows x64, JDK 17, Android SDK 35, and .NET SDK 10.0.403.
Android remains minimum API 26 / target API 35. A Redmi 23021RAAEG running Android
15 was connected later in this task. The user authorized an in-place update and
receive-only testing; no phone microphone was activated during those tests.

| Check | Result |
| --- | --- |
| Android `assembleDebug` | Passed; versionCode 11 / versionName 2.1 |
| Android `testDebugUnitTest` | 22 passed, zero failures/errors |
| Android `lintDebug` | Zero errors; 39 advisory warnings |
| Windows WPF Release build | Zero warnings/errors |
| Headless host Release build | Zero warnings/errors |
| Real TCP/TLS hub integration runner | 40 passed, zero failures |
| Desktop microphone lifecycle runner, synthetic hardware adapter | 7 passed, zero failures |
| Java using the Android TLS implementation through the .NET hub | Passed |
| Incorrect inner peer certificate pin through the hub | Rejected as expected |
| WPF page renders at normal and 850 × 620 minimum size | Passed; six light-mode pages inspected |
| WPF starts real isolated hub and generates distinct enrollment invitations repeatedly | Passed |
| Dashboard filter and enrollment dropdown text contrast | Verified in rendered controls and popups |
| Rendered desktop QR decoded with Android's ZXing core 3.5.3 | Passed; version, address/port, full fingerprint and token valid |
| Real headless host configure/status/enrollment/stop over same-user IPC | Passed |
| Portable self-contained Windows x64 publication | Passed |
| Published desktop executable smoke run and published host `--help` | Passed without requiring the SDK host |
| APK signature and version inspection | Passed; debug signing verified, API 26 minimum |
| Java/.NET two-way hub call, Talk to all, emergency voice and SOS | Passed with two real TLS synthetic endpoints |
| Physical Redmi normal and priority announcements | 217 valid PCM frames received in each session on completed repeat |
| Physical Redmi microphone access | AppOps access timestamp/duration unchanged during receive-only checks |

The 22 Android tests include six private IPv4/CIDR policy checks, four existing
packet-rate regressions, two mutual SSLEngine TLS checks, and three connection
cancellation regressions, and seven dispatch floor/consent regressions. The floor
tests reject grants without a local hold, delayed grants after release,
background capture, unrelated floor owners and phone transmission in receive-only
modes. TLS tests exercise fragmented network records,
100 KiB transfer in both directions, and mismatched fingerprints. Cancellation
tests verify late registration and interruption of a blocked real socket read.

The 40 hub checks use real TLS sockets. They cover pending approval, token
replay/expiry, pending reconnect, directory access, wrong hub pin, unknown
identities, incorrect tunnel attachment, simultaneous independent device-pair
tunnels, per-device busy protection, registration/directory ordering under
reconnect, approval/pending ordering, inner mutual TLS payloads and revocation,
persistent aliases/identity, explicit private-network policy, exclusive ownership,
stale-state write protection, malformed frames, and bounded source rate limits.
New checks cover capability downgrade, dispatch attachment identity, half-duplex
floor control, malformed/no-floor/flooded audio, two-recipient announcements,
emergency preemption, revocation, SOS identity/replay/cooldown/history bounds,
offline resolution snapshots, persistence and a real stalled socket being
dropped without interrupting a healthy recipient.

The seven desktop lifecycle checks use a synthetic audio hardware adapter and a
real authenticated phone connection. They verify no capture without a call,
hold/release, delayed hardware completion, rapid release/repress, a remote floor,
page navigation and session end. The rapid-repress check reproduced an old
attempt closing a newer microphone; serialized capture operations fix it.

The Java/.NET interoperability runner compiles the exact Android `TlsTunnel.java`
source with JDK 17, enrolls two synthetic test peers with the real .NET hub,
negotiates inner mutual TLS, exchanges version-1 call/accept/ready messages and
300 synthetic 640-byte PCM frames in each direction, checks ordered release,
and rejects an incorrect inner peer pin. This establishes transport compatibility;
it does not measure audible quality or exercise the Android service at runtime.
It additionally sends 100 PCM frames in each direction for a hub call, 100 to
each of two receivers for Talk to all and emergency voice, and tests hub alerts,
phone SOS, acknowledgments, identity and resolution across Java and .NET.

## Physical receive-only check

The Redmi was updated from 2.0 to 2.1 without uninstalling. Its existing identity,
trusted contacts and pinned default desktop hub were retained. An explicit field
runner used that hub profile and sent synthetic speech without opening a
microphone. Android logged 217 received frames in the normal announcement,
followed by emergency preemption and 217 frames in the completed priority
announcement. The test alert was resolved; the phone returned to its device list
without a stale emergency banner. AppOps showed no new RECORD_AUDIO access.

An initial emergency run ended after 119 frames without a captured reason. The
field harness was strengthened to reject a receiver leaving during transmission,
and the completed repeat delivered all 217 frames in both sessions. Audio focus
callbacks are also scoped to their originating session so late callbacks from
an interrupted session cannot end the replacement emergency session. Subjective
audibility and two-way physical microphone/headset behavior remain unverified.

## Reproduce

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\desktop\build.ps1 -Publish
```

Individual hub and cross-runtime checks:

```powershell
dotnet run --project desktop/Relay.Hub.Tests -c Release
dotnet run --project desktop/Relay.Desktop.Checks -c Release
dotnet run --project desktop/Relay.Interop.Checks -c Release -- .
```

With an isolated test profile, the desktop supports self-rendering checks:

```powershell
.\Relay.Desktop.exe --data-dir C:\Temp\Relay-UI-Test --smoke-test --smoke-report C:\Temp\Relay-UI-QA\report.json
```

Add `--smoke-compact` for minimum-size layout. Add `--smoke-enrollment` to start
the real hub using that isolated profile's detected private interface and verify
fresh QR invitation generation. The smoke run stops the hub and exits. A
normal run retains its profile's persistent identity. Test profiles should not
be reused as a production identity. Configure an unused test port before an
enrollment smoke run when real phones are already paired to the default hub,
so their certificate pin checks do not encounter an unrelated test identity.

The workspace's generated WPF reports and page images are under
`desktop/Relay.Desktop/obj/visual-qa*`. Interactive computer-use inspection was
unavailable because desktop access was not approved; page rendering and the
application's own functional checks were used instead.

## Field validation still required

- Two updated Android phones: original same-subnet pairing/calls, microphone,
  simultaneous TALK arbitration, recording indicators, audio cues, and headsets.
- Existing older Android client with 2.1 client on the same subnet. The direct
  version-1 wire format is preserved, but this combination was not run on phones.
- Real routed subnets with TCP 52743 allowed; pairing and calls in both directions.
- Real VLANs blocking phone-to-phone traffic but allowing both phones to TCP
  52744 on the hub; peer pairing and calls through the hub.
- Network blocked entirely to the hub; verify useful status and recovery after
  the network administrator restores the route.
- Android API 26 hardware, OEM battery management, overnight standby, locked
  screen behavior, Wi-Fi roaming, DHCP address changes, and multiple physical
  PC network interfaces. Policy and endpoint handling were checked in code and
  automated tests, not on those physical networks.
- Windows service installation through the Service Control Manager, intended
  service account rights, restart policy, power loss, and account/profile access.
  The host's console operation and same-user admin IPC were tested.

No router, Windows Firewall, adapter bridging, OS forwarding, or service
installation settings were changed. Windows packages and the APK are development
outputs without production distribution signing. No claim of complete field or
production validation is made.
