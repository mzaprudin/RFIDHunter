import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val localProperties = Properties().also { properties ->
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { stream -> properties.load(stream) }
}
val configuredVoskModel = providers.gradleProperty("voskModelDir")
    .orElse(localProperties.getProperty("voskModelDir") ?: "")
val configuredWhisperModelDir = providers.gradleProperty("whisperModelDir")
    .orElse(localProperties.getProperty("whisperModelDir") ?: "")
val configuredWhisperVariant = providers.gradleProperty("whisperModelVariant")
    .orElse(localProperties.getProperty("whisperModelVariant") ?: "q5_1")
val configuredNdkDir = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse("${System.getenv("LOCALAPPDATA") ?: ""}/Android/Sdk/ndk/26.3.11579264")
val usableNdk = configuredNdkDir.orNull?.let { File(it, "source.properties").isFile } == true
fun resolveVoskModelPath(path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}
val generatedVoskAssets = layout.buildDirectory.dir("generated/vosk-assets")
val generatedWhisperAssets = layout.buildDirectory.dir("generated/whisper-assets")
val prepareVoskModel by tasks.registering(Sync::class) {
    onlyIf {
        val path = configuredVoskModel.orNull.orEmpty()
        path.isNotBlank() && resolveVoskModelPath(path).isDirectory
    }
    from(configuredVoskModel.map(::resolveVoskModelPath))
    into(generatedVoskAssets.map { it.dir("vosk-model") })
}
val prepareWhisperModels by tasks.registering(Sync::class) {
    onlyIf {
        val path = configuredWhisperModelDir.orNull.orEmpty()
        path.isNotBlank() && resolveVoskModelPath(path).isDirectory
    }
    from(configuredWhisperModelDir.map(::resolveVoskModelPath)) {
        include("ggml-base-q5_1.bin")
    }
    into(generatedWhisperAssets.map { it.dir("whisper-model") })
}

android {
    namespace = "ru.rfidhunter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.rfidhunter.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        if (usableNdk) ndk { abiFilters += listOf("arm64-v8a") }
    }

    if (usableNdk) {
        ndkVersion = "26.3.11579264"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    flavorDimensions += "reader"
    productFlavors {
        create("mock") {
            dimension = "reader"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
        create("hardware") {
            dimension = "reader"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets["main"].assets.srcDir("src/main/assets")
    sourceSets["main"].assets.srcDir(generatedVoskAssets)
    sourceSets["main"].assets.srcDir(generatedWhisperAssets)
    sourceSets["hardware"].java.srcDir("src/hardware/kotlin")

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.alphacephei:vosk-android:0.3.75")

    add("hardwareImplementation", files("../Honeywell_RFID_SDK_v0.5.0/libs/honeywell_rfid_sdk.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.register("checkVoiceModel") {
    doLast {
        val model = configuredVoskModel.orNull?.takeIf { it.isNotBlank() }
        if (model == null) {
            logger.lifecycle("Vosk model is not configured. Runtime will use the developer fallback until voskModelDir is set.")
        } else {
            logger.lifecycle("Vosk model configured at $model")
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareVoskModel)
    dependsOn(prepareWhisperModels)
}

// Lint also snapshots the generated assets; declare the same dependency so
// Gradle's task validation cannot observe a stale or missing model directory.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(prepareVoskModel)
    dependsOn(prepareWhisperModels)
}

tasks.register("checkWhisperModels") {
    doLast {
        val directory = configuredWhisperModelDir.orNull?.takeIf { it.isNotBlank() }?.let(::resolveVoskModelPath)
        val q5 = directory?.resolve("ggml-base-q5_1.bin")
        val q8 = directory?.resolve("ggml-base-q8_0.bin")
        logger.lifecycle("Whisper q5_1: ${q5?.takeIf { it.isFile }?.length() ?: 0} bytes")
        logger.lifecycle("Whisper q8_0: ${q8?.takeIf { it.isFile }?.length() ?: 0} bytes")
    }
}
