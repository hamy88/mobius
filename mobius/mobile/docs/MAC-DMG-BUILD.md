# macOS DMG 构建说明（小莫助理桌面版）

## 结论：DMG 必须在 macOS 上构建

DMG 是 macOS 专属安装包格式。Compose Multiplatform 的桌面原生打包（`nativeDistributions`）
底层使用 JDK 自带的 `jpackage`，而 `jpackage` 产出 DMG 时**依赖 macOS 的 `hdiutil`**——
该工具仅在 macOS 上存在。

当前 Linux 构建环境（无 macOS、无 `hdiutil`）上执行：

```
./gradlew :desktopApp:packageDmg
> Task :desktopApp:packageDmg SKIPPED
BUILD SUCCESSFUL
```

任务会被 `SKIPPED`，**无法产出任何 DMG**。这与 iOS 真机 IPA 的限制同源（参见
[IOS-BUILD.md](./IOS-BUILD.md)）。

> jpackage 的平台限制是硬性的：**在哪台机器上跑，就只能产那个平台的安装包**。
> Linux 上可产 `.deb`/`.rpm`，Windows 上产 `.exe`/`.msi`，macOS 上产 `.dmg`/`.pkg`。

## 取得 DMG 的完整步骤

在任意一台 macOS（推荐 macOS 12+，JDK 17+）上，仓库根目录执行：

### 方式 A：未签名 DMG（最快）

```bash
./scripts/build-mac-dmg.sh
```

产物：`data/MomoAssistant-1.0.0.dmg`（含 `.sha256`）。

> 未签名 DMG 首次打开 macOS 会提示「无法验证开发者」。安装后**右键 App → 打开**，
> 或在「系统设置 → 隐私与安全」点「仍要打开」即可放行。

### 方式 B：Developer ID 签名（免去警告，推荐分发）

需先在「钥匙串访问」导入 **Developer ID Application** 证书（从 Apple Developer 后台下载）。

```bash
MOMO_MAC_SIGN=true \
MOMO_MAC_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
./scripts/build-mac-dmg.sh
```

### 方式 C：签名 + Apple 公证（可被 Gatekeeper 直接放行）

在方式 B 基础上再加 Apple ID 与 App-Specific Password（在 appleid.apple.com 生成）：

```bash
MOMO_MAC_SIGN=true \
MOMO_MAC_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
APPLE_ID=you@example.com \
APPLE_ID_PASSWORD=xxxx-xxxx-xxxx-xxxx \
APPLE_TEAM_ID=TEAMID \
./scripts/build-mac-dmg.sh
```

## CI（可选）

`.github/workflows/` 若需在 CI 自动产 DMG，须使用 `macos-latest` runner，并按上面方式
通过 repository secrets 注入 `MOMO_MAC_SIGNING_IDENTITY` / `APPLE_ID` 等。

## 当前可在本 Linux 环境产出的桌面替代物

虽不能产 DMG，但以下可在本环境产出（供开发自测）：

| 任务 | 产物 | 说明 |
|---|---|---|
| `:desktopApp:packageDeb` | `.deb` | Debian/Ubuntu 安装包 |
| `:desktopApp:packageRpm` | `.rpm` | Fedora/RHEL 安装包 |
| `:desktopApp:packageUberJarForCurrentOS` | 可运行 `.jar` | 需目标机有 JRE |
| `:desktopApp:packageWindowsPortableZip` | Windows 便携 zip | 跨平台构建 Windows 便携包 |

**DMG 不在此列——只能上 Mac。**
