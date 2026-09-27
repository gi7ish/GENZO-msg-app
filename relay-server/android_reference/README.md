# Android Reference Sketch — READ THIS FIRST

**This code has never been compiled or run.** This sandbox has no Android
SDK and no network access to Maven Central / Google Maven (see
`/docs/KNOWN_LIMITATIONS.md`, item A5), so there is no way to build or test
a Kotlin/Android project here.

## What this is

A structural mirror of the exact flow proven working in the `device` Rust
crate — generate identity, generate + register prekeys, fetch a peer's
bundle, establish an X3DH session, encrypt, decrypt — written against the
**real** `libsignal-client` Java/Kotlin API. We did not guess these method
names: we cloned the same `signalapp/libsignal` tag (`v0.73.0`) this
project's Rust code is pinned to and read the actual source of
`SessionBuilder.java`, `SessionCipher.java`, `state/PreKeyBundle.java`,
`state/SignalProtocolStore.java`, and
`state/impl/InMemorySignalProtocolStore.java` to get these signatures right.

## What this is not

- Not compiled. Not linked against the real `libsignal-client` AAR (which
  itself would need to be built from this same repo, or fetched from Maven
  — neither is possible in this sandbox).
- Not tested. No unit tests, no instrumented tests, nothing has actually
  executed.
- Not using Android Keystore or SQLCipher-for-Android for real — those
  integration points are stubbed with comments describing what belongs
  there, matching the architecture doc's original design.
- Not a complete app. No Compose UI, no Gradle project, no `AndroidManifest.xml`.
  Just the crypto/networking/storage-boundary logic, structured the way it
  would sit inside `core-crypto`, `core-network`, and `core-database`
  modules per the original project-structure proposal.

## What actually proves the cryptography works, then?

The `device` Rust crate, tested and demonstrated in this same repository.
`libsignal-client`'s Java/Kotlin bindings are a thin JNI wrapper around the
identical Rust `rust/protocol` core the Rust crate calls directly — same
X3DH implementation, same Double Ratchet implementation, same AEAD code.
What's unverified here is specifically the JNI binding layer and Android
platform integration (Keystore, Compose, Gradle/Maven build), not the
underlying cryptographic engine.

## Before shipping any of this

1. Get a machine (or CI environment) with real internet access, install the
   Android SDK + a current JDK, and either pull `libsignal-client` from
   Maven (`org.signal:libsignal-client:<version>`) or build it from source
   per `java/README.md` in the `signalapp/libsignal` repo.
2. Replace `InMemorySignalProtocolStore` (used below only because it's the
   one thing that requires zero extra plumbing to demonstrate the flow)
   with a real store backed by Android Keystore (identity/prekey private
   material) and an SQLCipher-encrypted database (session/ratchet state),
   exactly as designed in the original architecture document.
3. Compile it. Run the unit tests you write against it. Only then treat any
   part of it as verified.
