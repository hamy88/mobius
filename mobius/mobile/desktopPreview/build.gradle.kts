buildscript {
    repositories {
        // Aliyun mirrors go LAST: GitHub Actions runners are outside China,
        // so mavenCentral/google/gradlePluginPortal resolve fast and reliably.
        // Aliyun frequently 502s; keeping it as a fallback (not primary)
        // prevents a single flaky mirror from breaking the whole build.
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
        classpath("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.0")
        classpath("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.0.0")
        classpath("org.jetbrains.compose:compose-gradle-plugin:1.6.11")
    }
}

apply(plugin = "org.jetbrains.kotlin.multiplatform")
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
apply(plugin = "org.jetbrains.compose")
apply(plugin = "org.jetbrains.kotlin.plugin.compose")

val desktopComposeArtifact = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
        "desktop-jvm-windows-x64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") == "aarch64" ->
        "desktop-jvm-macos-arm64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        "desktop-jvm-macos-x64"
    System.getProperty("os.arch") == "aarch64" ->
        "desktop-jvm-linux-arm64"
    else ->
        "desktop-jvm-linux-x64"
}

configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../shared/src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.6.11")
                implementation("org.jetbrains.compose.foundation:foundation:1.6.11")
                implementation("org.jetbrains.compose.material3:material3:1.6.11")
                implementation("org.jetbrains.compose.ui:ui:1.6.11")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-client-logging:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.27.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
            }
        }
        val commonTest by getting {
            kotlin.srcDir("../shared/src/commonTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("../shared/src/desktopMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.desktop:$desktopComposeArtifact:1.6.11")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation("io.ktor:ktor-client-cio:2.3.12")
                implementation("javazoom:jlayer:1.0.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

afterEvaluate {
    val desktopTarget = extensions
        .getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class)
        .targets
        .getByName("desktop")
    val mainCompilation = desktopTarget.compilations.getByName("main")
    tasks.register<JavaExec>("runDesktop") {
        group = "application"
        description = "Run the Compose Desktop preview."
        dependsOn(mainCompilation.compileTaskProvider)
        mainClass.set("com.mobius.momo.preview.MainKt")
        classpath = mainCompilation.output.allOutputs + (mainCompilation.runtimeDependencyFiles ?: files())
    }
}
