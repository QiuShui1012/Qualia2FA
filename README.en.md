<div align="center" style="align-items: center">

<img src=".idea/icon.svg" alt="icon" style="height: 128px; width: 128px">

# Qualia2FA

[简体中文](README.md) | English

A **serverless, non-realtime** multi-platform two-factor authentication (2FA) app.

[![License](https://img.shields.io/github/license/Qualia2FA/Qualia2FA?label=License)][License]
[![Releases](https://img.shields.io/github/downloads/Qualia2FA/Qualia2FA/total?logo=github&label=Downloads)][Releases]

[License]: LICENSE

[Releases]: https://github.com/Qualia2FA/Qualia2FA/releases

</div>

---

Each device runs on its own: no vendor server, no account, no master password. Devices
signed in to the same account can share 2FA entries directly, and merging takes the union.

| Item                | Value                                      |
|---------------------|--------------------------------------------|
| Platforms           | Windows and Android                        |
| UI                  | Compose Multiplatform                      |
| Interface languages | English (default), Simplified Chinese      |
| Interface theme     | Follows the system's light or dark setting |
| License             | Apache-2.0                                 |

## Installing

Every version is published on the [Releases](https://github.com/Qualia2FA/Qualia2FA/releases)
page:

| Platform                           | Artifacts                       | Notes                                                                                   |
|------------------------------------|---------------------------------|-----------------------------------------------------------------------------------------|
| Windows 10 / 11 (x64)              | `.msi` and `.exe` offline installers | Signed, but with a self-signed certificate, so Windows warns about an unknown publisher |
| Android 8.0 and above (64-bit ARM) | `.apk`                          | Signed with the project's release key, installable as it is                             |

The Android build currently ships only the `arm64-v8a` architecture, and the Windows build
only `x86_64`. Pull requests are very welcome.

After installing, no account and no network are needed.
The camera permission is requested only when a scan is needed, and declining it still leaves
the option to scan an image of a QR code; the local network permission is used only for
pairing or syncing.
The app asks for no background permissions and does not sync in the background.

## Design choices

- **No centre.** No server, no traditional account system, no "forgot password" flow. Your
  keys live only on your own devices.
- **No realtime sync.** Devices share on demand instead of syncing in the background, so a
  server outage cannot keep you from a verification code.
- **End-to-end encryption.** Entries are encrypted before they leave the device, so no
  intermediary on the sharing channel ever sees plaintext.
- **Works offline.** Everything keeps working without a network, including code
  generation.

## Current state

**Early development. Windows desktop and Android are supported, and they are one interface over
one set of decisions.**

The core functionality is essentially complete. What remains is:

- **App password**:

  Require identity verification when unlocking the app, for further security.
  Device biometrics will be integrated; otherwise a separate password has to be set.

- **Removing devices & master key rotation**:

  A device can be removed once it is discarded or no longer trusted.
  Because of how the application is designed, this needs **every** device on the same account
  that has not been removed to run one full sync before the device is gone completely.

- **Linux, iOS and macOS support**:

  Support for devices on those three platforms.
  The author (@QiuShui1012) has no Apple device and has not joined the developer programme, so
  the plans for the latter two are indefinitely delayed.
  **Apple developers are welcome to join!**

## Building and running

| crate              | Contents                                                                                                                                                 | License            |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|
| `qualia-dik`       | Device identity primitives (representation, fingerprint, verification) and the platform-native hardware keystores; the only library crate allowed to use `unsafe` | LGPL-3.0-or-later |
| `qualia-transport` | Transport channels: QR support (capacity model, encoding/decoding, rendering, text envelope) and LAN support (multicast discovery, connection records, a QUIC data plane), both implemented | LGPL-3.0-or-later |
| `qualia2fa-core`   | All cryptography, the vault format, the sync and pairing protocols. No I/O, no clock, no `unsafe`                                                       | LGPL-3.0-or-later |
| `qualia2fa-ffi`    | UniFFI bindings (Kotlin / Swift) plus the binding generator                                                                                              | LGPL-3.0-or-later |

Building needs JDK 21 and a Rust toolchain (MSVC on Windows):

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA

./gradlew :composeApp:jvmTest      # compile and run the unit tests, including round trips into the Rust library
./gradlew :composeApp:run          # start the desktop application
./gradlew :composeApp:packageMsi   # package it (jlink + jpackage)

./gradlew :composeApp:assembleRelease  # Android package, signed and installable
./gradlew :composeApp:assembleDebug    # Android debug package, for checking behaviour only
```

The Android package is written to `composeApp/build/outputs/apk/release/`, and installing it on
a device is `adb install -r composeApp/build/outputs/apk/release/composeApp-release.apk`.

To check what the application looks like on a device, use `assembleRelease`: **Android refuses to
optimize a debuggable package**, so a debug build is several times slower by construction and
judging smoothness from one gives the opposite of what a user sees. `assembleRelease` needs a
signature to be installable and reads it from a `keystore.properties` beside the repository root,
which is not committed; without that file it falls back to the debug keystore, which is enough to
install locally but is not an identity to release under.

The application builds the **published** `qualia2fa-ffi`: during configuration the source is
fetched from crates.io, checked against the SHA-256 the registry index publishes, and
unpacked into the git-ignored `composeApp/rust/`. When the version changes, **`ffiCrateSha256`
has to change with it**: changing only the version number fails at that check, which is
deliberate — it stops "the number says new, the package is old" from passing silently.

Every push runs the same build on GitHub Actions: the Windows side runs the unit tests and
produces the MSI and the EXE, the Android side produces the arm64 APK, and all of them are
attached to that run. A `v*` tag runs the release build instead, which publishes the three
artifacts on a new Release.

To build one layer on its own, work inside its own repository:

```bash
cargo test --all-targets        # Qualia2FA-Core
cargo test --all-targets        # QualiaDeviceIdentityKit (includes tests against a real TPM)
cargo test                      # Qualia2FA-FFI
cargo test --all-targets        # QualiaTransport
```

## Repository layout

```
composeApp/
├── src/commonMain/             UI and business code (Kotlin, shared by both platforms)
│   ├── composeResources/       The mark and the icons (drawable/), interface text: values/ English (default), values-zh/ Chinese
│   └── kotlin/dev/qualia/qualia2fa/
│       ├── Layout.kt           What each window size shows, written as pure functions so they can be tested
│       ├── Theme.kt            Both colour schemes (light and dark), corners and spacing: the palette is the mark's own (#EAF2F4 and #091012)
│       └── *Screen.kt          Loading, login, 2FA, sync, settings, and both sides of pairing
├── src/androidJvmMain/         What the desktop and Android builds share: the vault file, and the wrapped master key beside it
├── src/jvmMain/                Desktop implementation: the data directory, file dialogs, the window
├── src/androidMain/            Android implementation: the activity, the camera, the file picker, the local network, the window theme
│   └── res/values-night/       The window colour and the system bar icons in the dark theme
└── src/jvmTest/                Unit tests (including the contrast of both colour schemes)
```

`Qualia.kt` is the only file in the repository that imports the generated bindings: the UI
sees ordinary Kotlin values, so replacing, patching or forking the binding layer should touch
only that one file. The two features that must not cross that line — the entry list, and where
the master key is kept — are wrapped there as well.

The master key itself is never written anywhere: a key-encryption key is generated inside the
platform key store, never leaves it, and what the data directory holds is the bytes it
produced. Copying that file therefore does not copy the account, and the settings screen
reports which backend answered — `tpm`, `android-keystore` or `software`.

`icons/` holds the `.ico` used for packaging (the Windows installer and the window icon); it
is regenerated from the mark by `icons/make_ico.py`.

## Interface theme

Two colour schemes, light and dark, **following the system setting**. There is no switch of our
own inside the application: the user already has that setting, and a second one could only
disagree with it.

The two schemes are the same mark read twice. Light is the plate with the ink drawn on it
(`#EAF2F4` under `#091012`); dark **swaps those two** — the ink becomes the background and the
plate becomes what is drawn on it — and the secondary surfaces and the error red are derived
from the same two tones rather than from a third colour. Contrast is **computed, not judged by
eye**: `ThemeTest` runs every foreground/background pair the interface actually draws through
the WCAG 2.1 formula and requires AA (4.5:1) from both schemes, so a colour tweak that makes
something unreadable fails a test before it reaches a user.

The two platforms **read the system setting at different moments**, which is their toolkits'
doing rather than a choice here: Android delivers the configuration change to the running
application, so switching the theme in the system settings takes effect immediately; the
desktop toolkit reads the system theme once, when the window is created, so a change while the
application is open takes effect at the next start.

## Interface languages

The text lives in `composeApp/src/commonMain/composeResources/`: `values/` is the default
locale (English) and `values-zh/` is the Chinese translation. The language follows the system
by default and can be switched on the login screen and in the settings; the choice is kept in
`settings.properties` in the data directory.

The selector on the login screen is deliberate: that is the last screen before the vault is
open, and a user who cannot read the interface would otherwise have to reach the settings to
change it — reading them being exactly what they cannot do.

`StringsTest` compares the key sets of the two catalogues: a missing translation falls back to
English silently at runtime, and that comparison is the only thing that catches it.

## Contributing

Three hard constraints before you start:

- **A cryptographic implementation is judged by the test vectors in the specification**, not
  by "it seems to produce codes". The core library already has RFC 4226 / 6238 / 4648 / 9106
  vector tests; a new implementation needs a citation of the same kind.
- **Secret material must not reach logs, error messages or persisted plaintext.** This is not
  negotiable.
- **No silent downgrade.** When the hardware keystore is unavailable it has to be reported as
  such, and the user decides whether to accept weaker protection, rather than quietly falling
  back to a software key.

When you change interface text, change **both** catalogues (English and Chinese), or users see
the English fallback.

## License

The application itself is under the Apache License 2.0; see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE).

The four Rust layers (`qualia-dik`, `qualia2fa-core`, `qualia2fa-ffi`, `qualia-transport`) are
under LGPL-3.0-or-later. The combination is deliberate: the app layer stays permissively
licensed for easy distribution and reuse, while the cryptographic core stays copyleft, so any
modification has to be given back.
