# Bible Translate App

A trilingual (English / Spanish / Portuguese) Bible reader for Android with
tap-to-translate and a scoped AI study-chat bubble. Imported from GitHub —
see `README.md` for the full product/architecture writeup (translation
strategies, data layer, UI layer, known limitations).

## Stack

Kotlin, Jetpack Compose (Material 3), Gradle, min SDK 28, target/compile SDK
35. No backend server. This is a native Android app, not a web app — there
is no browser preview in Replit for it.

## Building on Replit

This environment has no Android emulator or connected device, so "running"
here means producing a debug APK, not launching the UI. To build:

```bash
export ANDROID_HOME=/home/runner/workspace/.android-sdk
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Pull that file down and
install it on a device/emulator (e.g. via `adb install` or Android Studio)
to actually use the app.

### Environment setup notes

- **Android SDK**: not available as a Replit/Nix module, so the command-line
  tools + `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`
  were downloaded directly from Google into `.android-sdk/` (gitignored,
  ~450MB). `local.properties` points `sdk.dir` at it.
- **JDK**: the default Replit Java module (GraalVM 22.3 / JDK 19) fails
  Android's `jlink`-based system-modules step. Installed `jdk17` (OpenJDK
  17.0.15) via Nix instead and pinned Gradle to it with
  `org.gradle.java.home` in `gradle.properties`. If that Nix store path ever
  goes stale (e.g. after a channel update), re-resolve it with
  `find /nix/store -maxdepth 1 -iname "openjdk-17*"` and update the path.
- **API keys**: `app/build.gradle.kts` reads `gemini.api.key` /
  `translate.api.key` from `local.properties` as before, now falling back to
  the `GEMINI_API_KEY` / `TRANSLATE_API_KEY` Replit Secrets when those
  properties aren't set. Both secrets are already configured in this repl.

No workflow is configured — there's no long-running server/port for this
project to bind to.

## User preferences

None recorded yet.
