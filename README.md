# GENZO — Android MVP (Step 9)

> **Want to just get this running with no local Android Studio/Rust setup?**
> Follow `docs/GET_IT_RUNNING.md` — deploys the relay to Render.com and
> builds the APK via GitHub Actions, both from the browser.

> **FROZEN at Step 9.** No new features until external verification on a
> real Windows + Android Studio environment confirms a real build and a
> real two-device encrypted message round trip. See
> `/docs/WINDOWS_VERIFICATION_CHECKLIST.md` for the exact checklist to work
> through, and report results before any further development resumes.

**Read `/docs/ANDROID_BUILD_STATUS.md` first.** This project has not been
compiled or run in the sandbox that authored it — no Android SDK, no
Gradle, no Maven access were available there. Everything below is how to
build and run it for real, on a machine that has those things.

## What this is

A real Android Studio project (Kotlin + Jetpack Compose) that talks to the
exact same Rust relay from the vertical slice (Step 8), using the real
`org.signal:libsignal-client`/`libsignal-android` JNI bindings — same
libsignal version (`v0.73.0`) as the Rust side, same X3DH/Double Ratchet
flow, ported not reimplemented.

## Project structure

```
genzo-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   (points at Gradle 8.9 — jar itself not fetchable here, see build status doc)
├── app/
│   ├── build.gradle.kts                        dependencies + versions (see below)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/                             minimal: theme, strings, network security config
│       │   └── java/com/genzo/app/
│       │       ├── GenzoApplication.kt           manual DI: builds the Keystore→LocalStore→RelayApi→Repository chain
│       │       ├── MainActivity.kt
│       │       ├── crypto/DeviceIdentity.kt       ★ the only file touching key material — calls libsignal, nothing else
│       │       ├── network/                       WireModels.kt, RelayApi.kt, RelayConfig.kt
│       │       ├── data/                           LocalStore.kt (SQLCipher), KeystoreKeyProvider.kt (Android Keystore)
│       │       ├── domain/                         Models.kt, GenzoRepository.kt (UI-safe boundary)
│       │       └── ui/                             onboarding/, conversations/, chat/, common/, GenzoNavHost.kt
│       ├── test/java/com/genzo/app/                WireModelsSerializationTest.kt (plain JVM, no device needed)
│       └── androidTest/java/com/genzo/app/         CryptoRoundTripTest.kt, LocalStoreTest.kt (need a device/emulator)
└── docs/ANDROID_BUILD_STATUS.md                 ★ read this first
```

## Architecture: UI → domain → crypto/network/data

- **UI** (`ui/`): Compose screens + ViewModels. Only ever sees
  `domain.ChatMessage` / `domain.Conversation` / `domain.ConnectionStatus`.
- **domain** (`domain/GenzoRepository.kt`): the only class holding a
  `DeviceIdentity` reference. Orchestrates crypto + network + storage;
  never returns key material or ciphertext to its callers.
- **crypto** (`crypto/DeviceIdentity.kt`): the only file that calls
  libsignal directly. Private key material never leaves this class.
- **network** (`network/`): relay HTTP client + wire DTOs.
- **data** (`data/`): SQLCipher local store + Android Keystore wrapping key.

## Exact dependencies and versions

| Component | Version | Why |
|---|---|---|
| Kotlin | 2.0.21 | pairs with AGP 8.7.2, well past the Compose-compiler-plugin migration |
| AGP (`com.android.application`) | 8.7.2 | deliberately not AGP 9.x (Jan 2026, breaking DSL changes) — see root `build.gradle.kts` |
| Gradle | 8.9 | matches AGP 8.7.2's supported range |
| compileSdk / targetSdk | 35 | current at authoring time |
| minSdk | 26 | SQLCipher-android supports 23+; 26 chosen for Keystore behavior |
| `org.signal:libsignal-client` | **0.73.0** | identical tag to the Rust vertical slice — confirmed present on Maven Central |
| `org.signal:libsignal-android` | **0.73.0** | ditto; both required together per libsignal's own Android integration notes |
| `net.zetetic:sqlcipher-android` | 4.18.0 | current artifact name/API (not the older `android-database-sqlcipher`) |
| `androidx.sqlite:sqlite` | 2.7.0 | sqlcipher-android's declared runtime dependency |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | relay HTTP client |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | wire DTO (de)serialization |
| Compose BOM | 2024.12.01 | stable, Kotlin-2.0-era |
| `androidx.navigation:navigation-compose` | 2.8.4 | screen nav |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | ViewModels |

Full list in `app/build.gradle.kts`, with inline notes on the two entries
that carry extra risk (SQLCipher call signature, Compose icons package —
see build status doc).

## Build command

```bash
cd genzo-android
./gradlew assembleDebug
```
(First run: let Android Studio generate the Gradle wrapper jar if it's
missing — see build status doc.)

## Test commands

```bash
./gradlew test                    # WireModelsSerializationTest — no device needed
./gradlew connectedAndroidTest     # CryptoRoundTripTest + LocalStoreTest — needs an emulator/device running
```

## How to run the relay

Same relay as the Rust vertical slice (Step 8), unchanged:
```bash
cd vertical-slice          # the Step 8 project
RELAY_ADDR=127.0.0.1:8765 cargo run -p relay
```

## How to run two Android clients against it

**Simplest path — two emulators on the same machine as the relay:**

1. Start the relay as above (binds `127.0.0.1:8765` on your dev machine).
2. Launch two Android emulator instances (Android Studio → Device Manager
   → run two AVDs simultaneously, or `emulator -avd <name> -port 5554` /
   `-port 5556` from the command line for two independent instances).
3. Install the app on both (`./gradlew installDebug`, or Android Studio's
   Run with a device-selection dropdown, once for each emulator).
4. Both emulators reach the relay at `10.0.2.2:8765` by default (each
   emulator's private alias for its own host machine's localhost — see
   `network/RelayConfig.kt` and `res/xml/network_security_config.xml`). No
   config change needed for this same-machine setup.
5. On emulator 1: onboard as `alice`. On emulator 2: onboard as `bob`.
6. On `alice`'s conversation list, tap **+**, enter `bob`, tap **Start** —
   this fetches bob's bundle and runs X3DH.
7. Send a message from `alice`. Within a couple of seconds (the chat
   screen's poll interval), it should appear on `bob`'s device, decrypted.
   Reply from `bob`; it should appear back on `alice`'s device.

**Two physical devices (or a device + emulator) on the same Wi-Fi:**
Change `RelayConfig.baseUrl` to the relay machine's LAN IP (e.g.
`http://192.168.1.50:8765`), add that exact host to
`res/xml/network_security_config.xml`'s cleartext exception list, rebuild,
and install on both devices.

## What was actually tested (this response)

- Nothing executed. Every claim of correctness above is "written against
  verified APIs," not "ran and passed." See `/docs/ANDROID_BUILD_STATUS.md`
  for the exact commands to actually verify each layer once you have Android
  Studio.

## What remains untested

Everything: compilation, unit tests, instrumented tests, and — most
importantly — the real two-device flow through the relay. Do not treat any
part of this as working until you've run it yourself.

## Security limitations (in addition to everything already documented in
the Rust vertical slice's `docs/KNOWN_LIMITATIONS.md`, which still applies)

- Session/ratchet state is in-memory only (`InMemorySignalProtocolStore`)
  — killing the app loses the ability to continue an existing session;
  only already-received/sent messages persisted via `LocalStore` survive.
- The Keystore-backed wrapping key has `setUserAuthenticationRequired(false)`
  — i.e. not gated behind biometric/device-credential auth yet. See the
  comment in `KeystoreKeyProvider.kt` for what changes to harden this.
- No StrongBox guarantee — falls back to normal hardware-backed Keystore
  when StrongBox isn't available (most emulators), which is expected and
  fine, but means the security level varies by device.
- No certificate pinning; TLS (once you point this at a real
  `https://` relay instead of the local dev HTTP one) relies on the
  platform trust store as-is.
- No rate limiting, no attacker-resistant registration flow — same
  relay-side limitations as the Rust vertical slice.
- This is not production-ready, is not audited, and none of the crypto
  code paths in this Android layer have been exercised even once.
