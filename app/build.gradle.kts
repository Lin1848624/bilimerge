plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.bilimerge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.bilimerge"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 只打包 arm64-v8a：ffmpeg-kit-min 仅提供 arm64-v8a / x86_64，
        // 而现代真机全部是 arm64，单 ABI 可让 APK 体积减半。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 自用工具：直接用 debug 签名，免去额外配置即可安装使用
        getByName("debug") {
            storeFile = file("bilimerge.jks")
            storePassword = "bilimerge"
            keyAlias = "bilimerge"
            keyPassword = "bilimerge"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/kotlin/**",
                "/DebugProbesKt.bin",
                "/META-INF/*.version"
            )
        }
        jniLibs {
            // 保持默认（不抽取 .so）：APK 内直接 mmap，安装更快、占用更小
            useLegacyPackaging = false
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // 这两条是有意为之，不是疏漏：
        //  OldTargetApi       —— 刻意停在 targetSdk 35（Android 15），比它更高的版本行为变更未经实测
        //  ChromeOsAbiSupport —— 只面向手机，上游 ffmpeg-kit-min 也不提供 x86_64 以外的 32 位库
        disable += setOf("OldTargetApi", "ChromeOsAbiSupport")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ffmpeg-kit 社区维护分支：原生支持 16KB 页对齐与 Android 15+，min 变体已含
    // mov/mp4 解复用器、mp4 复用器与 h264_mp4toannexb / aac_adtstoasc 位流过滤器，
    // 完全覆盖 B 站 m4s 的无损封装需求，且体积远小于 full 变体。
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9")

    // 扫描识别逻辑是纯 JVM 代码（FileStorage 只依赖 java.io），可以直接在主机上跑单测
    testImplementation("junit:junit:4.13.2")
    // EntryMeta 用 org.json，Android 自带但 JVM 上没有
    testImplementation("org.json:json:20240303")
}
