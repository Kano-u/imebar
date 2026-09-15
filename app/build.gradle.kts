plugins {
    id("com.android.application")
}

// 固定签名：CI 里通过环境变量把 keystore 传进来（GitHub Secrets）。
// 本地没配这些环境变量时，自动退回 debug 签名，保证本地也能编译。
val fixedKeystore: String = System.getenv("KEYSTORE_PATH") ?: ""

android {
    namespace = "com.local.imebar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.imebar"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (fixedKeystore.isNotEmpty()) {
            create("fixed") {
                storeFile = file(fixedKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 个人自用：不混淆，方便出问题时看堆栈
            isMinifyEnabled = false
            // 有固定密钥就用固定密钥（版本之间可以互相覆盖安装）；没有就用 debug 签名
            signingConfig = if (fixedKeystore.isNotEmpty()) {
                signingConfigs.getByName("fixed")
            } else {
                signingConfigs.getByName("debug")
            }
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

dependencies {
    // 这三个 jar 是从官方 AAR 里抽出来的 classes.jar（见 app/libs/README.txt）。
    // 用普通 jar 而不是 AAR，可以避开 AAR 元数据里 minCompileSdk=37 的限制，
    // 这样用 AGP 8.7.3 + compileSdk 35 就能编，CI 也不用额外装 android-37。
    //
    // api 由 LSPosed 框架在运行时提供，绝不能打进 APK，所以是 compileOnly；
    // service 提供 XposedProvider（远程配置读取要用），interface 是它依赖的 AIDL 接口。
    compileOnly(files("libs/libxposed-api.jar"))
    implementation(files("libs/libxposed-service.jar", "libs/libxposed-interface.jar"))
}
