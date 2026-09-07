plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "MomoShared"
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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.activity.compose)
            // 极光推送（聚合推送）：jpush 必须配合 jcore。从 aliyun/mavenCentral 解析。
            // AppKey 经 AndroidManifest meta-data(JPUSH_APPKEY) 注入，未配置时 PushProvider.init 为空操作。
            implementation("cn.jiguang.sdk:jpush:4.0.5")
            implementation("cn.jiguang.sdk:jcore:2.7.4")
            // 华为 HMS Push(供 MomoHmsPushService 直接接管 HMS 事件)。androidApp 也有(聚合插件)。
            implementation(libs.hms.push)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation("javazoom:jlayer:1.0.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                // org.intellij.markdown 是 multiplatform-markdown-renderer 的传递运行时依赖,
                // 但未暴露到编译类路径; MarkdownTableParseTest 需直接用其解析器做表格诊断。
                implementation("org.jetbrains:markdown:0.7.3")
            }
        }
        iosMain.dependencies {
            // ComposeUIViewController(iosMain 入口)在独立的 ui-uikit 模块; 库模块(:shared)需显式声明,
            // 否则 compose.ui 只带入含 UIKitView 的 ui 模块, ComposeUIViewController 解析不到。
            implementation("org.jetbrains.compose.ui:ui-uikit:" + libs.versions.compose.get())
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.mobius.momo.shared"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26
        val configuredBaseUrl = providers.gradleProperty("MOMO_BASE_URL")
            .orElse(providers.environmentVariable("MOMO_BASE_URL"))
            .getOrElse("https://cloud-17.agent-matrix.com")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MOMO_BASE_URL", "\"$configuredBaseUrl\"")
        // 极光推送 AppKey / 渠道名。从 gradle.properties(或环境变量) MOMO_JPUSH_APPKEY / MOMO_JPUSH_CHANNEL 读取，
        // 默认空串——空串时 JPush 不注册（PushProvider.init 为空操作），App 仍可正常构建与运行。
        // 配置后需同时在 androidApp 的 manifestPlaceholders 设置 JPUSH_APPKEY（JPush SDK 经 manifest meta-data 读取）。
        val jpushAppKey = providers.gradleProperty("MOMO_JPUSH_APPKEY")
            .orElse(providers.environmentVariable("MOMO_JPUSH_APPKEY"))
            .getOrElse("")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val jpushChannel = providers.gradleProperty("MOMO_JPUSH_CHANNEL")
            .orElse(providers.environmentVariable("MOMO_JPUSH_CHANNEL"))
            .getOrElse("mobius")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MOMO_JPUSH_APPKEY", "\"$jpushAppKey\"")
        buildConfigField("String", "MOMO_JPUSH_CHANNEL", "\"$jpushChannel\"")
    }
}
