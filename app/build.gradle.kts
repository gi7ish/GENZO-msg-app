plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.genzo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.genzo.app"
        minSdk = 26          // SQLCipher-android supports API 23+; 26 chosen for modern Keystore behavior
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // left off deliberately for this MVP; see docs/ANDROID_BUILD_STATUS.md
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // libsignal-client's Maven artifact bundles native libraries for
        // desktop platforms (Linux/Windows/macOS) alongside the Android
        // .so files, because the same artifact is also used server-side.
        // libsignal-android supplies the actual Android-targeted natives.
        // Per the signalapp/libsignal README's own Android integration
        // notes: exclude the desktop natives so they don't inflate (or
        // conflict with) the APK. If a future libsignal-client release
        // changes these paths, `unzip -l` the AAR/JAR and adjust.
        resources {
            excludes += setOf(
                "/META-INF/native-image/**",
                "**/libsignal_jni.dylib",
                "**/signal_jni.dll",
                "**/libsignal_jni.so", // desktop x86_64 .so under a non-Android jni path, NOT the android jniLibs/<abi>/ one
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // --- Signal protocol crypto core (real, official, same tag v0.73.0
    //     as the Rust vertical slice — NOT reimplemented here) ---
    implementation("org.signal:libsignal-client:0.73.0")
    implementation("org.signal:libsignal-android:0.73.0")

    // --- Local encrypted database ---
    implementation("net.zetetic:sqlcipher-android:4.18.0")
    implementation("androidx.sqlite:sqlite:2.7.0")

    // --- Relay HTTP client (mirrors device/src/net.rs's role; here we use
    //     a real, mainstream client rather than hand-rolling HTTP parsing,
    //     since Android has normal Maven access to OkHttp) ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // --- Jetpack Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    // NOTE: Icons.Filled.Add / .Send used in the UI are believed to be in
    // material-icons-core's default set; if Gradle can't resolve either
    // one, swap this for androidx.compose.material:material-icons-extended
    // (much larger artifact, superset of core) — unverified here, no Maven
    // access in this sandbox (see /docs/ANDROID_BUILD_STATUS.md).
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
