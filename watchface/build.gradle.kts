plugins { id("com.android.application") }
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
    namespace = "dev.kd40000.arcsdraculacompass"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kd40000.arcsdraculacompass"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }
}
