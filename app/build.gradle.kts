plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.imebar"
    // libxposed 的 AAR 要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.local.imebar"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 个人自用：不混淆，方便出问题时看堆栈
            isMinifyEnabled = false
            // 用 debug 签名，保证 release 包也能直接装（后面可换成自己的 keystore）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

configurations.all {
    // libxposed 的 api 由 LSPosed 框架在运行时提供，不能打进 APK
    exclude(group = "io.github.libxposed", module = "api")
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    // service 里带 XposedProvider（远程配置读取需要它）
    implementation("io.github.libxposed:service:102.0.0")
}
