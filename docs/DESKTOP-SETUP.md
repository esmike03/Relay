# Relay Desktop Manager 2.1

## Install and enroll

1. Extract `RelayDesktop-2.1-win-x64.zip` to a permanent folder on a supported
   Windows x64 PC. The portable package includes .NET; an SDK is not required.
2. Run `Relay.Desktop.exe`. Open **Network** and review the selected private
   IPv4 listener addresses and approved source CIDRs. Add each participating
   phone subnet explicitly. Save settings while the hub is stopped.
3. Start the hub. In **Enrollment**, select the PC address reachable from the
   phone and generate an invitation. Each invitation is single-use and expires
   after ten minutes.
4. Install the updated Relay 2.1 APK on the phone, preserving the existing
   installation/signing identity. Open Relay and **Become available**.
5. Open **Settings → Desktop manager → Desktop connection → Scan desktop QR
   code**, or paste the enrollment link / enter the complete desktop details.
6. Compare the full certificate fingerprint against the desktop screen and tap
   **Approve and connect**. In the desktop Devices screen, select the pending
   phone and approve it. Repeat with a new invitation for every phone.
7. On a phone, open **Devices → Desktop directory** and request pairing with
   another online phone. The recipient must approve. Enrolling with the same
   desktop does not automatically pair phones or enable automatic answering.
8. Call a paired device. The call screen identifies its route as Direct, Routed
   direct, or Via desktop hub. Microphones still operate only while TALK is held.

The updated APK is a development/test build, not a production-signed release.
If the original app was signed by a different key, Android will reject an
in-place update. Keep the original signing key for distribution; uninstalling
erases the phone's identity, contacts, history, and recordings.

## Desktop or Host?

**Relay Desktop** is the light-mode app with a window, device management and
voice controls. Use it to make calls, Talk to all, handle SOS and send emergency
announcements. It includes the hub engine.

**Relay Hub Host** runs the same engine without a window, for console/service
deployment. It has local command-line alert administration, but no microphone
or desktop voice interface. Run one per data profile; you do not need both.

## Calls, Talk to all, and emergencies

1. Start the desktop hub and enable **Available** on updated enrolled phones.
2. To speak with one phone, select it under **Devices → Call selected device**.
   Its user taps **Answer desktop**. Hold the desktop TALK button with the mouse,
   or focus it and hold Space. Release to listen. The phone holds its own TALK
   control to reply. One side owns the floor at a time.
3. For an announcement, choose **Dispatch → Talk to all**. Wait for recipient
   statuses, then hold TALK. This is receive-only on phones. Normal announcements
   skip busy phones; offline or unsupported devices are reported accurately.
4. Choose the desktop's **Emergency…** action to raise an alert and begin a
   priority voice announcement. Confirm the action, review recipients, then hold
   TALK to speak. Emergency voice can interrupt a normal phone call. A separate
   **Send emergency alert** action sends text without opening a microphone.
5. A phone can tap **SOS**, enter an optional message, and confirm **Send SOS**.
   The hub and supported connected phones see its source, time and message.
   SOS can be sent while a voice call is active and does not activate its mic.
6. The desktop operator marks an alert resolved. A phone's **Dismiss on this
   phone** hides only its local copy. Alert state reconciles after reconnection,
   including resolutions made while a phone was offline.
7. **End dispatch** stops the desktop session. A phone can choose **Stop
   listening** or end its desktop call. Releasing TALK, leaving its page, changing
   window focus, disconnecting or stopping the app closes microphone capture.

Select the Windows microphone and speaker/headset under **Dispatch → Audio
devices** while no dispatch is active. Missing devices or OS microphone access
are reported; Relay does not change system privacy settings. Desktop voice is
encrypted with the hub as the participant. Phone-to-phone tunnels retain their
separate end-to-end encryption. Dispatch recording is not implemented.

Connected means the receiver joined, not that a person heard the announcement.
Announcements and alerts require a reachable, running hub and available phones.
Android notification, silent-mode, Do Not Disturb, and force-stop restrictions
continue to apply. Repeated new SOS alerts from a device are limited to one per
30 seconds; identical IDs do not repeat notifications. Older clients must be
updated to 2.1 for dispatch and emergency features.

## Choose a network layout

### One subnet

Existing direct pairing and calls on TCP 52743 remain available without the hub.
Old clients can still make same-subnet direct calls. Hub enrollment and tunneled
calls require updated clients.

### Different subnets, direct routing allowed

Example: phone A is `192.168.10.20/24` and phone B is `192.168.20.30/24`.
Approve `192.168.20.0/24` on A and `192.168.10.0/24` on B under **Settings →
Approved routed subnets**. The router must allow TCP 52743 in the needed
directions. QR/manual pairing works without multicast discovery.

### Different subnets, phones may only reach the PC

Example: A is `192.168.10.20/24`, B is `192.168.20.30/24`, and the hub's reachable
address is `192.168.1.10`. Approve both phone CIDRs in the hub's Network settings.
Allow both phone networks to connect to the hub's TCP 52744 listener. Calls can
then use outbound phone-to-PC connections; phone-to-phone routing is optional.

A PC with multiple private interfaces may listen on more than one selected
address. Each phone must enroll using an address it can actually reach. Relay
does not bridge interfaces or enable Windows IP forwarding. A shared Wi-Fi
name does not prove reachability: guest isolation, VLAN ACLs, VPNs, and firewall
rules can still block the required path.

Set a DHCP reservation for the PC or use a stable administrator-assigned private
address. If the hub address changes, update its configuration on each phone.
The pinned certificate, not the IP address, establishes hub identity.

## Windows Firewall

Relay never adds rules automatically. An administrator can create a narrowly
scoped inbound rule for the selected listener, port, and phone source subnets.
For example, adapt these addresses and run in an elevated PowerShell session:

```powershell
New-NetFirewallRule -DisplayName 'Relay local hub' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress 192.168.1.10 -LocalPort 52744 -RemoteAddress 192.168.10.0/24,192.168.20.0/24 -Profile Private
```

Use the network profile intended by your administrator. Do not open the port to
the public internet or enable broad forwarding. Router/VLAN ACLs must separately
allow the phone-to-hub path. Changing Windows Firewall alone cannot change a
router ACL.

## Background operation and revocation

Closing the desktop window leaves Relay in the system tray with its current hub
state. Double-click the tray icon to reopen. **Quit and stop hub** stops it.
The PC must remain awake and connected; sleep, shutdown, or a hub restart ends
relayed calls. Existing direct calls operate independently.

On Android, hub presence follows **Available**. Going offline closes it.
Force-stop, battery management, reboot, and existing foreground-service rules
still apply. Reopen Relay after force-stop or reboot.

Revoking a device in the desktop stops its hub sessions and blocks hub access.
It does not erase independently paired direct contacts on other phones. Use
**Forget device** on a phone to remove that phone's direct trust. After hub
revocation, a device needs a fresh enrollment invitation and operator approval.
Desktop aliases are local administrative labels, not remotely forced names.

## Headless host and Windows service

The separate `RelayHubHost-2.1-win-x64.zip` runs the same engine without WPF. Run
only one host or desktop instance per profile. The default profile is
`%LOCALAPPDATA%\Relay\Hub`, scoped to the Windows account.

```powershell
.\Relay.Hub.Host.exe --help
.\Relay.Hub.Host.exe --data C:\Relay\HubData --configure --listen 192.168.1.10 --allow 192.168.10.0/24,192.168.20.0/24 --port 52744
.\Relay.Hub.Host.exe --data C:\Relay\HubData
```

From another console under the same Windows account:

```powershell
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command status
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command enroll --host 192.168.1.10
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command approve --fingerprint FULL_DEVICE_FINGERPRINT
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command alias --fingerprint FULL_DEVICE_FINGERPRINT --alias 'Warehouse 1'
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command revoke --fingerprint FULL_DEVICE_FINGERPRINT
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command stop
```

Headless emergency text commands (run under the host's Windows account):

```powershell
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command emergencies
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command raise-emergency --message 'Assistance required'
.\Relay.Hub.Host.exe --data C:\Relay\HubData --command resolve-emergency --id ALERT_ID
```

The admin pipe accepts only the same Windows account. Enrollment links contain
temporary invitation secrets; share them only with the intended device.

For optional Windows service deployment, place the executable and data in
permanent locations. An administrator can register a service explicitly:

```powershell
sc.exe create RelayHub binPath= '"C:\Relay\RelayHubHost\Relay.Hub.Host.exe" --data "C:\Relay\HubData"' start= demand
```

Before starting it, use Services → RelayHub → Log On to configure the intended
Windows account, its service-logon rights, and access to the data directory.
Configure the profile and run administration commands as that same account.
Then start the service using Services or `sc.exe start RelayHub`. Service
installation and account/OS settings are not performed by Relay.

Hub private keys are nonexportable Windows CNG keys belonging to the account
that initialized the profile. Moving only `identity.cer` and the JSON database
to another account or PC does not transfer the private key. A new hub identity
requires fresh phone enrollment. A missing public certificate with an existing
identity key produces an explicit error rather than silently changing identity.

## Diagnostics

- **Waiting for approval**: approve the phone in the manager's Devices screen.
- **Invalid/expired invitation**: generate a fresh invitation; do not reuse one
  consumed by another device.
- **Certificate mismatch**: compare fingerprints before enrolling again.
- **Hub unreachable**: verify the address, selected listener, PC power state,
  source CIDR, Windows network profile, firewall, and router/VLAN ACL.
- **Peer offline**: open Relay on the peer and enable Available; check battery
  and force-stop restrictions.
- **Direct blocked but hub online**: updated paired clients can fall back to
  the relay. Identity failures are reported instead of being hidden by fallback.
- **Another host owns the profile**: quit that desktop/headless host before
  starting another one with the same data directory.

The desktop's local listener check is a local diagnostic. A successful local
check does not establish reachability from another subnet. Call route failures
end a call cleanly; seamless mid-call migration is not implemented.
Cancelling before the other phone attaches can leave a pending hub slot busy
for up to its 15-second attachment deadline before a retry can proceed.

## Build and validation

Install JDK 17, Android SDK 35, and .NET 10 SDK. From the repository root:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\desktop\build.ps1 -Publish
```

If .NET 10 is outside PATH, pass `-DotnetPath C:\path\to\dotnet.exe`. The desktop
script builds the WPF and headless apps, runs real TLS hub checks and Java/.NET
interoperability checks, and publishes portable Windows x64 packages to
`artifacts/`. Android's APK is under `app/build/outputs/apk/debug/`.

See `DESKTOP-VALIDATION.md` for executed checks and remaining device/network
tests. See `HUB-PROTOCOL.md` for the wire format and `DESKTOP-ARCHITECTURE.md` for
component and trust boundaries.
