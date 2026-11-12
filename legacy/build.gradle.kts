plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    // Public development-only key, pinned across GitHub Actions runs.
    // This is NOT a secure release key. Move it into GitHub Actions secrets
    // before distributing the APK publicly beyond personal testing.
    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("ci/arcs-dev-signing.jks")
            storePassword = "ArcsDraculaDev26"
            keyAlias = "arcsdev"
            keyPassword = "ArcsDraculaDev26"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }
    namespace = "dev.kd40000.arcscompasslive"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kd40000.arcscompasslive"
        minSdk = 30
        targetSdk = 33
        versionCode = 13
        versionName = "0.5.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.wear.watchface:watchface:1.2.1")
    implementation("androidx.wear.watchface:watchface-editor:1.2.1")
    implementation("androidx.activity:activity-ktx:1.7.0")
    implementation("androidx.wear.watchface:watchface-complications-rendering:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
