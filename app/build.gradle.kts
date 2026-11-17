plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets a higher version number so updates install over the old app.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ca.activitycalendar"
    compileSdk = 36

    defaultConfig {
        applicationId = "ca.activitycalendar.app"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed key signs every build, so each update installs over the old app.
    // The key file is encrypted (AES-256); its password lives only in the
    // repository's Actions secret KEYSTORE_PASSWORD, never in the code.
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        create("shared") {
            storeFile = file("shared.keystore")
            storePassword = keystorePassword
            keyAlias = "actcal"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
