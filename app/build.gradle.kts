plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ztbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ztbrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.6"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ZeroTier SDK (libzt) - 嵌入应用层的 ZeroTier 协议栈
    // 编译自: https://github.com/zerotier/libzt
    // 将编译好的 .aar 放入 app/libs/ 目录
    implementation(files("libs/libzt.aar"))

    // OkHttp - 用于 SOCKS5 代理和网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")
}

