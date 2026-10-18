import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseStore = providers.environmentVariable("NUSAMESH_KEYSTORE").orNull
val releaseAlias = providers.environmentVariable("NUSAMESH_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("NUSAMESH_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("NUSAMESH_KEY_PASSWORD").orNull

val buildCodec2Ios = tasks.register<Exec>("buildCodec2Ios") {
    workingDir(rootProject.projectDir)
    commandLine("sh", rootProject.file("scripts/build-codec2-ios.sh").absolutePath)
    inputs.dir(rootProject.file("third_party/codec2/src"))
    inputs.dir(file("src/nativeInterop/codec2"))
    inputs.dir(file("src/nativeInterop/codec2-generated"))
    inputs.file(rootProject.file("scripts/build-codec2-ios.sh"))
    outputs.dir(layout.buildDirectory.dir("codec2-ios"))
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        val sdk = if (target.name == "iosArm64") "iphoneos" else "iphonesimulator"
        val bridge = target.compilations.getByName("main").cinterops.create("codec2bridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/codec2bridge.def"))
            includeDirs(project.file("src/nativeInterop/codec2"))
            extraOpts("-libraryPath", project.layout.buildDirectory.dir("codec2-ios/$sdk").get().asFile.absolutePath)
        }
        tasks.matching { it.name == bridge.interopProcessingTaskName }.configureEach {
            dependsOn(buildCodec2Ios)
        }
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation(libs.haze)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // AI super-resolution gambar LoRa (Real-ESRGAN / SESR / XLSR, TFLite int8)
            implementation(libs.tensorflow.lite)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "id.nusamesh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.nusamesh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Peta offline bawaan (dibuat tools/offline-map, di-gitignore): ikut APK supaya langsung tersedia
    // tanpa impor. Bila folder tidak ada, APK tetap jadi — hanya tanpa peta bawaan.
    sourceSets.getByName("main").assets.srcDir("offline-maps")

    // Model .tflite harus tersimpan tanpa kompresi supaya bisa di-memory-map langsung dari APK.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (releaseStore != null) {
        require(!releaseAlias.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            "Release signing requires NUSAMESH_KEY_ALIAS, NUSAMESH_STORE_PASSWORD and NUSAMESH_KEY_PASSWORD."
        }
        signingConfigs.create("distribution") {
            storeFile = file(releaseStore)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        if (releaseStore != null) signingConfig = signingConfigs.getByName("distribution")
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}



