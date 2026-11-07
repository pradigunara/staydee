import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// FFmpegKit is vendored: the AAR is a release asset on our own repo, fetched by
// scripts/fetch-aar.sh. The build refuses any file whose sha256 does not match.
val ffmpegKitVersion = libs.versions.ffmpegkit.get()
val ffmpegKitSha256 = "72094919ac812598ec3f77ba2631e5b2dbd50b1daa98dfa64e9fc565f2b52ede"
val ffmpegKitAar = layout.projectDirectory.file("libs/ffmpeg-kit-full-gpl-$ffmpegKitVersion.aar")

val verifyFfmpegKit = tasks.register("verifyFfmpegKit") {
    val aar = ffmpegKitAar
    val expected = ffmpegKitSha256
    inputs.file(aar)
    doLast {
        val file = aar.asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "${file.name}: sha256 $actual, expected $expected" }
    }
}

tasks.named("preBuild") { dependsOn(verifyFfmpegKit) }

// Release signing comes from the environment (CI secrets, or a local export); the keystore never
// enters the repo. Without STAYDEE_KEYSTORE the release build stays unsigned.
val releaseKeystore = providers.environmentVariable("STAYDEE_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("STAYDEE_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("STAYDEE_KEY_ALIAS").orNull

android {
    namespace = "io.github.pradigunara.staydee"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pradigunara.staydee"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        // the AAR's .so files are already 16 KB aligned; keep them uncompressed
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files(ffmpegKitAar))
    implementation(libs.smart.exception)
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
