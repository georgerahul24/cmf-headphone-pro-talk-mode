plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.georgerahul.cmftalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.georgerahul.cmftalk"
        minSdk = 31
        targetSdk = 36
        versionCode = 14
        versionName = "2.6.3"
    }

    // CI passes a fixed keystore (secret ANDROID_KEYSTORE_B64) so new builds
    // install over old ones. Without it, the local debug key is used.
    val ks = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        create("release") {
            if (ks != null) {
                storeFile = file(ks)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
}
