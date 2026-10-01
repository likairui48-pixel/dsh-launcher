plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsh.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // 固定签名：CI 每次跑都是全新 runner，AGP 默认会现生成一个随机 debug 密钥，
    // 导致每次构建签名都不同、无法覆盖安装。仓库内固化一把 debug 密钥解决这个问题。
    signingConfigs {
        create("dsh") {
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dsh")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dsh")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Shizuku 客户端 SDK：仅用于「检测 Shizuku 是否在运行」
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
