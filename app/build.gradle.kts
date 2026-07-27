plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fppvm.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.fppvm.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The test TV (HiSmart 2K ATV4) is armeabi-v7a only. Ship the two ARM ABIs the
        // zstd-jni AAR provides plus x86 for emulators; dropping the rest keeps the APK small.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        // Committed debug keystore (public debug creds) -> stable signatures across machines/CI,
        // so `adb install -r` updates in place instead of failing on a signature mismatch.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // FSEQ v2 channel data is zstd-compressed by default (xLights/FPP write zstd, occasionally
    // zlib). zstd-jni's Android AAR bundles a real libzstd per ABI, so decoding is native speed
    // and doesn't depend on sun.misc.Unsafe tricks that Android's hidden-API policy can break.
    // zlib is handled by java.util.zip.Inflater, which ships with the platform.
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Desktop-JVM natives, so the FSEQ round-trip tests can actually compress/decompress on CI.
    testImplementation("com.github.luben:zstd-jni:1.5.7-3")
}
