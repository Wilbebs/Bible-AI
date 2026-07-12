---
name: Building Android/Gradle apps on Replit
description: How to get an Android Gradle project compiling in the Replit NixOS container, and its limits.
---

There is no Android SDK Nix/Replit module (`installSystemDependencies` has no
`androidsdk*` package in the rippkgs index). To build an Android app:

1. Install a JDK via `installSystemDependencies({ packages: ["jdk17"] })`.
   The default Replit Java module (`java-graalvm22.3`, GraalVM/JDK 19) fails
   Android's Gradle build at the `androidJdkImage` transform — its `jlink`
   can't process `core-for-system-modules.jar`. Must use a plain JDK 17
   instead, pointed to via `org.gradle.java.home` (find the exact Nix store
   path with `find /nix/store -maxdepth 1 -iname "openjdk-17*"`) — but set
   that property in the user-level `~/.gradle/gradle.properties`, NOT the
   project's committed `gradle.properties`. A hardcoded Nix store path in the
   committed file breaks the build immediately on any other machine
   (Android Studio, CI, another dev) with "Java home supplied is invalid".
2. Download the Android command-line tools zip directly from
   `dl.google.com/android/repository/commandlinetools-linux-*_latest.zip`
   into the project (e.g. `.android-sdk/`, gitignored — it's ~450MB+), then
   run its `sdkmanager` with `yes |` piped in to accept licenses, installing
   `platform-tools`, `platforms;android-<N>`, `build-tools;<N>.0.0` matching
   the project's `compileSdk`. Point `local.properties`' `sdk.dir` at it.
3. Also install `gradle` via `installSystemDependencies` if you want a
   system `gradle`, but the project's own `./gradlew` wrapper works fine
   once Java + `ANDROID_HOME`/`local.properties` are set.

**Why:** Replit's container has no Android emulator or device, so there is
no way to actually run/preview the app UI — only compile it
(`./gradlew assembleDebug`) and hand the resulting APK off to a real device
or emulator elsewhere. Don't promise a "running app preview" for native
Android projects; the deliverable in Replit is a successful build.

**How to apply:** Any time a fresh Android/Gradle project is imported and
the user wants it "running" or "buildable" on Replit, follow this setup
before attempting `./gradlew assembleDebug`, and set expectations that
Replit can build but not run/emulate the app.
