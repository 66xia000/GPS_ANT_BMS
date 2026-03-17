plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zjsf.gps_ant_bms"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zjsf.gps_ant_bms"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 1. 配置 APK 分片
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 2. 使用更兼容的方式处理多 APK 版本号
// 如果这部分仍然报错，可以暂时注释掉
tasks.whenTaskAdded {
    if (name.contains("Process") && name.contains("Release") && name.contains("Resources")) {
        // 这里的逻辑通常可以通过 splits 自动处理
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
