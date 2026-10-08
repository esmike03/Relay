# Device validation

Initial tests on 8 October 2026 used the user's **23021RAAEG** Redmi running **Android 15 / API 35** and a Java desktop peer on the same LAN. Later installation and UI checks also used the user's **TC25**, running **Android 8.1 / API 27**, with a 480 × 800 display. An agreed automatic call between TC25 and Redmi was observed during the design checks, including the transmitting/listening states.

## Passed checks

- Gradle APK build and Android lint (no lint errors; advisory warnings remain).
- Installation, launching, runtime permission grants, availability service, and the device/settings/history layouts.
- RSA Android Keystore identity generation and mutually authenticated TLS handshakes. Tested negotiated ECDHE_RSA with AES-GCM.
- Incoming pairing request, explicit device approval, acknowledgement, and persistent trusted contact.
- Incoming call from a paired desktop while the Android app was on the home screen; answer starts the foreground audio service.
- Incoming `Notification.CallStyle` alert while the screen was off, with an Activity answer action.
- Unanswered incoming call ends after approximately 45 seconds and creates a **Missed** history entry.
- Outgoing call from Android to the desktop receiver, acceptance/ready handshake, and call completion.
- Android microphone transmission: the desktop received hundreds of valid 20 ms PCM frames while TALK was held.
- Desktop-to-Android audio injection and receiver playback pipeline using a quiet 440 Hz test tone.
- Recording start notification transmitted to the peer, and the Android screen displayed the indicator when the remote peer reported recording.
- Completed stereo 16 kHz, 16-bit WAV was extracted for validation. Both local and remote channels contained nonzero PCM samples; RIFF header, channel count, sample rate, and file length were valid.
- Saved recording playback through Relay's private content provider and MediaPlayer.
- An unpaired certificate attempting a call received **Pair these devices first**.
- Completed and missed history entries survived APK updates signed with the same development key.

- Offline QR encode/decode round trip, displayed phone pairing QR, and Android rear-camera scanner initialization. Optical scanning between two real devices remains to be verified.
- Styled modal layout, full-width actions, and bottom navigation checked visually on the test phone.
- Two-sided automatic-answer request/approval/acknowledgement persisted the agreement. A subsequent background call connected without an Answer tap, using connected-device/media-playback foreground types and no microphone foreground type.
- Agreement revocation acknowledged by the peer; the next call required manual answering.
- Volume-up answered an incoming call and volume-down long-press sent microphone frames while the call screen was visible.
- Interrupted call journal and its recording were recovered after force-stop followed by reopening Relay.
- Input-dialog regression verified on the phone: the keyboard opens and the manual IP, pairing key, port, device name, and contact IP fields accept edits. Test edits were cancelled. Dialog actions remain visible above the keyboard.
- Scanner buttons checked on the phone with a 20 dp gap below the preview and a 12 dp gap between actions.
- Redmi accessibility service confirmed enabled and bound; physical volume up/down events were received. Xiaomi initially denied background call-screen launches. After allowing **Open new windows while running in the background**, physical volume up answered a background incoming call, the call screen opened, and volume-down holds transmitted over 350 PCM frames in total with transmission ending on release.
- A slow key-filter callback caused a repeating system volume-up event. Audio and activity actions now run after acknowledging the key event. After clearing the old key state and updating, a physical-button call completed with 458 microphone frames and no new system volume-adjustment events in the Android audio event log. Configured shortcut keys are consumed throughout incoming/connected calls; selecting **System volume** opts a key back into volume control.
- Relay 1.1 design visually checked on Redmi and TC25: device cards, navigation, line icons, gradients, shadows, dialogs, and active call controls. On the TC25, Talk, speaker, recording, and End call fit without clipping. History and grouped Settings checked on Redmi.
- Relay 1.1 manual-pairing input confirmed on both phones. With the TC25 keyboard open, dialog actions switch to a compact row and remain fully visible; the form scrolls to its other fields. Test addresses were cancelled.
- Relay 1.2 cosmic theme checked on both phones. TC25 home and active-call layouts keep the circular Talk control, speaker, recording, and End call reachable. The recording confirmation and manual-pairing dialog use styled dark panels; a test IP entry accepted input with both compact footer actions visible above the TC25 keyboard, and was cancelled. Redmi Settings, History, and scanner layouts were visually checked. Both devices report versionCode 3 / versionName 1.2 after updating; no fatal Android runtime errors appeared in the recent logs. Gradle assembleDebug and lintDebug passed with advisory warnings.

## Limits of this validation

Most protocol and recording tests used the desktop peer. The later TC25/Redmi call check does not validate all features between two Android phones. Long standby/Doze delivery, vendor process killing, low storage, headset/Bluetooth routes, and networks with client isolation need further tests. Microphone packets and recorded channel data were checked programmatically; subjective audio quality and echo should also be assessed with two real phones.

The application does not receive calls while Android has force-stopped it. Availability must be restored by opening the app. Lock-screen behavior depends on Android notification and full-screen intent permissions.

The optional background key-filtering accessibility service was enabled by the user on the Redmi and tested with physical buttons while the screen was on. Locked-screen/screen-off shortcuts and TC25 background key filtering still require device validation.
