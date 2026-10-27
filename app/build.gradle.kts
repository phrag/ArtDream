plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build identity for the self-updater (Updater.kt): compared against the
// version.json that CI publishes next to the rolling release APK.
fun git(vararg args: String): String = try {
    ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) {
    ""
}
val gitSha = git("rev-parse", "HEAD").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
val gitTime = git("log", "-1", "--format=%ct").toLongOrNull() ?: 0L

android {
    namespace = "com.phrag.artdream"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phrag.artdream"
        minSdk = 26          // covers every Shield
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "GIT_TIME", "${gitTime}L")
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/phrag/ArtDream/releases/download/latest/\"")
    }

    // Committed debug key so every CI build has the same signature and the
    // "latest" release APK installs over the previous one. Debug-only: not a
    // secret, don't use it for anything published to a store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true   // GIT_SHA etc. for the updater
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // NewPipeExtractor needs java.time / NIO below API 33
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Runtime: the YouTube extractor below ("Save to TV", and stream URLs for YouTube links) and
    // Media3 ExoPlayer, which plays YouTube links natively so HDR streams reach the TV as HDR.
    // Everything else is HttpURLConnection + org.json (bundled) + BitmapFactory + MediaPlayer.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // NewPipeExtractor is GPL-3.0 like this app. Pinned to a release tag; update deliberately,
    // YouTube changes break old versions.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // Tests only: android.jar's org.json is stubbed on the JVM, so use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
