plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.georgerahul.cmftalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.georgerahul.cmftalk"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
