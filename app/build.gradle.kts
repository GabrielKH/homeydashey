import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.homeydash"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.homeydash"
        minSdk = 29
        targetSdk = 35
        // CI numbers each build, so a newer APK installs over the one on the tablet.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.2.$build"
        // Only the kitchen tablet's 64-bit ARM chip: the speech, voiceprint and face libraries
        // otherwise ship for four chip types and more than double the download.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // One key for every build, so updates install over the old app instead of
        // needing it removed first. It is a sideload-only kiosk app; swap in a key
        // kept outside the repo before ever publishing it.
        create("shared") {
            storeFile = file("signing/homeydash.jks")
            storePassword = "homeydash"
            keyAlias = "homeydash"
            keyPassword = "homeydash"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sherpa-onnx brings ONNX Runtime 1.27.1 and onnxruntime-android 1.27.0; either serves both.
        jniLibs.pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
    }

    // The face model is memory-mapped straight from the APK.
    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * sherpa-onnx (TARS's voice, spoken on the tablet) is only published as an .aar on GitHub, so
 * it is fetched once into the build folder.
 */
val sherpaVersion = "1.13.5"
val sherpaAar: File = rootProject.layout.buildDirectory.file("sherpa/sherpa-onnx-$sherpaVersion.aar").get().asFile.also { aar ->
    if (!aar.exists()) {
        aar.parentFile.mkdirs()
        val part = File(aar.path + ".part")
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").toURL()
            .openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        part.renameTo(aar)
    }
}

dependencies {
    implementation(project(":homey-api"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // Assistant: on-device face recognition.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
    // Fridge: barcode and QR scanning on the camera, offline (the model is in the app).
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    // Wake name and voice recognition, offline. Vosk calls its native library through JNA,
    // which must be the Android (aar) build.
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    // Runs the WeSpeaker voiceprint model that tells voices apart.
    implementation(libs.onnxruntime.android)
    // TARS's voice: a Piper model run by sherpa-onnx, and the tar.bz2 of pronunciation rules it needs.
    implementation(files(sherpaAar))
    implementation(libs.commons.compress)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

configurations.all {
    // socket.io-client pulls in org.json, which Android already ships.
    exclude(group = "org.json", module = "json")
}
