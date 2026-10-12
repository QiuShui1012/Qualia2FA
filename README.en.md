# Qualia2FA

A **serverless, non-realtime** multi-platform two-factor authentication (2FA) app.

Each device runs on its own: no vendor server, no account, no master password. Devices
signed in to the same account can share 2FA entries directly, and merging takes the union.

中文版见 [README.md](README.md).

| Item | Value |
|---|---|
| Platforms | Windows desktop, Android, iOS |
| UI | Compose Multiplatform |
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

**Early development.** The core library (TOTP/HOTP, `otpauth://` parsing, the encrypted
vault, device identity, QR pairing, recovery keys) is complete and covered by tests; the
UI has not started. It is not yet usable as a daily 2FA app.

## Where the dependencies come from

This repository has **no submodules**. Each Rust layer is developed in its own repository
and published to crates.io; the app side depends on them by version:

| crate | Contents | License |
|---|---|---|
| `qualia-dik` | Device identity primitives (representation, fingerprint, verification) and the platform-native hardware keystores; the only crate allowed to use `unsafe` | LGPL-3.0-or-later |
| `qualia2fa-core` | All cryptography, the vault format, the sync and pairing protocols. No I/O, no clock, no `unsafe` | LGPL-3.0-or-later |
| `qualia2fa-ffi` | UniFFI bindings (Kotlin / Swift) plus the binding generator | LGPL-3.0-or-later |
| `qualia-transport` | Transport channels: the QR half (capacity model, encoding, rendering, text envelope, image decoding) is implemented | LGPL-3.0-or-later |

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
```

The UI code does not exist yet, so there is no "build the app" command. To build one layer
on its own, work inside its own repository:

```bash
cargo test --all-targets        # Qualia2FA-Core
cargo test --all-targets        # QualiaDeviceIdentityKit (includes tests against a real TPM)
cargo test                      # Qualia2FA-FFI
cargo test --all-targets        # QualiaTransport
```

## Repository layout

```
.
└── (the UI code will live at the root of this repository)
```

Each of the four Rust layers has its own repository; this one no longer pulls them in as
submodules and keeps no copy of them.

## Contributing

The UI has not started, so the most useful contributions right now are design discussion
and work on the layers themselves. Three hard constraints before you start:

- **A cryptographic implementation is judged by the test vectors in the specification**,
  not by "it seems to produce codes". The core library already has RFC 4226 / 6238 / 4648 /
  9106 vector tests; a new implementation needs a citation of the same kind.
- **Secret material must not reach logs, error messages or persisted plaintext.** This is
  not negotiable.
- **No silent downgrade.** When the hardware keystore is unavailable it has to be reported
  as such, and the user decides whether to accept weaker protection, rather than quietly
  falling back to a software key.

### Setting up

```bash
git clone https://github.com/Qualia2FA/Qualia2FA
cd Qualia2FA
```

## License

The application itself is under the Apache License 2.0; see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE).

The four Rust layers (`qualia-dik`, `qualia2fa-core`, `qualia2fa-ffi`,
`qualia-transport`) are under LGPL-3.0-or-later. The combination is deliberate: the app
layer stays permissively licensed for easy distribution and reuse, while the cryptographic
core stays copyleft, so any modification has to be given back.
