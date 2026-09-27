# GENZO — Vertical Slice (Step 8)

Proves one thing, end to end, for real: **Device A generates an identity →
registers prekeys → Device B does the same → Device A fetches Device B's
bundle → X3DH session establishment → Double Ratchet init → encrypt →
send through a Rust relay → Device B receives → decrypts → persists the
plaintext in an SQLCipher-encrypted local database.**

Every cryptographic operation is a direct call into the official
`signalapp/libsignal` Rust crate (pinned at git tag `v0.73.0` — see the root
`Cargo.toml` comment and `/docs/KNOWN_LIMITATIONS.md` §A1 for exactly why).
Nothing here reimplements or simplifies a cryptographic primitive.

**This is a prototype proving a pipeline, not an audited product.** Read
`/docs/KNOWN_LIMITATIONS.md` before relying on anything here.

---

## What's in this repo

```
vertical-slice/
├── Cargo.toml               workspace root — dependency version pins explained here
├── common/                  wire-format DTOs shared by relay + device
├── local_store/              SQLCipher-backed local message store
├── relay/                     minimal relay server (std::net only, no plaintext ever stored)
├── device/
│   ├── src/lib.rs             Device: identity/prekey gen, X3DH, encrypt/decrypt (+ unit tests)
│   ├── src/net.rs             hand-rolled HTTP/1.1 client (std::net only)
│   ├── src/relay_client.rs    typed wrapper: register/fetch_bundle/send/pull + polling helpers
│   ├── src/bin/device_a.rs    standalone demo process ("Alice")
│   ├── src/bin/device_b.rs    standalone demo process ("Bob")
│   └── tests/two_device_flow.rs   full integration test, real sockets, real SQLCipher
├── android_reference/        Kotlin reference sketch — NOT compiled, see its own README first
└── docs/
    └── KNOWN_LIMITATIONS.md  read this before anything else
```

---

## Requirements to build

- Rust + Cargo. This was built and tested against **rustc/cargo 1.75.0**
  (Ubuntu 24.04's `apt` package) specifically — see `Cargo.toml`'s header
  comment for why the dependency versions are pinned the way they are. A
  newer toolchain (1.85+) should also work and would let you move the
  `libsignal-protocol` git tag forward to current `main`.
- `clang`, `libclang-dev`, `cmake`, `make`, `protobuf-compiler`, `pkg-config`,
  `libssl-dev`, `git` (needed to build `libsignal-protocol` itself, which
  vendors/builds parts of BoringSSL-adjacent crypto and uses `protoc` for
  its protobuf definitions).
- Network access to `github.com` (to fetch the pinned `libsignal` git
  dependency) and `crates.io` (for everything else). No other network
  access is required to build or run this.

On Ubuntu/Debian:
```bash
apt-get install -y rustc cargo clang libclang-dev cmake make protobuf-compiler pkg-config libssl-dev git
```

## Build

```bash
cd vertical-slice
cargo build --workspace
```

First build will take a few minutes (compiles `libsignal-protocol`,
`rusqlite`'s bundled SQLCipher, etc.). Subsequent builds are incremental.

## Run the automated tests

```bash
cargo test --workspace
```

Expected: **13 passed; 0 failed** across three test binaries:
- `local_store` (3 tests): write/read round trip, wrong-key rejection,
  raw-file-contains-no-plaintext.
- `device` unit tests (7 tests): identity generation, prekey generation,
  session establishment + encrypt/decrypt round trip, ratchet-advances-
  correctly-before-acknowledgment, responder-replies-as-plain-ratchet-
  message, wrong-device decryption failure, tampered-ciphertext rejection.
- `device` integration tests (3 tests, in `tests/two_device_flow.rs`): the
  full pipeline over real relay + real sockets + real SQLCipher persistence
  with a simulated app-restart; a two-way ping/pong; clean failure against
  an unregistered recipient.

## Run the two-device demo as two real separate processes

Three terminals:

**Terminal 1 — the relay:**
```bash
RELAY_ADDR=127.0.0.1:8765 cargo run -p relay
```
```
privacy-messenger relay listening on http://127.0.0.1:8765
routes: POST /v1/register  GET /v1/prekey_bundle/:user_id  POST /v1/messages/:user_id  GET /v1/messages/:user_id  GET /v1/health
```

**Terminal 2 — Device B ("bob"), started first so it's ready to receive:**
```bash
RELAY_ADDR=127.0.0.1:8765 cargo run -p device --bin device_b
```

**Terminal 3 — Device A ("alice"):**
```bash
RELAY_ADDR=127.0.0.1:8765 cargo run -p device --bin device_a
```

### Expected output

`device_a`:
```
[device A / alice] using relay at 127.0.0.1:8765
[device A] step 1: generating identity...
[device A]   identity_key (b64, first 24 chars) = <...>...
[device A]   registration_id = <...>
[device A] step 2: generating + registering prekeys...
[device A]   registered as 'alice' with the relay directory
[device A] step 3: waiting for bob to register, then fetching his prekey bundle...
[device A]   got bob's bundle (registration_id=<...>)
[device A] steps 4-5: running X3DH + initializing the Double Ratchet...
[device A]   session established
[device A] step 6: encrypting: "Hello Bob, this is Alice. This message is end-to-end encrypted!"
[device A]   ciphertext = <N> bytes, message_type = 3 (PreKeySignalMessage)
[device A] step 7: sending ciphertext through the relay...
[device A]   sent. waiting for bob's reply...
[device A] received + decrypted bob's reply: "Got it, thanks Alice! - Bob"
[device A] done.
```

`device_b`:
```
[device B / bob] using relay at 127.0.0.1:8765
[device B] step 1: generating identity...
[device B] step 2: generating + registering prekeys...
[device B]   registered as 'bob' with the relay directory
[device B] step 8: waiting for a message from alice...
[device B]   received <N> bytes from alice (message_type=3)
[device B] step 9: decrypting (this also completes X3DH on bob's side, consuming his prekeys)...
[device B]   decrypted: "Hello Bob, this is Alice. This message is end-to-end encrypted!"
[device B] step 10: storing the message in a local SQLCipher-encrypted database...
[device B]   stored at /tmp/genzo_device_b_messages.db
[device B]   (1 message(s) now in this conversation locally)
[device B] replying: "Got it, thanks Alice! - Bob"
[device B]   reply sent. done.
```

You can confirm the persisted database is genuinely encrypted at rest:
```bash
strings /tmp/genzo_device_b_messages.db | grep "Hello Bob"   # → no output: plaintext is not recoverable from the raw file
```
Opening that same file with `sqlite3` directly (no key) will fail with
`file is not a database` — that's the point.

## What this does *not* yet do

See `/docs/KNOWN_LIMITATIONS.md` for the full breakdown, but briefly: no
persisted protocol/session store (ratchet state is in-memory only — a
restarted device can't continue an existing conversation, only start new
ones or read what it already saved to `local_store`), no multi-device, no
batch prekeys, no attachments, no disappearing messages, no key
verification UI, no backup, no rate limiting on the relay, and the Android
client is an unverified reference sketch (no Maven/SDK access in this build
environment — see `android_reference/README.md`).
