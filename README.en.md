# Qualia2FA

![Qualia2FA](composeApp/src/commonMain/composeResources/drawable/qualia2fa_logo.svg)

A **serverless, non-realtime** multi-platform two-factor authentication (2FA) app.

中文版见 [README.md](README.md).

Each device runs on its own: no vendor server, no account, no master password. Devices
signed in to the same account can share 2FA entries directly, and merging takes the union.

| Item | Value |
|---|---|
| Platforms | Windows desktop (implemented); Android and iOS are planned |
| UI | Compose Multiplatform |
| Interface languages | English (default), Chinese |
| License | Apache-2.0 |

## Design choices

- **No centre.** No server, no account system, no "forgot password" flow. Your keys live
  only on your own devices.
- **No realtime sync.** Devices share on demand instead of reconciling in the background,
  so "sync conflict" is not a category of problem here, and a server outage cannot keep
  you from a verification code.
- **End-to-end encryption.** Entries are encrypted before they leave the device, so no
  intermediary on the sharing channel ever sees plaintext.
- **Works offline.** Everything keeps working without a network, including code
  generation.

## Current state

**Early development, Windows desktop only so far.** What already works:

- **The screens**: the application opens on a loading screen (mark, name, ring; a narrow
  window moves the ring underneath, a short one drops the name and then the ring), then
  either the login screen, when there is no key on this device, or the main screens
- **The main screens**: 2FA, sync and settings share one navigation — a rail down the left
  in landscape, a bar across the bottom in portrait, with the current screen highlighted —
  and every screen rearranges itself for the window it is in
- **First run**: create a vault (the device identity and the master key go to the platform
  key store, which is reported as it is — TPM or software), or log in by pairing with a
  device that is already unlocked. A device that already holds a master key is only offered
  unlocking and logging in: creating a vault there would replace that key
- **Entries**: add, edit and delete by hand, import one from an `otpauth://` QR code in an
  image, or paste an `otpauth://` link; selection mode deletes several at once, after asking,
  because the tombstone reaches every device the account syncs with
- **Codes**: live display, countdown ring, one-click copy (the clipboard is cleared again
  after 30 seconds, and only if it still holds what was copied). All three HMAC algorithms are
  supported — SHA-1, SHA-256 and SHA-512 — chosen in the entry editor and carried in from the
  `algorithm` parameter when a link or a QR code is imported (SHA-1 being what the
  `otpauth://` scheme itself means when it says nothing)
- **Pairing**: started from the sync screen; a pairing code can be drawn as a QR code and
  read back out of an image, and the text always stays underneath the code
- **Syncing**: two devices show one code each, one direction at a time; wherever the two
  disagree, the user decides entry by entry
- **On the same WiFi or hotspot**: pairing requests and sync payloads no longer have to be
  carried by hand — the two devices find each other and the request and the reply travel over
  the local network themselves. The QR code is still the pairing's **out-of-band anchor**: the
  first pairing still takes one scan, which pins down which device the other side is, and the
  LAN only saves the carrying afterwards. The LAN channel is **opened on demand**: a screen
  that needs it opens one and closes it on the way out, nothing syncs in the background, and
  nothing is announced while no screen is using it
- **Settings**: the interface language, the protection the master key and the device
  identity actually have, recovery key import and export, and locking now
- **Recovery keys**: export (the passphrase is typed twice) and import (replacing this
  device's vault is confirmed first, then the passphrase is asked for)
- **Locking**: the vault locks 15 minutes after it opens, and on demand from the settings;
  locking releases the key material and clears the clipboard, and the login screen unlocks it
  again. **Losing focus does not lock**: the file dialog is a window of its own, so that would
  lock before a QR code could be picked out of an image

Not there yet: multiple accounts, master key rotation, Android and iOS.

## Building and running

This repository has **no submodules**. Each Rust layer is developed in its own repository
and published to crates.io; the app side depends on them by version:

| crate | Contents | License |
|---|---|---|
| `qualia-dik` | Device identity primitives (representation, fingerprint, verification) and the platform-native hardware keystores; the only crate allowed to use `unsafe` | LGPL-3.0-or-later |
| `qualia2fa-core` | All cryptography, the vault format, the sync and pairing protocols. No I/O, no clock, no `unsafe` | LGPL-3.0-or-later |
| `qualia2fa-ffi` | UniFFI bindings (Kotlin / Swift) plus the binding generator | LGPL-3.0-or-later |
| `qualia-transport` | Transport channels: the QR half (capacity model, encoding, rendering, text envelope, image decoding) and the LAN half (multicast discovery, connection records, a QUIC data plane) are both implemented | LGPL-3.0-or-later |

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

To see what this application is like on a device, use `assembleRelease`: **Android refuses to
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

Every push runs the same build on GitHub Actions: the unit tests first, then the Windows MSI
and EXE installers, which are attached to that run.

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
├── src/commonMain/             UI and business code (Kotlin, multiplatform)
│   ├── composeResources/       The mark and the icons (drawable/), interface text: values/ English (default), values-zh/ Chinese
│   └── kotlin/dev/qualia/qualia2fa/
│       ├── Layout.kt           What each window size shows, written as pure functions so they can be tested
│       ├── Theme.kt            Colours, corners and spacing: the palette is the mark's own (#EAF2F4 and #091012)
│       └── *Screen.kt          Loading, login, 2FA, sync, settings, and both sides of pairing
├── src/jvmMain/                Desktop implementation: file storage, file dialogs, the window and locking on focus loss
└── src/jvmTest/                Unit tests
```

`Qualia.kt` is the only file in the repository that imports the generated bindings: the UI
sees ordinary Kotlin values, so replacing, patching or forking the binding layer touches that
one file.

`icons/` holds the `.ico` used for packaging (the Windows installer and the window icon); it
is regenerated from the mark by `icons/make_ico.py`.

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
