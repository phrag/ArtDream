plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.artdream"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.artdream"
        minSdk = 26          // covers every Shield
        targetSdk = 34
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Intentionally none. HttpURLConnection + org.json (bundled) + BitmapFactory.
}
