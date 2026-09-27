# Windows External Verification Checklist

Project frozen at Step 9 (Android MVP). No new features until this
checklist's step 13 succeeds for real. Nothing below has been executed by
the assistant that wrote it — see `/docs/ANDROID_BUILD_STATUS.md` for why
(no Android SDK / Maven / Gradle-distribution access in that sandbox). This
is the reference document for you to execute and validate on your own
machine.

## 1. Android Studio version
**2024.2.1 ("Ladybug") or newer** — any current version qualifies too.
Source: Google's own AGP↔Studio compatibility table
(developer.android.com/build/releases/gradle-plugin) lists Ladybug
(2024.2.1) as the first Studio version supporting AGP 8.7.x, which is what
this project is pinned to; every Studio release since then extends that
supported range rather than narrowing it.

## 2. Required JDK version
**JDK 17**, confirmed by Google's official docs: "Android Gradle Plugin
version 8.x requires JDK 17." In practice you likely don't need to install
one yourself — Android Studio bundles its own JDK 17+ (JetBrains Runtime)
and uses it by default (Settings → Build Tools → Gradle → "Gradle JDK").
Only install a separate JDK if you plan to run `gradlew`/`cargo` from a
plain terminal outside Android Studio and want it on `PATH`.

## 3. Required Android SDK version
**Android SDK Platform 35** (compileSdk = targetSdk = 35 in
`app/build.gradle.kts`). Install via Android Studio → More Actions → SDK
Manager → SDK Platforms tab → check "Android 15.0 (\"VanillaIceCream\")" /
API 35.

## 4. Required SDK Build Tools version
Not explicitly pinned in `app/build.gradle.kts` (no `buildToolsVersion`
override), so Android Studio will select its default for API 35 — expect
**35.0.0** (SDK Manager → SDK Tools tab → "Android SDK Build-Tools" →
ensure a 35.x entry is checked). If Gradle sync complains about a missing
specific version, install whichever exact one it names.

## 5. Required NDK version
**None.** This project has zero native code of its own. `libsignal-android`
and `sqlcipher-android` are consumed as prebuilt AARs (already containing
compiled `.so` files per ABI) from Maven — no local native compilation, no
NDK needed to build the Android app.

## 6. Do you need Rust/Cargo installed locally?
**Yes — but only to run the relay, not for the Android app itself.** The
relay is a separate project (`vertical-slice/`, from Step 8), built with
`cargo`. The Android app depends only on Maven artifacts; it does not call
into your local Rust toolchain in any way.

## 7. Do you need to build any native libraries manually?
**No, for either half.**
- Android app: prebuilt AARs, as above — nothing to compile.
- Relay: confirmed by inspecting its actual dependency graph —
  `relay` depends only on `common` + `serde` + `serde_json`; `common`
  depends only on `serde`. None of these have native build steps
  (no `cmake`, `protoc`, or C/C++ compiler needed). Plain `cargo build`
  compiles it directly. (This is narrower than the full vertical-slice
  workspace — `device`/`local_store` DO need the native toolchain
  documented in the Step 8 report, but you don't need those crates at all
  anymore now that Android is the real client.)
- You do still need the standard Rust-on-Windows prerequisite that has
  nothing to do with this project specifically: the MSVC linker, via
  Visual Studio Build Tools' "Desktop development with C++" workload (or
  the smaller standalone "Build Tools for Visual Studio"). This is
  required to link *any* Rust binary on Windows, not specific to `relay`.

## 8. Exact steps to start the Rust relay
```powershell
# one-time setup
winget install Rustlang.Rustup          # or download from rustup.rs
rustup default stable
# also required once, if not already installed: Visual Studio Build Tools
# with the "Desktop development with C++" workload (for the MSVC linker)

# every time you want to run the relay
cd vertical-slice
$env:RELAY_ADDR = "127.0.0.1:8765"
cargo run -p relay
```
Expected output:
```
privacy-messenger relay listening on http://127.0.0.1:8765
routes: POST /v1/register  GET /v1/prekey_bundle/:user_id  POST /v1/messages/:user_id  GET /v1/messages/:user_id  GET /v1/health
```
Leave this running in its own terminal for the rest of the test.

## 9. Exact Android Studio steps to build/install the app
1. **File → Open** → select the `genzo-android` folder → Open.
2. Let Gradle sync run. If it reports a missing Gradle wrapper jar (see
   §14), accept Android Studio's offer to fix it, or set
   Settings → Build Tools → Gradle → "Use Gradle from: 'Specified location'"
   pointing at a locally installed Gradle 8.9+.
3. If SDK Platform 35 / Build-Tools aren't installed, Studio will prompt —
   accept, or install manually via SDK Manager (see §3–4).
4. **Build → Make Project** (Ctrl+F9) — this is the actual first real
   compile of this project. Resolve whatever it reports; check §14 first.
5. Once it builds clean: **Run → Run 'app'** (or the green ▶ button) with
   a target device selected — this installs and launches it.

## 10. Exact steps to run two emulator/device instances
1. **Tools → Device Manager → Create Device** — create two separate AVDs
   (e.g. "Pixel_8_A" and "Pixel_8_B"), any API 26+ system image (API 35
   recommended to match compileSdk).
2. Launch both from Device Manager (each opens its own emulator window).
3. In Android Studio's device dropdown (top toolbar, next to Run), you can
   only target one at a time per click — click Run once with "Pixel_8_A"
   selected, then again with "Pixel_8_B" selected. Both installs land on
   their respective emulator.
4. Both emulators reach the relay at `10.0.2.2:8765` by default with no
   config changes (see `network/RelayConfig.kt` — each emulator's private
   NAT gives it that address as an alias for your host machine's
   localhost, where the relay is listening per step 8 above).

## 11. How Device A discovers Device B
**There is no contact-discovery mechanism in this MVP** — this is by
design, matching the data-minimization goal (no phone-number/contacts
upload), and is a documented limitation, not a bug to report. Discovery
means: Bob tells Alice his chosen username through some other channel
(verbally, a chat app you already use, whatever) — the same as knowing
someone's Signal/WhatsApp phone number today. On Alice's device: conversation
list → **+** → type Bob's exact username → **Start**. This calls
`GET /v1/prekey_bundle/bob` on the relay and runs X3DH; it does not search,
browse, or guess usernames.

## 12. How to verify the message is actually E2EE
No code changes needed — use what's already there:
1. **Inspect the wire payload directly.** After Alice sends but before Bob's
   app polls it (you have ~2 seconds — the poll interval in
   `ChatViewModel.kt` — or just pause Bob's emulator momentarily), run:
   ```powershell
   curl http://127.0.0.1:8765/v1/messages/bob
   ```
   The JSON response's `ciphertext_b64` field must be unreadable base64 —
   not the plaintext Alice typed, not recognizably related to it. This is
   the relay's actual view of the message; if you can read your plaintext
   here, something is badly wrong.
2. **Inspect what got registered.** `curl http://127.0.0.1:8765/v1/prekey_bundle/bob`
   (before Alice consumes bob's one-time prekey) shows only public key
   material — confirm nothing resembling a private key or raw secret is
   present.
3. **Watch the relay's own terminal output** (step 8) for the whole test —
   it only ever prints its startup banner. It has no code path that logs
   message content (see `relay/src/lib.rs` from Step 8) because it's never
   handed anything but ciphertext to begin with.
4. Optional, more rigorous: run the relay under a local proxy (e.g.
   Wireshark on the loopback interface, since it's plaintext HTTP by
   design for this local dev setup) and confirm the same thing at the
   packet level, not just via `curl`.

## 13. Expected result of the first successful test
- Both emulators complete onboarding without error (identity generated,
  `/v1/register` returns `{"ok":true}`).
- Alice's "Start" action on Bob's username completes without error
  (X3DH succeeds) — no exception, chat screen opens.
- A message typed on Alice's device appears on Bob's device, verbatim,
  within a few seconds (poll interval), and is not visible in cleartext at
  the relay per §12.
- A reply typed on Bob's device appears back on Alice's device the same way.
- Both devices' `CryptoRoundTripTest` and `LocalStoreTest`
  (`./gradlew connectedAndroidTest`) pass, and `WireModelsSerializationTest`
  (`./gradlew test`) passes.
- This is the point at which "Android Device A → relay → Android Device B,
  encrypted message sent and decrypted" becomes a demonstrated fact instead
  of a designed-but-unverified claim.

## 14. Known build issues that may occur
(All flagged in advance in `/docs/ANDROID_BUILD_STATUS.md`; consolidated here.)

| Symptom | Likely cause | Fix |
|---|---|---|
| Gradle sync fails immediately, "wrapper jar missing/corrupt" | This project ships `gradle-wrapper.properties` but not the binary `gradle-wrapper.jar` (needs network access the authoring sandbox didn't have) | Accept Android Studio's auto-fix prompt, or run `gradle wrapper --gradle-version 8.9` once with any local Gradle install, or switch Settings → Gradle to a local Gradle distribution |
| `Unresolved reference: SQLiteOpenHelper` constructor args, or similar in `LocalStore.kt` | The exact `net.zetetic:sqlcipher-android` constructor signature was written from its README example, not its javadoc (unreachable from the authoring sandbox) | Check the installed AAR's actual `SQLiteOpenHelper`/`SQLiteDatabase.openOrCreateDatabase` signatures in Android Studio (Ctrl+click to navigate) and adjust the call |
| `Unresolved reference: Send` or `.Add` (Compose icons) | Uncertain whether these resolve from `material-icons-core` or need `material-icons-extended` | Swap the `material-icons-core` dependency in `app/build.gradle.kts` for `material-icons-extended` |
| Duplicate/unexpected native libraries in the built APK, or a packaging conflict during merge | `libsignal-client`'s Maven artifact bundles desktop (Linux/Windows/macOS) native libs alongside Android ones, since the same artifact serves both; the `packaging { resources { excludes += ... } }` block in `app/build.gradle.kts` targets this but its exact exclude paths are unverified against the actual 0.73.0 AAR contents | Run `unzip -l` on the resolved `libsignal-client` jar (Gradle caches it under `~/.gradle/caches/...`) to see its real internal paths and adjust the excludes list to match |
| AGP upgrade prompt on project open | Your installed Android Studio may default-suggest AGP 9.x | Decline for now — build and verify against the pinned 8.7.2 first; upgrading AGP is a deliberate later decision, not something to do blind on an unverified project |
| `cargo build -p relay` fails to link on Windows | Missing MSVC linker | Install Visual Studio Build Tools with "Desktop development with C++" |
| App can't reach the relay ("Connection refused" / timeout in the app) | Emulator not actually mapping `10.0.2.2` correctly, relay not listening on all interfaces, or Windows Firewall blocking the port | Confirm `curl http://127.0.0.1:8765/v1/health` works from your Windows host first; check `RELAY_ADDR` was set to `127.0.0.1:8765` (not `localhost`, which can resolve differently); allow the port through Windows Defender Firewall if prompted |
| Cleartext traffic blocked | `res/xml/network_security_config.xml` only permits `10.0.2.2`/`localhost`/`127.0.0.1` | If testing on real hardware instead of emulators pointed at a LAN IP, add that exact host to the config (see its comments) |
