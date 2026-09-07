# iOS 安装包（.ipa）构建说明

> 本文档解释**为什么本环境没有直接产出 iOS 安装包**，以及**如何拿到真机可安装的 IPA**。
> 本次会话交付物：`scripts/build-ios-ipa.sh`（端到端构建脚本）+ 本说明。

---

## 1. 结论：当前环境无法产出真机 iOS IPA

在此次会话的 Linux 环境（`uname` = `Linux x86_64`）中，**没有任何 iOS 产物可被生成**，
真机可安装的 `.ipa` 更不可能。这是 **Apple + Kotlin/Native 的双重硬约束**，与代码本身无关：

| 检查项 | 实测结果 | 后果 |
|---|---|---|
| 构建机平台 | `Linux 6.8.0 x86_64`，无 `xcodebuild`/`swift` | 无法链接/签名 iOS App |
| 桥接远程机 `fuzhengping` | `platform: linux`，且 `disconnected` | 即使上线也是 Linux，无 macOS/Xcode |
| Apple 签名材料 | **零**：无 Team ID / 无 `.p12` / 无 `.mobileprovision`（全仓 + 环境变量扫描确认） | 无法产出真机可安装 IPA |
| Kotlin/Native iOS target | 构建日志明确：`The following Kotlin/Native targets cannot be built on this machine and are disabled` | 连 `MomoShared.framework` 都无法在此交叉编译 |
| CI 工作流 | `.github/workflows/build-all-formats.yml` 仅有 `ios-simulator-app` 目标，**无 IPA 目标** | CI 也只产模拟器 `.app`，非真机 IPA |

> ⚠️ 三个不可绕过的前提：**① macOS + Xcode 主机**、**② Apple Developer Team ID**、
> **③ 与 bundle id 匹配的 Distribution 证书 + Provisioning Profile**。缺一不可。
> 没有签名材料的「IPA」要么无法安装，要么是模拟器 `.app`（README 第 148 行：
> "Simulator `.app` 的 ZIP 不是 IPA"）。

为避免交付一个**无法安装的假包**（例如把模拟器 `.app` 压缩后改后缀为 `.ipa`），
本次不伪造产物，而是交付可直接在 Mac 上产出真机 IPA 的完整脚本。

---

## 2. 交付物

| 文件 | 位置 | 作用 |
|---|---|---|
| `scripts/build-ios-ipa.sh` | 仓库（已入库） | macOS 上端到端构建真机 IPA 的脚本 |
| `docs/IOS-BUILD.md` | 仓库（已入库） | 本说明 |
| `data/IOS-BUILD-README.md` | 仓库根 `data/`（.gitignore） | 用户面向指引（IPA 最终落地目录） |

最终产出的 `.ipa` 与 `*.sha256` 会落到 **仓库根 `data/`**（与现有 Android APK 同目录）。

---

## 3. 在 Mac 上拿到真机 IPA（推荐：用脚本）

### 3.1 准备 Apple 签名材料
1. 拥有 Apple Developer 账号 → 取得 **Team ID**（如 `ABCDE12345`）。
2. 创建 **Distribution 证书**（App Store 或 Ad Hoc），导出为 `.p12` 并记下密码。
3. 创建 **Provisioning Profile**，bundle id 为 `com.mobius.momo.iosApp`，关联上一步证书。
4. 在 Mac 上：安装 Xcode（App Store）、`brew install xcodegen`、安装 JDK 17。

### 3.2 运行脚本

```bash
cd <仓库根>      # clever_wave/momo-mobile 或 worktree

# 方式 A：自动签名（Xcode 已托管证书 + Profile，最简单）
TEAM_ID=ABCDE12345 ./scripts/build-ios-ipa.sh

# 方式 B：手动签名（指定 provisioning profile 名称 + 分发方式）
TEAM_ID=ABCDE12345 PP_NAME="momo_ios_distribution" \
  SIGNING_METHOD=app-store ./scripts/build-ios-ipa.sh

# 方式 C：连 .p12 一起导入（CI / 全新机器）
./scripts/build-ios-ipa.sh --import-p12 ~/dist.p12:你的p12密码
```

脚本会自动：构建 arm64 真机框架 → XcodeGen 生成工程 → `xcodebuild archive` →
`-exportArchive` 导出 IPA → 计算 sha256 → 暂存到 `data/`。

完整参数：`./scripts/build-ios-ipa.sh -h`

### 3.3 `SIGNING_METHOD` 选用
- `app-store`：上架 App Store / TestFlight（最常用）
- `ad-hoc`：分发给指定设备 UDID 列表
- `development`：开发机调试
- `enterprise`：企业内部分发（需企业账号）

---

## 4. 让 CI 自动产出 IPA（备选）

仓库 README 提到 `momo-ios-ipa-*`（仅有 Apple 签名 secrets 时）由
`.github/workflows/momo-mobile-build.yml` 产出。若希望在 GitHub Actions 上自动出包，
在该 workflow 中配置以下 **Repository Secrets**：

| Secret | 说明 |
|---|---|
| `IOS_TEAM_ID` | Apple Team ID |
| `IOS_DIST_P12_BASE64` | `base64` 编码的 Distribution `.p12` |
| `IOS_DIST_P12_PASSWORD` | `.p12` 密码 |
| `IOS_PP_BASE64` | `base64` 编码的 `.mobileprovision` |
| `IOS_BUNDLE_ID` | `com.mobius.momo.iosApp`（与 profile 匹配） |

CI job 在 `macos-14` runner 上：解码 secrets → 导入钥匙串 → 复用
`scripts/build-ios-ipa.sh` 的 archive/export 逻辑。

> 现有 `build-all-formats.yml` 只有模拟器目标，需新增 `ios-ipa` 矩阵项；
> 该改动可放在后续单独 PR。

---

## 5. 没有签名材料时的临时验证手段（非安装包）

仅在 macOS 上、用于**本地验证**，产物**不能装到真机**：

```bash
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
mkdir -p iosApp/Frameworks
cp -R shared/build/bin/iosSimulatorArm64/debugFramework/MomoShared.framework iosApp/Frameworks/
cd iosApp && xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

产出为模拟器 `.app`，可在 iOS Simulator 运行，但**不是 IPA，不可装真机**。

---

## 6. 本次会话动作记录
- 设置 worktree（分支 `6a388790`）。
- 排查：平台 / Xcode / aimux 桥接 / 签名材料 / Kotlin-Native target / CI 目标——均证实本机不可构建 iOS。
- 实测 `:shared:linkDebugFrameworkIosArm64` → 任务 `SKIPPED`，因 iOS target 在本机被禁用。
- 编写 `scripts/build-ios-ipa.sh`（`bash -n` 通过，`-h` 正常）。
- 合并 `6a388790` → `agent_smart_dev`。
