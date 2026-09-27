# Android Build Status — READ THIS BEFORE TRUSTING ANYTHING ELSE HERE

## The honest summary

**This project has never been opened in Android Studio, never been built,
never been run, and its tests have never executed.** Every Kotlin file was
written by hand against APIs verified from source or documentation, then
statically sanity-checked (package consistency, brace balance, import
correctness) — not compiled. Treat it as a strong starting point for a real
build, not as working software.

## Concrete evidence, not a guess

Checked directly in this sandbox before writing any code:

```
$ which sdkmanager adb gradle kotlinc
sdkmanager: not found
adb: not found
gradle: not found
kotlinc: not found

$ java -version
openjdk version "21.0.10" 2026-01-20   # JDK present; nothing else Android-related is

$ curl -sI https://dl.google.com
HTTP/2 403, x-deny-reason: host_not_allowed
$ curl -sI https://maven.google.com
HTTP/2 403, x-deny-reason: host_not_allowed
$ curl -sI https://repo.maven.apache.org/maven2/
HTTP/2 403, x-deny-reason: host_not_allowed
$ curl -sI https://services.gradle.org/distributions/
HTTP/2 403, x-deny-reason: host_not_allowed
```

No Android SDK, no Gradle, no `adb`, and the network sandbox's domain
allowlist blocks every host that would let us fetch one (Google's Maven
repo, Maven Central, and the Gradle distribution server are all explicitly
denied — same allowlist documented in the Rust vertical slice's Step 8).
There is no workaround available inside this sandbox; building an actual
`.apk` here would require infrastructure access this environment does not
grant.

## What WAS verified (real, not assumed)

- **`org.signal:libsignal-client:0.73.0` and `org.signal:libsignal-android:0.73.0`
  are both genuinely published on Maven Central**, confirmed by fetching
  `repo1.maven.org/maven2/org/signal/libsignal-client/` and
  `.../libsignal-android/` directly — so this Android client can depend on
  the *exact same* libsignal version as the proven Rust vertical slice, not
  an approximation.
- **The Java/Kotlin API calls in `crypto/DeviceIdentity.kt`** (`IdentityKeyPair.generate()`,
  `SessionBuilder(store, address).process(bundle)`, `SessionCipher.encrypt/decrypt`,
  `PreKeyBundle`'s constructor parameter order, `SignedPreKeyRecord`/`KyberPreKeyRecord`
  constructors, `InMemorySignalProtocolStore`) were checked against the actual
  Java source at `java/shared/java/org/signal/libsignal/protocol/` in the
  `signalapp/libsignal` repo at tag `v0.73.0` (the same clone used for the
  Rust vertical slice), not written from memory. This is the same diligence
  applied to the Rust `device` crate in Step 8.
- **`net.zetetic:sqlcipher-android:4.18.0`** is the current, correctly-named
  artifact (the older `net.zetetic:android-database-sqlcipher` name/API is
  superseded) — confirmed via its published POM on Maven Central.
- Current stable **Compose BOM (`2024.12.01`-era)**, and the fact that AGP
  moved to a new major version (9.0, Jan 2026) with breaking DSL changes —
  checked via web search, which is why this project deliberately targets
  the older, longer-documented AGP 8.7.2 / Kotlin 2.0.21 / Gradle 8.9
  combination rather than the newest possible one (see root `build.gradle.kts`
  comment).

## What was NOT and could NOT be verified here

- That the project actually compiles. Not one `.kt` file in this project
  has been run through `kotlinc` or the Kotlin Gradle plugin.
- The exact `net.zetetic:sqlcipher-android` Kotlin call signature used in
  `data/LocalStore.kt` (`SQLiteOpenHelper`'s constructor arity,
  `SQLiteDatabase.openOrCreateDatabase`'s parameter list) — written from the
  library's public GitHub README usage example, not from its javadoc/source,
  because Maven access to fetch the artifact itself is blocked here.
- Whether `Icons.Filled.Add`/`Icons.Filled.Send` resolve from
  `material-icons-core` or require `material-icons-extended` — flagged
  in-line in `app/build.gradle.kts`.
- The Gradle wrapper JAR itself. `gradle/wrapper/gradle-wrapper.properties`
  points at Gradle 8.9, but the actual `gradle-wrapper.jar` binary that
  makes `./gradlew` work could not be downloaded here (same
  `services.gradle.org` block above). **Run `gradle wrapper --gradle-version 8.9`
  once** (with a real Gradle install, or let Android Studio regenerate it
  automatically on first project open) before using `./gradlew`.
- Whether the Double-Ratchet/X3DH behavior actually matches the Rust tests'
  assertions in a live JVM — extremely likely, since it's the same
  underlying Rust `rust/protocol` core reached through a different (but
  equally official) binding, but "extremely likely" is not "verified,"
  which is exactly why `CryptoRoundTripTest` exists — run it before
  believing it.
- The real two-device flow: **Android Device A → Rust relay → Android
  Device B with an actual message transmitted and decrypted has NOT
  happened.** This is the single most important open item. See below for
  exactly how to do it once you have a real Android environment.

## How to actually get to "it works," on your own machine

1. Install Android Studio (which bundles a compatible JDK, SDK, and
   Gradle) — this alone resolves every blocker listed above, since your
   machine has normal internet access to Google's Maven, Maven Central,
   and Gradle's distribution server.
2. Open this project (`genzo-android/`) in Android Studio. Let it sync;
   it will prompt to generate the Gradle wrapper jar if missing.
3. `./gradlew assembleDebug` — this is the actual, real first compile.
   Fix whatever it reports (expect the SQLCipher call signature and the
   Compose icons import to be the most likely first issues, per the list
   above).
4. `./gradlew test` — runs `WireModelsSerializationTest` (no device needed).
5. Start an emulator (or connect a device), then
   `./gradlew connectedAndroidTest` — runs `CryptoRoundTripTest` and
   `LocalStoreTest` for real, against real libsignal + real SQLCipher
   native code.
6. Follow the two-device instructions in this project's top-level
   `README.md` to actually run Device A and Device B against the Rust
   relay from the vertical slice and watch a real encrypted message cross
   the wire.

Only after step 6 succeeds should anyone treat "Android Device A → relay →
Android Device B" as demonstrated rather than designed.
