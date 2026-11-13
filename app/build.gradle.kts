plugins {
    id("com.android.application")
}

android {
    namespace = "dev.wiserlocal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wiserlocal.app"
        minSdk = 29
        // Kept at 35 on purpose: newer targets add local-network permission rules
        // and force edge-to-edge layout, neither of which this basic app handles yet.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    lint {
        disable += "OldTargetApi"
    }
}
