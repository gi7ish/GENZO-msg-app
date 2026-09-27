# Get It Running — no Android Studio, no local Rust, no command line

This is the complete path from "I have this folder" to "GENZO is installed
on two phones and they can message each other," using only GitHub's and
Render's websites. Two things get built, in this order:

1. **The relay** (deployed to Render.com — a small always-on server both
   phones will talk to over the real internet)
2. **The Android app** (built by GitHub Actions, into a downloadable APK)

**Nothing in this doc has been executed.** Every step is the best-known
correct sequence for these platforms; the first time each step actually
runs, on your accounts, is its first real test. If any step errors, paste
the exact error back and it'll get fixed.

---

## Part 1 — Get everything into one GitHub repo

1. Go to github.com, sign in (or create a free account).
2. Click **+ → New repository**. Name it `genzo` (or anything). Keep it
   **Private** if you'd rather not have it public — everything here still
   works. Don't initialize with a README (this folder already has one).
3. On the new repo's page, use **"uploading an existing file"** (or drag
   and drop) and upload **everything inside this folder** — `app/`,
   `relay-server/`, `.github/`, `settings.gradle.kts`, all of it — so that
   `settings.gradle.kts` and `relay-server/` sit at the **top level** of
   the repo, not nested inside another folder.
   (If GitHub's web uploader balks at the number of files/folders in one
   go, GitHub Desktop — a free point-and-click app, no terminal — handles
   a folder-with-subfolders upload in one step instead.)

---

## Part 2 — Deploy the relay (Render.com, free tier)

1. Go to render.com, click **Get Started**, sign up with your GitHub
   account (this lets Render see your repos without extra passwords).
2. **New +** → **Web Service**.
3. Pick the `genzo` repo you just created.
4. Render will ask for a few settings:
   - **Root Directory:** `relay-server`
   - **Runtime:** Docker (Render should auto-detect the `Dockerfile` once
     Root Directory is set to `relay-server`)
   - **Instance Type:** Free
5. Click **Deploy Web Service**. Watch the build log — this is the actual
   first compile of the relay, for real, on a machine with normal internet
   access. It should end with something like "your service is live" and a
   URL such as `https://genzo-relay-xxxx.onrender.com`.
6. **Copy that URL.** You'll need it in the next part.

   Sanity check it worked: open `https://<your-url>/v1/health` in a
   browser tab. Expect to see `{"ok":true}`. If you see an error page
   instead, the relay didn't start — check Render's log tab for what it
   printed.

   Note: Render's free tier spins a service down after periods of
   inactivity and takes a few seconds to wake back up on the next request
   — expect the first message after a quiet period to be slow, not broken.

---

## Part 3 — Point the app at your deployed relay

1. Back in your GitHub repo, open
   `app/src/main/java/com/genzo/app/network/RelayConfig.kt` (GitHub lets
   you edit text files right in the browser — click the pencil icon).
2. Change the one line:
   ```kotlin
   var baseUrl: String = "http://10.0.2.2:8765"
   ```
   to your Render URL, with `https://` and no trailing slash, e.g.:
   ```kotlin
   var baseUrl: String = "https://genzo-relay-xxxx.onrender.com"
   ```
3. Commit directly to `main`. This alone triggers Part 4.

---

## Part 4 — Build the APK (GitHub Actions, automatic)

1. Committing to `main` automatically starts the `Build GENZO APK` workflow
   (`.github/workflows/build-apk.yml`). Watch it under your repo's
   **Actions** tab.
2. This is the real first compile of the Android app — expect it to
   possibly hit one of the issues already anticipated in
   `docs/ANDROID_BUILD_STATUS.md` / `docs/WINDOWS_VERIFICATION_CHECKLIST.md`
   §14 (e.g. the SQLCipher call signature, or the Compose icons artifact).
   If the log shows a red ✗, copy the error text back here.
3. On success, open the finished workflow run → **Artifacts** section at
   the bottom → download **genzo-debug-apk** (a `.zip` containing the
   actual `.apk` file).

---

## Part 5 — Install on two phones

1. Unzip the downloaded artifact to get `app-debug.apk`.
2. Get that file onto each phone however's easiest (email it to yourself,
   a cloud-drive link, a USB cable, etc.) and tap it on each phone.
3. Android will warn about installing from outside the Play Store — allow
   it for this file specifically (Settings prompt will guide you the first
   time).
4. Open the app on both phones. On phone 1: onboard as e.g. `alice`. On
   phone 2: onboard as `bob`. Both need real internet access (mobile data
   or Wi-Fi) — no emulator aliasing involved now, this is real internet to
   your Render URL.
5. On `alice`'s conversation list: **+** → type `bob` → **Start**.
6. Send a message. It should reach `bob`'s phone within a few seconds
   (longer on the very first message if Render's free tier had spun the
   service down — see Part 2's note). Reply from `bob`; it should return
   to `alice`.

That's the actual, real, two-phone, internet-routed, end-to-end test —
the same milestone `docs/WINDOWS_VERIFICATION_CHECKLIST.md` §13 describes,
just reached without installing Android Studio or Rust on your own machine.

## If something breaks

Every step above produces a visible log (Render's deploy log, GitHub
Actions' build log, or an on-screen Android error). This project was
written carefully but has never been run end-to-end by anyone, including
whoever wrote it — paste back whatever the log actually says at the point
it stops, and that's exactly the information needed to fix it.
