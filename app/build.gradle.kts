plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Copies the vendored ffmpeg/ffprobe executables (vendor/ffmpeg, built by native/ffmpeg/build.sh)
 * into a jniLibs tree as lib*.so: only files named like libraries are extracted to
 * nativeLibraryDir, the one app location Android lets us exec from.
 */
abstract class BundleNativeTools : DefaultTask() {
    @get:InputDirectory
    abstract val vendorDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val abiDir = outputDir.get().asFile.resolve("arm64-v8a").apply { deleteRecursively(); mkdirs() }
        for (tool in listOf("ffmpeg", "ffprobe")) {
            vendorDir.get().file("arm64-v8a/$tool").asFile.copyTo(abiDir.resolve("lib$tool.so"), overwrite = true)
        }
    }
}

val bundleNativeTools = tasks.register<BundleNativeTools>("bundleNativeTools") {
    vendorDir.set(layout.projectDirectory.dir("../vendor/ffmpeg"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(bundleNativeTools, BundleNativeTools::outputDir)
    }
}

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
        versionCode = 4
        versionName = "0.1.3"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        // extract lib*.so on install: the bundled ffmpeg/ffprobe are executed from nativeLibraryDir
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
