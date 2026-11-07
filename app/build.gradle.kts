plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Set by the release workflow (-PappVersionName=1.2.3 -PappVersionCode=<run number>); local builds are 0.1.0 / 1.
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = ((findProperty("appVersionCode") as String?)?.toIntOrNull()) ?: 1

// "owner/repository" on GitHub whose releases hold the APKs. Set `wewrc.updateRepo` in gradle.properties; blank = no update check.
val updateRepo = (findProperty("wewrc.updateRepo") as String?).orEmpty()

// Read-only GitHub token so the app can fetch releases from a PRIVATE repository (the workflow passes the UPDATE_TOKEN secret). Blank = public repo.
val updateToken = (findProperty("wewrc.updateToken") as String?).orEmpty()

// Release signing comes from the environment (the release workflow, or your own machine). Without it, release builds are unsigned.
val keystorePath: String? = System.getenv("WEWRC_KEYSTORE_FILE")

android {
    namespace = "za.westend.watersplit"
    // Current AndroidX libraries require compiling against API 37; the app still targets 36 (Android 16).
    compileSdk = 37

    defaultConfig {
        applicationId = "za.westend.watersplit"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("WEWRC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WEWRC_KEY_ALIAS")
                keyPassword = System.getenv("WEWRC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The old sheet history lives with the engine tests (golden data); ship that same file inside the app
    // so the history is imported on first launch without keeping a second copy.
    sourceSets {
        getByName("main") {
            assets.directories.add("../engine/src/test/resources")
        }
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // Runs the daily "is anything due?" check that powers the reminder notification.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Official Anthropic SDK: reads the municipal invoice and the meter photo (phases 3 and 4).
    implementation("com.anthropic:anthropic-java:3.6.0")
}
