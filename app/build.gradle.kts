import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// k2-fsa/sherpa-onnx (Apache-2.0): среда запуска голосов Piper на Android (OfflineTts + espeak-ng); AAR скачивается один раз в app/libs
val sherpaVersion = "1.13.8"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
val downloadSherpa by tasks.registering {
    outputs.file(sherpaAar)
    onlyIf { !sherpaAar.exists() }
    doLast {
        sherpaAar.parentFile.mkdirs()
        val part = File(sherpaAar.parentFile, sherpaAar.name + ".part")
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        check(part.length() > 40_000_000L) { "sherpa-onnx AAR скачан не полностью" }
        check(part.renameTo(sherpaAar)) { "не удалось сохранить sherpa-onnx AAR" }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadSherpa) }

android {
    namespace = "com.base.editor"
    compileSdk = 36

    packaging {
        // несколько нативных библиотек (Vosk, LiteRT, sherpa-onnx) приносят свой libc++_shared.so
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }

    defaultConfig {
        applicationId = "com.base.editor"
        minSdk = 29          // loadThumbnail(), MediaStore scoped storage
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
        // нативные библиотеки (Vosk, LiteRT); x86 нужен только эмуляторам и сильно раздувает APK
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "release-keystore.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.litert)
    // Vosk: офлайн-распознавание речи с пословными таймингами (маленькие модели ru/en); JNA нужен ему для нативных вызовов
    implementation(libs.vosk.android)
    implementation(files(sherpaAar))
    // распаковка голосов Piper (tar.bz2)
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.libpag)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
