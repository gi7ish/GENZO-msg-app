# Known Limitations — Vertical Slice (Step 8)

This document is deliberately blunt. Nothing here should be read past without
understanding what it means for real-world use. **This is a prototype proving
one narrow pipeline works. It is not an audited product.**

---

## Category A — Environment constraints discovered while building this

These are facts about *this sandbox*, verified empirically (not assumed),
that shaped technical decisions below. They are not properties of the
messenger design itself.

### A1. Pinned to libsignal tag `v0.73.0`, not current `main`

Current `signalapp/libsignal` `main` requires `edition = "2024"` / rustc
1.85+. This sandbox's network allowlist blocks `rustup`/`static.rust-lang.org`,
and Ubuntu 24.04's `apt` only ships rustc 1.75.0 — confirmed by an actual
`cargo build` failure (`feature 'edition2024' is required`), not a guess.

We bisected tags and found `v0.73.0` (May 2025) is the newest official,
Signal-maintained release that still compiles under rustc 1.75.0 — confirmed
by an actual successful build, including its post-quantum (PQXDH/Kyber1024)
components. This is real Signal-authored cryptographic code, not a
third-party reimplementation (see A2), just ~4 months behind upstream `main`
as of this writing.

**What you should do about this in a real environment:** on a machine with
normal internet access, `rustup` will happily install rustc 1.85+, and you
should track current `main` (or the newest tagged release) instead of this
pin. The Cargo.toml comment marks exactly where to change this.

### A2. Do not use the crates.io packages named "libsignal-protocol" or "libsignal-rust"

We checked both before writing any code:
- `libsignal-protocol` on crates.io is an old (2019) wrapper around a
  deprecated C library (`libsignal-protocol-c`), unrelated to and much less
  capable than Signal's current Rust implementation.
- `libsignal-rust` on crates.io is a third-party reimplementation of the
  Signal protocol, not written or audited by Signal.

Neither is what this project depends on. We depend on the real
`signalapp/libsignal` repository directly via a pinned git tag.

### A3. A handful of transitive dependencies had to be version-pinned

Several crates in the dependency graph (`zeroize`, `zeroize_derive`,
`hashbrown`, `openssl-sys`, and originally `axum`/`reqwest`/`tokio` before we
dropped them — see A4) publish newer versions that also require
`edition2024`. We seeded this workspace's `Cargo.lock` from the already-known-
good lockfile produced by building `libsignal-protocol` v0.73.0 standalone,
so cargo reuses proven-compatible versions instead of re-resolving to the
newest (incompatible) ones. **This is a sandbox-compatibility measure, not a
security downgrade** — every pinned version here is a normal, previously
released, non-yanked crate version; none of them are older than necessary,
and `openssl-sys 0.9.114` specifically was chosen because it's the *newest*
version that still declares an MSRV we can build.

### A4. The relay and its HTTP client are hand-rolled over `std::net`, not axum/tokio/reqwest

We tried `axum 0.7` + `tokio 1` + `reqwest 0.12` first. Their dependency
trees pull in `zeroize 1.9`, `icu_*`/`idna` (via `url`), and other crates
that require `edition2024`, and version-pinning the *entire* transitive
closure of a modern async web stack turned into an unbounded chain (fix one
`edition2024` crate, hit another one behind it). Given the brief's own
instruction to build the relay "only to the extent required for this
vertical slice," four routes over `std::net::TcpListener` with one thread
per connection is a reasonable, auditable amount of code, and it sidesteps
the whole problem.

**What you should do about this in a real environment:** with a current
Rust toolchain, swap to axum/tokio (or your team's preferred stack) for
anything beyond a throwaway demo relay — hand-rolled HTTP parsing is fine
for four fixed, internally-generated routes talking to a client we also
wrote, and is not something you want to scale into a real internet-facing
service that has to deal with malformed/adversarial requests, TLS
termination, HTTP/2, connection reuse, backpressure, etc. None of that is
handled here.

### A5. The Android/Kotlin client is a reference sketch, not a compiled artifact

This sandbox has no Android SDK and no Maven Central / Google Maven network
access (only `crates.io`/`npm`/`pypi`/`github.com` are reachable), so
`libsignal-client` for Java/Kotlin (the official JNI bindings), SQLCipher for
Android, and Jetpack Compose cannot be fetched or compiled here at all.

What we *did* verify: we cloned the same `v0.73.0` tag and read the actual
`SessionBuilder.java`, `SessionCipher.java`, `PreKeyBundle.java`, and
`SignalProtocolStore.java` source in `java/shared/java/org/signal/libsignal/protocol/`,
so `android_reference/` mirrors the real method signatures rather than
half-remembered ones. But it has never been compiled or run, and it is not
a stand-in for actually building and testing the Android app.

**What proves the actual cryptography works, then?** The Java/Kotlin
bindings are a thin JNI wrapper around the exact same Rust `rust/protocol`
core we called directly in the `device` crate — same X3DH, same Double
Ratchet, same AES-256-GCM/ChaCha implementation, same code path, just a
different calling language. What's unverified is the *binding layer and the
Android-specific plumbing* (Keystore integration, Compose UI, Gradle/Maven
build), not the cryptographic engine itself.

---

## Category B — Scope simplifications (deliberate, per the brief's "narrow slice" instruction)

- **Single device per user.** `DEVICE_ID = 1` for everyone. Real Signal
  supports multiple devices per account (phone + linked desktop, etc.) — out
  of scope here.
- **One one-time prekey per user, not a batch.** Real clients upload ~100 at
  once and replenish as the server reports a low count. The relay here holds
  exactly one and hands out `None` after it's consumed. A second session
  attempt with the same peer would need a second registration in this MVP.
- **No persisted protocol store.** `InMemSignalProtocolStore` holds session
  ratchet state, prekeys, and identity keys in process memory only. A real
  app must persist this (encrypted, e.g. inside the same SQLCipher database)
  so sessions survive an app restart. In this slice, only the *decrypted
  message* is persisted (see `local_store`) — the ratchet state is not,
  which is fine for a single demo run but means "restart the app and keep
  chatting" isn't implemented.
- **No key verification / safety-number UI.** `Device::identity_key_b64()`
  exposes the raw material a safety-number UI would be built on, but no
  fingerprint/QR comparison flow exists yet.
- **No multi-message / conversation UI, no attachments, no disappearing
  messages, no backup, no storage manager.** All explicitly out of scope per
  your instruction to build only the identity→X3DH→encrypt→relay→decrypt→
  persist pipeline first.
- **The relay's directory and queues are in-memory only** and vanish on
  restart. A real relay needs at least the queue to survive a process
  restart (so undelivered messages aren't silently dropped), typically via
  a lightweight store like Redis/SQLite — deliberately not built here since
  it adds nothing to proving the crypto pipeline.
- **No rate limiting, no abuse prevention, no auth on the relay's HTTP
  endpoints at all.** Anyone who can reach the relay's port can register
  any username and read any (undelivered) envelope queued for a user they
  claim to be. This is fine for a localhost demo; it is not fine for
  anything reachable over a real network.
- **The demo DB-encryption key is generated with the OS CSPRNG and held only
  in process memory** for the life of the demo/test — see the extensive
  comment in `local_store/src/lib.rs::LocalStore::open`. On Android this
  must come from the Android Keystore (hardware-backed where available),
  gated behind biometric/device-credential auth, and must never be written
  to a plaintext file. That boundary is simulated correctly in spirit
  (never touches disk unencrypted) but is not itself hardware-backed here.

---

## Category C — Things that remain true limitations even in a fully production version of this design

These aren't bugs to fix later; they're inherent to the architecture and
should be communicated honestly to users (see the Privacy Dashboard /
transparency screens from the original product brief):

- The relay operator still sees: who is talking to whom (sender/recipient
  identifiers), approximate timing, message sizes, and delivery state. Full
  metadata-hiding needs onion routing / sealed sender, which is out of scope.
- A compromised endpoint (either party's device) defeats all of this —
  encryption protects data in transit and at rest, not a device that's
  already been compromised by malware with code execution.
- Disappearing messages, once implemented, will never stop a screenshot or
  a second device photographing the screen.
- A legally compelled relay operator can be ordered to log new metadata
  going forward, and to hand over whatever transient ciphertext is in the
  queue at the moment of compulsion — there is no cryptographic magic that
  prevents future logging by the operator of a system they run.
