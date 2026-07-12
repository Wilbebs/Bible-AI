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

## Design direction

Adopting an iOS-26-style "liquid glass" aesthetic, established first on the
Gemini study-chat bubble (`ui/reader/ChatBubble.kt`) and captured as reusable
tokens in `ui/theme/Glass.kt` for future screens to pick up:

- **Glass panels**: translucent gradient fill + bright rim-light border +
  soft shadow on a large (28dp) rounded shape (`Glass.panelShape`,
  `Glass.panelBrush()`, `Glass.panelBorderBrush()`), instead of an opaque
  Material `Card`. No backdrop blur is used behind the panel — the verses
  stay fully readable while the bubble is open; the panel's own
  translucency/shadow is what reads as "floating above" the content.
- **Motion**: the chat bubble enters/exits with `AnimatedVisibility`
  (fade + scale) in `ReaderScreen.kt`, modeled on iOS sheet presentation.
- **Gemini glow**: the follow-up input is a pill-shaped glass field ringed by
  a still (non-rotating) border whose color slowly cycles through Google's
  brand colors (`Modifier.geminiGlowBorder()` / `Glass.colorAtCyclePosition()`),
  referencing the Gemini/Bard loading indicator's palette without the motion.
- **Typewriter reveal**: assistant replies and word definitions stream in
  incrementally (`rememberTypewriterProgress` / `TypewriterText` in
  `ui/theme/Glass.kt`) instead of appearing all at once — word-by-word for
  tappable text (so tap targets stay intact), character-by-character for
  plain text.
- **Bubble dismissal**: tapping outside the panel calls the same logic as
  "Delete chat" collapse-to-original-translation (`onStartOver`) rather than
  closing it — only the explicit "✕" fully dismisses the bubble
  (`ReaderScreen.kt`).

This is the first pass — the reader's top bar and verse list still use
default Material styling and are natural next candidates if the glass look
should extend further.

## Known environment limitation: Gemini network errors on-device

If a device/emulator shows "Unable to resolve host 'generativelanguage.googleapis.com'"
in the chat bubble, that's a DNS/network failure on that specific device (no
internet access reaching Google's API), not an app bug — `INTERNET` permission
is declared and the request code is unchanged. Failed AI responses are now
flagged as errors (`ChatMessage.isError`) and rendered as plain, non-tappable
text so a failure message can't itself be tapped and looked up as if it were
real verse content.

## User preferences

None recorded yet.
