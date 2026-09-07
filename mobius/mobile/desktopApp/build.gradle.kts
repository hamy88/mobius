import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val appPackageName = "Mobius"
val appPackageVersion = "0.1.0"
val appMainClass = "com.mobius.momo.desktop.MainKt"

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

val windowsPortableRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.getByName("desktopRuntimeClasspath"))
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        attribute(Attribute.of("ui", String::class.java), "awt")
    }

    // The desktop source set uses compose.desktop.currentOs for local runs and
    // native package tasks. For a Windows portable bundle built on Linux/macOS,
    // replace that current-OS native runtime with the Windows x64 runtime.
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-linux-x64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-linux-arm64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-x64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-arm64")
}

dependencies {
    windowsPortableRuntimeClasspath(
        "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:${libs.versions.compose.get()}"
    )
}

compose.desktop {
    application {
        mainClass = appMainClass

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = appPackageName
            packageVersion = appPackageVersion
            // ASCII only: Inno Setup's ANSI-mode template misdecodes CJK
            // characters in description, aborting jpackage with
            // "UTFDataFormatException: Input length = 1".
            description = "Mobius"
            vendor = "Mobius"
            includeAllModules = true

            windows {
                menuGroup = "Mobius"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("icons/mobius_logo.ico"))
            }

            macOS {
                bundleID = "com.mobius.momo.desktop"
                dockName = "Mobius"
                // Apple's package tooling rejects a zero major version. The
                // product version remains 0.1.0; this is the DMG package version.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
                iconFile.set(project.file("icons/mobius_logo.icns"))
                entitlementsFile.set(project.file("entitlements.mac.plist"))
                runtimeEntitlementsFile.set(project.file("runtime-entitlements.mac.plist"))

                val macSignEnabled = providers.environmentVariable("MOMO_MAC_SIGN")
                    .orElse(providers.gradleProperty("compose.desktop.mac.sign"))
                    .map { it.equals("true", ignoreCase = true) }
                    .orElse(false)

                val signingIdentity = providers.environmentVariable("MOMO_MAC_SIGNING_IDENTITY")
                    .orElse(providers.gradleProperty("compose.desktop.mac.signing.identity"))

                signing {
                    sign.set(macSignEnabled)
                    identity.set(signingIdentity)
                }

                notarization {
                    appleID.set(providers.environmentVariable("APPLE_ID"))
                    password.set(providers.environmentVariable("APPLE_ID_PASSWORD"))
                    teamID.set(providers.environmentVariable("APPLE_TEAM_ID"))
                }
            }

            linux {
                iconFile.set(project.file("icons/mobius_logo.png"))
            }
        }
    }
}

val writeWindowsPortableLauncher by tasks.registering {
    val launcherFile = layout.buildDirectory.file("generated/windowsPortable/$appPackageName.bat")
    outputs.file(launcherFile)

    doLast {
        launcherFile.get().asFile.writeText(
            """
            @echo off
            setlocal
            set "APP_HOME=%~dp0"
            set "JAVA_EXE=java"
            if exist "%APP_HOME%runtime\bin\java.exe" set "JAVA_EXE=%APP_HOME%runtime\bin\java.exe"
            "%JAVA_EXE%" -cp "%APP_HOME%app\lib\*" $appMainClass %*
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        )
    }
}

val writeWindowsPortableReadme by tasks.registering {
    val readmeFile = layout.buildDirectory.file("generated/windowsPortable/README.txt")
    outputs.file(readmeFile)

    doLast {
        readmeFile.get().asFile.writeText(
            """
            $appPackageName Windows portable bundle

            Run $appPackageName.bat to start the app.

            This bundle contains the Windows Compose Desktop runtime jars, but
            does not include a bundled Windows JRE by default. It will use:

            1. runtime\bin\java.exe when a JRE is copied into the runtime folder;
            2. otherwise, java.exe from PATH.

            This is a no-Windows-host fallback artifact. The official installer
            remains :desktopApp:packageExe, which must be run on Windows.
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        )
    }
}

val prepareWindowsPortableBundle by tasks.registering(Sync::class) {
    dependsOn("desktopJar", writeWindowsPortableLauncher, writeWindowsPortableReadme)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(layout.buildDirectory.dir("windowsPortable/$appPackageName"))

    from(tasks.named("desktopJar")) {
        into("app/lib")
    }
    from(windowsPortableRuntimeClasspath) {
        into("app/lib")
    }
    from(writeWindowsPortableLauncher.map { it.outputs.files.singleFile })
    from(writeWindowsPortableReadme.map { it.outputs.files.singleFile })
    from("icons/mobius_logo.ico") {
        into("app")
    }
}

tasks.register<Zip>("packageWindowsPortableZip") {
    group = "distribution"
    description = "Builds a Windows x64 portable zip without requiring a Windows host."

    dependsOn(prepareWindowsPortableBundle)
    archiveFileName.set("$appPackageName-windows-portable-$appPackageVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/windows-portable"))
    from(prepareWindowsPortableBundle.map { it.destinationDir }) {
        into(appPackageName)
    }
}

// ===== macOS Apple Silicon (arm64) portable bundle =====
// jpackage 无法在非 macOS 主机产出 .dmg/.app; 这里照 Windows portable 模式,
// 把 Compose/Skiko 原生 runtime 换成 macOS-arm64, 打成 portable zip,
// Mac(Apple Silicon) 上配 JDK 17+ 即可运行。官方 .dmg/.app 仍需在 macOS 上用 :desktopApp:packageDmg 构建。
val macPortableRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.getByName("desktopRuntimeClasspath"))
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        attribute(Attribute.of("ui", String::class.java), "awt")
    }
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-linux-x64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-linux-arm64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-x64")
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-windows-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-arm64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-windows-x64")
}
dependencies {
    macPortableRuntimeClasspath(
        "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:${libs.versions.compose.get()}"
    )
}

val writeMacPortableLauncher by tasks.registering {
    val launcherFile = layout.buildDirectory.file("generated/macPortable/$appPackageName.sh")
    outputs.file(launcherFile)
    doLast {
        // 用 listOf + 普通字符串( \$ 转义字面 $ ) 避免三引号 raw string 把 bash 的 $VAR 当 Kotlin 模板。
        launcherFile.get().asFile.writeText(
            listOf(
                "#!/bin/bash",
                "set -e",
                "APP_HOME=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"",
                "JAVA_EXE=\"java\"",
                "if [ -x \"\$JAVA_HOME/bin/java\" ]; then JAVA_EXE=\"\$JAVA_HOME/bin/java\"; fi",
                "exec \"\$JAVA_EXE\" -cp \"\$APP_HOME/app/lib/*\" $appMainClass \"\$@\"",
                "",
            ).joinToString("\n")
        )
    }
}

val writeMacPortableReadme by tasks.registering {
    val readmeFile = layout.buildDirectory.file("generated/macPortable/README.txt")
    outputs.file(readmeFile)
    doLast {
        readmeFile.get().asFile.writeText(
            """
            $appPackageName macOS (Apple Silicon) portable bundle

            终端运行 ./$appPackageName.sh 启动(或右键打开)。

            本包含 macOS arm64 的 Compose/Skiko 原生渲染库, 但不含 JRE;
            需本机已安装 JDK 17+( JAVA_HOME 或 PATH 中的 java)。
            首次运行若被 Gatekeeper 拦截: xattr -dr com.apple.quarantine <解压目录>。

            这是「非 macOS 主机」的兜底产物。官方签名 .dmg/.app 仍需在 macOS 上构建。
            """.trimIndent() + "\n"
        )
    }
}

val prepareMacPortableBundle by tasks.registering(Sync::class) {
    dependsOn("desktopJar", writeMacPortableLauncher, writeMacPortableReadme)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(layout.buildDirectory.dir("macPortable/$appPackageName"))
    from(tasks.named("desktopJar")) {
        into("app/lib")
    }
    from(macPortableRuntimeClasspath) {
        into("app/lib")
    }
    from(writeMacPortableLauncher.map { it.outputs.files.singleFile })
    from(writeMacPortableReadme.map { it.outputs.files.singleFile })
    from("icons/mobius_logo.icns") {
        into("app")
    }
}

tasks.register<Zip>("packageMacPortableZip") {
    group = "distribution"
    description = "Builds a macOS Apple Silicon (arm64) portable zip without requiring a macOS host."

    dependsOn(prepareMacPortableBundle)
    archiveFileName.set("$appPackageName-macos-arm64-portable-$appPackageVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/macos-arm64-portable"))
    from(prepareMacPortableBundle.map { it.destinationDir }) {
        into(appPackageName)
    }
}
