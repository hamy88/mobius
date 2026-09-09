plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // 注：华为 agconnect(agcp) 插件与 KMP 的 androidTarget 检查冲突，故不在此 apply；
    // 改在文件末尾用 pluginManager.apply 延迟到 kotlin{} 之后。
    // 关键：agcp 延迟 apply 后不会把 agconnect-services.json 注入 APK assets，agconnect-core
    // 运行时读不到配置 → HMS getToken 失败 → 拿不到华为令牌 → 推送送不到被杀的 App。
    // 故必须把 agconnect-services.json 同时放在 src/androidMain/assets/，AGP 会可靠打包成
    // assets/agconnect-services.json，agconnect-core 运行时直接读。两处都放(根目录给 agcp)。
}

val releaseKeystorePath = providers.gradleProperty("MOMO_ANDROID_KEYSTORE_PATH")
    .orElse(providers.environmentVariable("MOMO_ANDROID_KEYSTORE_PATH"))
val releaseKeystorePassword = providers.gradleProperty("MOMO_ANDROID_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("MOMO_ANDROID_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("MOMO_ANDROID_KEY_ALIAS")
    .orElse(providers.environmentVariable("MOMO_ANDROID_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("MOMO_ANDROID_KEY_PASSWORD")
    .orElse(providers.environmentVariable("MOMO_ANDROID_KEY_PASSWORD"))
val releaseSigningAvailable = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

kotlin {
    androidTarget()

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.androidx.activity.compose)
            // 极光推送华为厂商通道：huawei 插件(桥接 JPush↔HMS) + HMS Push SDK。
            // JPush 运行时自动探测本插件，在华为设备上经 HMS 下发，App 被杀也能收到。
            implementation(libs.jpush.plugin.huawei)
            implementation(libs.hms.push)
        }
    }
}

android {
    namespace = "com.mobius.momo"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.mobius.momo"
        minSdk = 26
        targetSdk = 35
        // 0.2.0: 修复移动端聊天内容无法长按选取复制(SelectionContainer + combinedClickable)。
        versionCode = 21
        versionName = "0.2.0"
        // 极光推送 AppKey / 渠道：JPush SDK 经 AndroidManifest meta-data(JPUSH_APPKEY) 读取。
        // 从 gradle.properties(或环境变量)读取；默认空串——未配置时 JPush 不注册，App 仍可正常构建运行。
        val jpushAppKey = providers.gradleProperty("MOMO_JPUSH_APPKEY")
            .orElse(providers.environmentVariable("MOMO_JPUSH_APPKEY"))
            .getOrElse("")
        val jpushChannel = providers.gradleProperty("MOMO_JPUSH_CHANNEL")
            .orElse(providers.environmentVariable("MOMO_JPUSH_CHANNEL"))
            .getOrElse("mobius")
        manifestPlaceholders["JPUSH_APPKEY"] = jpushAppKey
        manifestPlaceholders["JPUSH_CHANNEL"] = jpushChannel
        // JPush SDK 自带 manifest 用 ${JPUSH_PKGNAME} 占位（provider authorities、permission、category 等），
        // 必须注入应用包名，否则 manifest 合并报“requires a placeholder substitution but no value”。
        manifestPlaceholders["JPUSH_PKGNAME"] = applicationId ?: "com.mobius.momo"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    // 按 ABI 拆分 APK：产出 arm64-v8a / armeabi-v7a 各自独立的包，供 mobius 主体
    // 「下载移动端 App」菜单分发 (与 desktop 端多平台 zip 对齐)。isUniversalApk=false → 不再产 fat 包。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

// 华为 agconnect 插件：读 androidApp/agconnect-services.json，把 HMS 配置注入构建，
// 供 agconnect-core 运行时读取（HMS Push 拿华为令牌的前提）。
// 必须在 kotlin{} 块之后延迟 apply——否则 agcp 访问 android 扩展会触发
// "AGP applied without creating android() Kotlin target"（KMP 冲突）。
pluginManager.apply("com.huawei.agconnect.agcp")
