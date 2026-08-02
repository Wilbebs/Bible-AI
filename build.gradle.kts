plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // Applied (conditionally, see app/build.gradle.kts) once google-services.json exists —
    // that file comes from registering the app in the Firebase console, a one-time manual step.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
