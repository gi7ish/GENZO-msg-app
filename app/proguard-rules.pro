# Minification is off for this MVP (see app/build.gradle.kts). If enabled later:
# libsignal-client's Java classes are called into from native (JNI) code by
# name/signature, so they must not be renamed/stripped. Signal's own
# libsignal-android artifact ships consumer ProGuard rules for this, but
# verify with `./gradlew :app:assembleRelease` + inspecting the mapping file
# before shipping a minified build — do not assume this comment is sufficient
# on its own.
-keep class org.signal.libsignal.** { *; }
