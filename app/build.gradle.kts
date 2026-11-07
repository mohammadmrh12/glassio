import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glasslauncher.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.glasslauncher.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 55
        versionName = "3.2.0"
        // TV boxes are ARM: the depth model's native library only for those (keeps the download small).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Trakt app (PKCE, no secret). Kept in local.properties, which is not committed.
        val props = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${props.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "NUVIO_ANON_KEY", "\"${props.getProperty("NUVIO_ANON_KEY", "")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${props.getProperty("SIMKL_CLIENT_ID", "")}\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key so it installs over the debug build and updates cleanly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses java.nio / java.time below Android 13.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Unit tests run on the computer (./gradlew testDebugUnitTest); Android calls return defaults there.
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    // Shuffle in other apps' music players (their MediaSessionCompat).
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    // Trailers played by Glassio's own player (no YouTube page): finds the trailer's video and sound streams.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("dev.mobile:dadb:1.2.10")
    // Depth effect on photos: runs a small depth model (downloaded on first use) on the TV.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
