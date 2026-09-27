// Root build file. Plugin versions declared here (`apply false`) and actually
// applied in app/build.gradle.kts.
//
// NOTE ON VERSION CHOICES: these are pinned to a combination that was stable
// and well-documented as of this project's authoring (mid-2025-era AGP 8.x /
// Kotlin 2.0.x), deliberately NOT the bleeding-edge AGP 9.x line (which
// shipped Jan 2026 with breaking DSL changes — see AGP 9 release notes).
// This project has never been opened in Android Studio or built (see
// /docs/ANDROID_BUILD_STATUS.md) — an older, longer-documented, widely-used
// version combination is a safer starting point to debug from than the
// newest possible one. If Android Studio's Upgrade Assistant offers to move
// you to AGP 9, that's a deliberate, separate decision to make once the
// project is confirmed building as-is, not something to do blind.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
