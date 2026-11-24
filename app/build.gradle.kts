import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.glasslauncher.tv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.glasslauncher.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 79
        versionName = "4.4.1"
        // TV boxes are ARM: the depth model's native library only for those (keeps the download small).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Trakt app (PKCE, no secret). Kept in local.properties, which is not committed.
        val props = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${props.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "NUVIO_ANON_KEY", "\"${props.getProperty("NUVIO_ANON_KEY", "")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${props.getProperty("SIMKL_CLIENT_ID", "")}\"")
        // "Support me" (Settings → About): the private Google Form a gift card code is sent to. Empty: not offered.
        buildConfigField("String", "SUPPORT_FORM", "\"${props.getProperty("SUPPORT_FORM", "")}\"")
        buildConfigField("String", "SUPPORT_CODE_ENTRY", "\"${props.getProperty("SUPPORT_CODE_ENTRY", "")}\"")
        buildConfigField("String", "SUPPORT_NOTE_ENTRY", "\"${props.getProperty("SUPPORT_NOTE_ENTRY", "")}\"")
        // "Report a problem" (Settings → About): the relay that opens the GitHub issue (tools/report-worker). Empty: a QR code instead.
        buildConfigField("String", "REPORT_URL", "\"${props.getProperty("REPORT_URL", "")}\"")
    }
    // Two builds of the same code. "github": everything, as published on GitHub Releases (the package and the key
    // never change: it updates over earlier installs). "play": for Google Play, its own package, without what Play
    // does not allow (see docs/play/README.md); BuildConfig.PLAY switches those parts off and R8 drops their code.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "store"
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "PLAY", "true")
        }
    }
    // Google Play's upload key (PLAY_STORE_FILE, PLAY_STORE_PASSWORD, PLAY_KEY_ALIAS, PLAY_KEY_PASSWORD in
    // local.properties). Without it the play build is signed with the debug key: fine for a TV, refused by Play.
    val playProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val playKey = playProps.getProperty("PLAY_STORE_FILE", "").takeIf { it.isNotBlank() && rootProject.file(it).exists() }
    signingConfigs {
        if (playKey != null) create("play") {
            storeFile = rootProject.file(playKey)
            storePassword = playProps.getProperty("PLAY_STORE_PASSWORD", "")
            keyAlias = playProps.getProperty("PLAY_KEY_ALIAS", "")
            keyPassword = playProps.getProperty("PLAY_KEY_PASSWORD", "")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key so it installs over the debug build and updates cleanly.
            // (Set per flavor below: a build type's key would win over the flavor's.)
        }
    }
    productFlavors.getByName("github").signingConfig = signingConfigs.getByName("debug")
    productFlavors.getByName("play").signingConfig = signingConfigs.getByName(if (playKey != null) "play" else "debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses java.nio / java.time below Android 13.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Unit tests run on the computer (./gradlew testGithubDebugUnitTest); Android calls return defaults there.
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Shuffle in other apps' music players (their MediaSessionCompat).
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("com.google.zxing:core:3.5.4")
    // Trailers played by Glassio's own player (no YouTube page): finds the trailer's video and sound streams.
    // (Not in the play build: Google Play does not allow playing YouTube's videos outside YouTube's own player.)
    "githubImplementation"("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("dev.mobile:dadb:2.0.0")
    // Depth effect on photos: runs a small depth model (downloaded on first use) on the TV.
    // Kept on 1.x: LiteRT 2 pulls a beta Play library with WorkManager and Room (it crashed at start under R8) and
    // adds 3 MB, for the one Interpreter call used here.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
