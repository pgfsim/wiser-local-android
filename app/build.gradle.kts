import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. The key never goes in the repo: the values come from environment
// variables (GitHub Actions) or from keystore.properties next to settings.gradle.kts
// (local builds; it is git-ignored). Without them, release builds are left unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(property)

android {
    namespace = "dev.wiserlocal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wiserlocal.app"
        minSdk = 29
        // Kept at 35 on purpose: newer targets add local-network permission rules
        // and force edge-to-edge layout, neither of which this basic app handles yet.
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // Where the app looks for updates. -PupdateApi=... points it at a test server.
        val updateApi = (project.findProperty("updateApi") as String?) ?: "https://api.github.com"
        buildConfigField("String", "UPDATE_API", "\"$updateApi\"")
        buildConfigField("String", "UPDATE_REPO", "\"pgfsim/wiser-local-android\"")
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "SIGNING_STORE_FILE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "OldTargetApi"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
