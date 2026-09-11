# CodePanda OTG — Engineering Explainer

> This document walks through the design and implementation of CodePanda OTG: an
> Android app that manages a *second* Android phone over USB-OTG by speaking the
> ADB protocol directly. Read it top-to-bottom, or skip the Background if you
> already know how ADB works.

---

## 1. Background

### 1.1 For the newcomer: what is ADB, really?

When you type `adb install app.apk` on a laptop, three things are cooperating:

1. **`adbd`** — a daemon running on the *phone*. It is the thing that actually installs the
   APK, lists files, runs shell commands, and so on. It is always there; "USB debugging" in
   Developer Options simply lets it accept connections.
2. **The ADB server** — a background process on your *laptop* that owns the USB connection.
3. **The `adb` client** — the command you typed, which talks to the server.

> 💡 **Key idea.** "ADB" is not a program so much as a *wire protocol*. Anything that can open
> the phone's USB bulk endpoints and speak that protocol can be the host — including another
> phone. That is the entire premise of CodePanda.

The protocol itself is delightfully small. Every packet is a **24-byte header** plus an
optional payload:

```
command (u32)  arg0 (u32)  arg1 (u32)  data_length (u32)  data_crc32 (u32)  magic (u32)
```

`magic` is just `command ^ 0xffffffff`, a cheap sanity check. The commands are a six-item
menu: `CNXN` (connect), `AUTH` (authenticate), `OPEN`/`OKAY`/`WRTE`/`CLSE` (open a stream,
acknowledge, write, close).

### 1.2 Authentication: why the phone shows a prompt

The first time you plug a phone into a new computer it asks *"Allow USB debugging from this
computer?"*. That handshake is RSA:

1. Host sends `CNXN`.
2. `adbd` replies `AUTH(TOKEN, …)` with a 20-byte random token.
3. Host signs the token with its **private** RSA key and returns `AUTH(SIGNATURE, …)`.
4. If `adbd` doesn't recognise the signature, the host sends its **public** key
   (`AUTH(RSAPUBLICKEY, …)`), which triggers the on-screen prompt.
5. Tap *Always allow* and `adbd` stores the key; next time step 3 succeeds silently.

### 1.3 The narrow background for this change

This repository started essentially empty. CodePanda OTG is a greenfield implementation of the
host side of that story, targeting **Android's USB Host API** as the transport and **Jetpack
Compose** for the UI.

---

## 2. Intuition

Think of one USB cable as a single telephone line that many conversations must share. ADB
solves this with **stream multiplexing**: each logical conversation (a shell command, a file
download, the video feed) is a *stream* identified by a pair of integers — our `local-id` and
the device's `remote-id`.

> 💡 **Toy example.** Say we want to run `getprop` while also pulling a file.
>
> - We `OPEN(local=1, "shell:getprop")`. adbd replies `OKAY(remote=7, local=1)` → stream A is
>   (1 ↔ 7).
> - We `OPEN(local=2, "sync:")`. adbd replies `OKAY(remote=8, local=2)` → stream B is (2 ↔ 8).
> - Now every inbound packet carries `arg1 = our local-id`, so an incoming `WRTE(…, arg1=1)`
>   is `getprop` output and `WRTE(…, arg1=2)` is file data. One reader thread fans them out.

The second essential idea is **flow control**. After you send one `WRTE` you must wait for the
peer's `OKAY` before sending the next. We model that with a one-permit semaphore per stream:
sending consumes the permit; the `OKAY` hands it back.

Everything the app does is built on those two ideas plus the stock device tools:

- *List apps?* `pm list packages -f`.
- *Debloat?* `pm disable-user --user 0 <pkg>` — reversible, no root.
- *Push a file?* the `sync:` service's `SEND` command.
- *Mirror the screen?* `screenrecord --output-format=h264 -` → a hardware `MediaCodec` → a
  `Surface`.

---

## 3. Code walkthrough

### 3.1 Packets and the connection state machine

`AdbMessage` serialises the header; note the checksum is the unsigned **sum** of payload bytes
(a historical quirk, not a real CRC):

```kotlin
fun payloadChecksum(payload: ByteArray): Int {
    var sum = 0L
    for (b in payload) sum += (b.toInt() and 0xff)
    return (sum and 0xffffffffL).toInt()
}
```

`AdbConnection` owns one reader thread and serialises writes behind a lock — the
"one reader, many writers" pattern that makes multiplexing safe:

```kotlin
private fun dispatch(message: AdbMessage) {
    when (message.command) {
        A_CNXN -> handleConnect(message)
        A_AUTH -> handleAuth(message)
        A_OKAY -> {
            val stream = streams[message.arg1] ?: return
            if (!stream.isOpened) stream.onOpened(message.arg0) else stream.onReady()
        }
        A_WRTE -> {
            streams[message.arg1]?.let {
                it.onPayload(message.payload)
                sendMessage(AdbMessage.okay(message.arg1, message.arg0)) // flow-control ack
            }
        }
        A_CLSE -> streams.remove(message.arg1)?.onRemoteClose()
    }
}
```

### 3.2 The tricky bit: `AdbCrypto`

The signature is *not* `SHA1withRSA`, because the token adbd sends is already a digest. We
hand-apply PKCS#1 v1.5 padding (with the SHA-1 DigestInfo header) and do a raw RSA operation,
exactly like the C implementation:

```kotlin
fun signToken(token: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, privateKey)
    cipher.update(SIGNATURE_PADDING) // 236 bytes so padding+token == 256
    return cipher.doFinal(token)
}
```

The public key must be re-encoded into adbd's 524-byte Montgomery structure (`modulus_size`,
`n0inv`, little-endian `modulus`, `rr = R² mod N`, `exponent`). Getting the endianness and
`n0inv = 2³² − (n mod 2³²)⁻¹` right is the whole ballgame.

### 3.3 File transfer: the `sync:` sub-protocol

`AdbSyncClient` rides one `sync:` stream and speaks a language of four-byte tags. A pull is:

```
→ RECV <path>
← DATA <len> <bytes…>   (repeated)
← DONE
```

```kotlin
fun pull(path: String, out: OutputStream, onProgress: (Long) -> Unit = {}) {
    sendRequest(ID_RECV, path)
    var total = 0L
    while (true) when (readTag()) {
        ID_DATA -> { val n = readInt(); out.write(stream.readExact(n)); total += n; onProgress(total) }
        ID_DONE -> { stream.readExact(4); break }
        ID_FAIL -> throw AdbServiceException("RECV failed: ${readFailMessage()}")
    }
}
```

### 3.4 The features are thin

Because the plumbing is solid, each feature repository is a few shell calls. Debloating:

```kotlin
suspend fun uninstallForUser(pkg: String) =
    CommandResult.from(shell.exec("pm uninstall -k --user 0 $pkg"))
```

### 3.5 Live mirroring

`screenrecord --output-format=h264 -` emits a raw Annex-B H.264 stream on stdout. `VideoDecoder`
splits it into NAL units, uses the SPS/PPS parameter sets to configure a `MediaCodec`, and
releases decoded frames straight to a `Surface`:

```kotlin
when (type) {
    NAL_SPS -> { sps = nal; if (pps != null && codec == null) configure(sps!!, pps!!) }
    NAL_PPS -> { pps = nal; if (sps != null && codec == null) configure(sps!!, pps) }
    else    -> { val mc = codec ?: continue; feed(mc, nal); drain(mc, bufferInfo) }
}
```

### 3.6 UI

The UI is a single-activity Compose app. `AppRoot` switches between the connect flow and a
five-tab scaffold purely as a function of `SessionManager.state` — there is no manual
navigation between "connected" and "disconnected".

---

## 4. Verification

> ⚠️ **What was tested and what wasn't.** The build machine had the JDK and Gradle but **no
> Android SDK and no physical device**, so the app was not run end-to-end here. The
> protocol-critical, device-independent Kotlin (the `adb` package) was **type-checked in
> isolation** with the Kotlin compiler and small Android stubs, and passed the frontend with
> zero errors.

**How to QA manually:**

1. `./gradlew :app:assembleDebug` on a machine with Android SDK 34; install on a phone with a
   USB-OTG port.
2. On a *second* phone, enable **Developer options → USB debugging**.
3. Connect the two with an OTG cable. In CodePanda tap **Connect**, then approve the prompt on
   the target (choose *Always allow*).
4. **Device** tab → confirm model/CPU/battery populate and *Show hidden info* lists `getprop`.
5. **Apps** tab → filter *User*, open an app's *Details*, then *Extract APK* (check the saved
   path in the snackbar). Try *Disable* then *Enable*.
6. **Files** tab → browse `/sdcard`, pull a file, then push one back with the ⬆ button.
7. **Debloat** tab → pick a *safe*-rated app, *Disable* it, confirm it disappears from the
   target's launcher, then *Enable* it again.
8. **Mirror** tab → *Screenshot* renders the current screen; *Live mirror* shows the feed.

**Recommended automated tests to add next:** unit tests for `AdbCrypto` (public-key encoding
against a known `adb pubkey` fixture, and a sign/verify round-trip), and for the `AdbSyncClient`
tag parser using recorded byte fixtures.

---

## 5. Alternatives considered

### 5.1 Bundle the real `scrcpy-server` instead of using `screenrecord`

| Pros | Cons |
| --- | --- |
| Much lower latency; 60fps; handles rotation | Ships a GPL binary that must be version-matched to the client |
| Supports control (touch/keys) injection | Significantly more protocol surface to implement and maintain |
| No 3-minute session cap | Larger APK; more moving parts to break |

CodePanda chose `screenrecord` so the app is fully self-contained and dependency-free; scrcpy
integration is a natural future enhancement layered on the same `AdbConnection`.

### 5.2 Use an existing ADB library (e.g. dadb / adblib)

| Pros | Cons |
| --- | --- |
| Less code to own; battle-tested | Most assume a JVM/desktop or a TCP socket, not Android USB Host |
| Faster initial delivery | Heavier dependency; less control over the USB transport quirks |
|  | Obscures the very mechanism this project exists to demonstrate |

A first-party implementation keeps the transport swappable (USB today, TCP/Wi-Fi tomorrow) and
the dependency footprint tiny.

---

## 6. Suggested people to talk to

This is a brand-new project, so there is no prior authorship history on these files to mine.
The natural point of contact is **@K0986** (the repository owner), who commissioned the feature
set and owns the product direction. For the ADB-protocol internals, anyone familiar with the
AOSP `system/core/adb` sources or Cameron Gutman's classic Java ADB implementation will feel
at home in the `adb` package.

---

## 7. Quiz

<details>
<summary>1. Why does <code>AdbConnection</code> use a single reader thread but a lock around writes?</summary>

**Answer: to safely multiplex many streams over one full-duplex link.**
Inbound packets for *all* streams arrive interleaved on the same pipe, so one reader must fan
them out by `arg1` (our local-id). Writes, however, come from many threads (shell, sync,
mirror) and would corrupt each other if interleaved mid-packet — hence one serialising lock.
A design with multiple readers would race on the socket; a design with no write lock would
splice packets together.
</details>

<details>
<summary>2. The device sends <code>WRTE</code> with payload. What must the host do before the device will send more, and why?</summary>

**Answer: reply with <code>OKAY</code>.** ADB uses strict one-in-flight flow control per
stream. Until the sender receives an `OKAY` acking the previous `WRTE`, it will not send the
next one. Forgetting the ack deadlocks that stream (but not others).
</details>

<details>
<summary>3. Why is the ADB auth signature produced with <code>RSA/ECB/NoPadding</code> rather than <code>SHA1withRSA</code>?</summary>

**Answer: the token is already a SHA-1 digest.** If we used `SHA1withRSA` we would hash the
digest a second time. Instead we manually prepend the PKCS#1 v1.5 padding and SHA-1 DigestInfo
header and perform a raw RSA operation — matching what adbd expects to verify.
</details>

<details>
<summary>4. What makes CodePanda's debloat actions reversible without root?</summary>

**Answer: they only change per-user state.** `pm disable-user --user 0` and
`pm uninstall --user 0` remove/disable the app *for user 0* while leaving the factory system
image intact. `cmd package install-existing` (or a factory reset) restores it. Truly deleting
the system image would require root.
</details>

<details>
<summary>5. In <code>VideoDecoder</code>, why are the SPS and PPS NAL units treated specially?</summary>

**Answer: they are the codec configuration, not picture data.** The SPS (type 7) and PPS
(type 8) describe resolution, profile and other parameters the decoder needs *before* it can
interpret any frame. `MediaCodec` is configured with them as `csd-0`/`csd-1`; only then can
subsequent slice NALs be fed and rendered.
</details>
