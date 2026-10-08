# Relay — local Android walkie-talkie

A working native Android app for private, one-to-one push-to-talk calls on a local network. No account, cloud backend, analytics, subscription, or internet connection is required.

Version **1.2** brings a cosmic glass theme: an indigo-to-purple aurora background, translucent cards, lavender/mint accents, soft shadows, and consistent line icons. The circular Talk control gently breathes and turns mint while transmitting; it respects Android's animator setting. Native gradients create the frosted effect on older phones without requiring live backdrop blur. Compact cards, grouped settings, and keyboard-aware dialogs keep the controls usable on the TC25's smaller display.

## Get started

1. Install `Relay.apk` on both Android devices (Android 8.0 or later).
2. Connect both devices to the same Wi-Fi network and open Relay.
3. Tap **Become available** and allow microphone and notification access.
4. On the second device, choose **Pair a device → Show my QR code**.
5. On the first device, choose **Pair a device → Scan QR code** and point the camera at that code. The request is sent automatically with its full certificate fingerprint. The second device taps **Approve pairing**. Manual IP/key pairing is also available.
6. Tap **Call** beside a trusted contact. The other person chooses **Answer** or **Decline**.
7. Hold **TALK** to transmit. Release to listen. Use **Speaker on / Earpiece** to change the route.
8. Optionally tap **Record call**. Both participants see a recording indicator. After the call, open **History** to play, share the WAV file, or delete the entry and its recordings.

The 12-character pairing key is separate from Android's wireless debugging pairing code. Keys are stable for an installation; uninstalling and reinstalling creates a new identity. A full 64-character certificate fingerprint can also be entered instead of the short key.

## Included

- Mutual pairing approval and the ability to forget contacts.
- Encrypted TLS 1.2 connections with mutual device certificates, pinned identities, and ECDHE/AES-GCM cipher suites.
- Same-subnet private IPv4 checks for Wi-Fi, Ethernet, and compatible Android hotspot interfaces.
- Local network discovery, with manual IP pairing and contact address updates as fallback.
- Incoming call notifications with answer/decline actions and an optional lock-screen call display.
- A foreground availability service that continues after leaving the app screen.
- Microphone capture only during push-to-talk; audio focus handling; speaker and earpiece controls.
- Half-duplex turn handling, including deterministic arbitration for simultaneous button presses.
- Completed, declined, missed, cancelled, disconnected, and interrupted call history with timestamps and durations.
- Optional stereo WAV recordings: your microphone in the left channel and the remote voice in the right, including pauses in the timeline.
- Recording segments can be started/stopped during a call. A segment is limited to 30 minutes. Low-storage and recording errors are reported.
- Private recording storage, playback, export with temporary URI access, and deletion.
- Call and recording recovery after an interrupted process. Duration recovery is approximate to the last five-second journal update.
- Battery, notification, and incoming call screen settings shortcuts.
- Backup and device-transfer exclusions for identities, contacts, history, and recordings.

## Background receiving

Keep **Available** enabled. Android shows a persistent local receiver notification. You can leave the screen or switch apps and still receive a call. Answering brings the call screen forward so Android can grant microphone access legally.

Android **Force stop**, Android's active-app **Stop** control, reboot, or vendor battery management can stop reception. After a reboot or force-stop, open Relay again. On phones with aggressive battery controls, choose unrestricted battery use and enable vendor autostart where available. Android's restrictions mean an app cannot promise reception after force-stop.

Enable notifications. On Android 14 and later, **Settings → Background call settings → Incoming call screen permission** controls whether a call may open on the lock screen; otherwise an incoming call notification remains the entry point. Do Not Disturb and notification settings may silence alerts.

The listener uses battery while available. **Go offline** closes it. Reconnect devices to a local Wi-Fi network to make calls; guest network/client isolation can prevent peers from communicating even when both show the same Wi-Fi name. Discovery uses mDNS; calls use TCP port **52743**. Manual IP pairing helps when mDNS is blocked, but cannot bypass router client isolation. IPv6-only networks and separate routed subnets are outside this version's scope.

## Recordings and privacy

Record only when all participants agree. Relay signals recording to the other device; it does not remotely prevent modified clients or external recorders from recording. Files stay in Android's private app storage and are protected by the device's storage security; they do not have an additional app-level encryption layer. Sharing deliberately gives your chosen receiving app access to that WAV file. Uninstalling removes app history and recordings.

This version handles one peer per call. Group channels, Bluetooth pairing, cloud/push delivery, cellular calls, and cross-subnet routing are not implemented. Wired/Bluetooth headset routing, extended overnight standby, and other manufacturers need additional device testing.

## Build and source

Open this directory in Android Studio. The checked-in Gradle wrapper uses Gradle 8.13 and Android Gradle Plugin 8.13.2. Install JDK 17 and Android SDK platform 35. Android Studio can create `local.properties` for your SDK path.

```powershell
.\gradlew.bat assembleDebug lintDebug
```

The APK appears in `app/build/outputs/apk/debug/app-debug.apk`. `Relay.apk` is a signed **development/test build**, not a Play Store release. Rebuilding with a different debug signing key requires uninstalling the existing build or using the original signing key, which affects saved contacts/history. For distribution, configure a private release signing key, review current store requirements, and test on additional devices. Signing secrets are not included in the source archive.

The app uses Java, Android platform APIs, and the bundled ZXing core library (Apache 2.0) for fully offline QR generation and scanning. The main components are `MainActivity` (screens), `RelayService` (receiver/call lifecycle and transport), `Identity` (Keystore certificates), `Lan` (subnet checks), `Store` (contacts/history/journal), `WavRecorder` (recording timeline), and `RecordingProvider` (temporary read-only export).

See `TESTING.md` for the device tests and remaining validation scope.

## Automatic answering and button shortcuts

Open the **•••** menu beside a trusted contact and choose **Automatic answering**. Tap **Request agreement**; the other device must explicitly tap **Agree and enable**. Once both peers acknowledge, calls between those devices connect automatically on speaker. The microphone remains off until TALK is held. Open the same menu and choose **Revoke agreement** to return to manual answering. Revocation takes effect locally immediately; the remote peer is notified when reachable. A normal call also carries the caller's current agreement state, so a revoked agreement cannot silently enable auto-answer again.

In **Settings → Button shortcuts**, configure each volume key as **System volume**, **Answer call**, **Hold to talk**, or **End/decline call**. Defaults are volume up to answer and volume down to hold TALK. These shortcuts work on the visible call screen.

To use volume shortcuts after leaving Relay, open **Settings → Background shortcuts → Accessibility settings** and manually enable **Relay button shortcuts**. This optional service requests hardware key filtering, not screen content access, and acts only during Relay calls. Android may require **App info → Allow restricted settings** for a sideloaded app. Background key delivery and behavior with the screen off depend on the device and system settings; Android may prioritize another key-filtering accessibility service. For an automatically answered call with no microphone service yet, pressing TALK opens the call screen before enabling microphone access.

The power button and its long-press behavior belong to Android and are not offered as app shortcuts. Force-stop still prevents call delivery, regardless of shortcuts.

On **Redmi / Xiaomi**, also open **App info → Other permissions → Open new windows while running in the background → Always allow**. Without this permission, the device can block a volume shortcut from opening the call screen to enable the microphone. Allow **Show on Lock screen** if you want the call screen on a locked phone. Relay Settings includes a shortcut to these instructions. **Test volume buttons** displays physical key presses from the accessibility service even when no call is active; ordinary shortcuts apply only to incoming or connected calls.
