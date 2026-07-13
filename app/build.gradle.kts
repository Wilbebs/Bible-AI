import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Keys are dev-time only: read from local.properties (gitignored, never
// committed) and baked into BuildConfig at compile time. There is no
// in-app UI to view or change them — not accessible/controllable by the
// end user. A real shipped app would proxy these through a backend
// instead of embedding them in the APK at all.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.logos.bibletranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.logos.bibletranslate"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Falls back to Replit Secrets (env vars) when local.properties doesn't
        // define the key — keeps the same dev-time-only, no-in-app-UI model
        // while letting the Replit environment supply keys via its secrets store.
        val geminiKey = localProperties.getProperty("gemini.api.key")
            ?: System.getenv("GEMINI_API_KEY") ?: ""
        val translateKey = localProperties.getProperty("translate.api.key")
            ?: System.getenv("TRANSLATE_API_KEY") ?: ""

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "TRANSLATE_API_KEY", "\"$translateKey\"")
    }

    // Bundled Bible SQLite assets are not compressed further by AAPT.
    androidResources {
        noCompress += "db"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
