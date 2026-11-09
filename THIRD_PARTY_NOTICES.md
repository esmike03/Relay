# Third-party notices

Relay bundles ZXing core 3.5.3 for offline QR encoding and decoding.

- Project: https://github.com/zxing/zxing
- Copyright: ZXing authors and contributors; individual source files contain their copyright notices.
- License: Apache License, Version 2.0. A copy is included at `licenses/Apache-2.0.txt`.
- The ZXing library itself is unmodified.

Android platform components are provided by the operating system. No Google Play Services scanner module is downloaded or required.

## Windows desktop and headless host

- QRCoder 1.7.0, by Raffael Herrmann and Shane Krueger, generates enrollment QR codes entirely offline. Project: https://github.com/Shane32/QRCoder. MIT license in `licenses/QRCoder-MIT.txt`. The library is unmodified.
- NAudio 2.2.1, by Mark Heath and contributors, provides Windows audio device capture and playback for desktop dispatch. Project: https://github.com/naudio/NAudio. MIT license in `licenses/NAudio-MIT.txt`. The library is unmodified. Constructing Relay's audio adapter does not open a microphone.
- .NET 10, Windows Desktop Runtime, and Microsoft.Extensions hosting / Windows Services libraries are provided by Microsoft and contributors. Project: https://github.com/dotnet. The .NET license and third-party notices are included as `licenses/Dotnet-LICENSE.txt` and `licenses/Dotnet-ThirdPartyNotices.txt`. Published portable packages include the applicable notices.
- Java/.NET TLS interoperability checks use only JDK and .NET platform APIs. The certificate fixture under `app/src/test/resources` is a synthetic test identity; it is not included in the APK and is not a production signing or device identity key.
