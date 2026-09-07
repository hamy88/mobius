plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.huawei.agconnect) apply false
}

// 强制对齐所有 org.jetbrains.compose.* artifact 到同一版本，防止 jpackage 打包时
// conflict resolution 把 Desktop(JVM) runtime 升到与 compiler ABI 不兼容的版本。
// 只对齐 org.jetbrains.compose.*：它们共用同一发行号(1.7.3)。androidx.compose.* 使用
// 各自独立的版本号(如 material3 约 1.3.x，不存在 1.7.3)，强行 useVersion("1.7.3") 会让
// Android 端解析 404，因此绝不能碰 androidx 系。
allprojects {
    configurations.configureEach {
        resolutionStrategy {
            eachDependency {
                if (requested.group.startsWith("org.jetbrains.compose")) {
                    useVersion("1.7.3")
                }
            }
        }
    }
}
