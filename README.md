# CodePanda OTG

**An on-device Android toolkit for inspecting and managing another Android phone over USB-OTG.**

CodePanda OTG turns your phone into a portable "computer" for a second phone. Plug the
target device into your phone with a USB-OTG cable, enable USB debugging on the target, and
CodePanda speaks the **Android Debug Bridge (ADB) protocol directly over USB** — no PC, no
root required for the common tasks.

It re-implements the parts of `adb` that matter (transport, RSA auth, stream multiplexing,
the sync file-transfer sub-protocol, `shell`/`exec` services) as a small, self-contained
Kotlin library, and wraps them in a modern Jetpack Compose UI.

---

## Features

| Area | What you can do |
| --- | --- |
| **Apps / APK manager** | List user & system packages, inspect version/SDK/permissions/paths, enable · disable · force-stop · clear-data · uninstall, **pull APKs** off the device, and **stream-install** an APK from your phone. |
| **File manager** | Browse the device filesystem via the fast binary sync protocol; **pull** and **push** files; create folders, rename, delete. |
| **Device info** | A categorised snapshot — model, Android/build, CPU/SoC, RAM, battery, display, storage — plus the full `getprop` dump ("show hidden info"). |
| **Debloat** | Disable or uninstall-for-user (`--user 0`) preinstalled apps without root, with a curated safety-rated catalog of common bloatware. Everything is reversible. |
| **Screen capture** | One-tap PNG **screenshots**, and **live screen mirroring** (H.264 via `screenrecord`, decoded on-device with `MediaCodec`, scrcpy-style). |

---

## How it works

```
┌───────────────────────────┐        USB-OTG        ┌───────────────────────────┐
│  Your phone (HOST)         │  bulk IN / bulk OUT   │  Target phone (adbd)       │
│                            │ ────────────────────► │  "USB debugging" enabled   │
│  UsbAdbTransport           │                       │                            │
│    └ AdbConnection ────────┼── CNXN / AUTH (RSA) ──┼──► authorises this host    │
│         ├ shell: / exec:   │                       │                            │
│         ├ sync:  (push/pull)                       │                            │
│         └ exec:screenrecord (H.264) ──► MediaCodec │                            │
└───────────────────────────┘                       └───────────────────────────┘
```

The whole ADB stack lives in [`app/src/main/java/com/codepanda/otg/adb`](app/src/main/java/com/codepanda/otg/adb):

- `AdbProtocol` / `AdbMessage` — the 24-byte packet header and command constants.
- `AdbCrypto` — RSA-2048 key generation, the peculiar 524-byte ADB public-key encoding, and
  token signing that produces the on-device "Allow USB debugging?" prompt.
- `AdbConnection` / `AdbStream` — the connect + auth handshake and the "one reader, many
  writers" multiplexer that lets the file manager, shell and mirror share one cable.
- `service/AdbShell`, `service/AdbSyncClient` — the `shell:`/`exec:` and `sync:` services.

`UsbAdbTransport` + `UsbDeviceScanner` bind that stack to Android's USB Host API.

---

## Building

Requirements: **Android Studio Koala+**, **Android SDK 34**, JDK 17.

```bash
./gradlew :app:assembleDebug
```

Run the JVM test suite (packet framing, payload negotiation, flow control, auth signing) with:

```bash
./gradlew :app:testDebugUnitTest
```

Both run on every push and pull request via [GitHub Actions](.github/workflows/android.yml).

Install on a phone that has a USB-OTG port, connect the target device, enable USB debugging on
it, tap **Connect**, then approve the prompt on the target (choose *Always allow*).

> **Note on live mirroring:** it uses the target's built-in `screenrecord` H.264 encoder, so no
> external binary is bundled. The platform caps a recording session at ~3 minutes, so CodePanda
> transparently restarts the stream.

---

## Project layout

```
app/src/main/java/com/codepanda/otg/
├── adb/            # ADB protocol: transport, crypto, connection, streams, services
├── usb/            # USB Host API glue
├── core/session/   # session lifecycle, USB permission, foreground service
├── feature/
│   ├── packages/   # APK manager
│   ├── files/      # file manager
│   ├── deviceinfo/ # device information
│   ├── bloatware/  # debloat tools + catalog
│   └── mirror/     # screenshots + live H.264 decode
└── ui/             # Compose theme, navigation, shared components
```

## Safety & scope

CodePanda performs exactly the operations a developer would run from `adb` on a laptop. It never
needs root for its headline features. Debloat actions use `pm disable-user` / `pm uninstall
--user 0`, both of which are reversible (a factory reset or *reinstall* restores the app).

## License

See the repository owner. This project re-implements the publicly documented ADB protocol.
