#!/bin/bash
# ============================================================================
# iOS 构建 → 上传 TestFlight 一条龙 (在 Mac 上跑)
# 用法: cd 到 momo-mobile 根目录, bash iosApp/build-testflight.sh
#       只想本地验证构建/导出、不真正上传: SKIP_UPLOAD=1 bash iosApp/build-testflight.sh
# ============================================================================
# 架构: project.yml 里 iosApp target 有 preBuildScripts 跑
#   `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, 归档(archive)时 Xcode 会自动
#   构建+嵌入+签名 MomoShared.framework 并把 Compose 资源同步进 app bundle
#   (compose-resources/)。故本脚本不再手动 link/copy framework(旧做法已废弃);
#   缺 compose-resources 的包启动即 MissingResourceException 闪退, 导出步会校验拦下。
# ============================================================================
# 一次性前提 (没配好会在对应步骤报错):
#   1. Mac: Xcode + `brew install xcodegen` + JDK17 + Android SDK(local.properties)
#   2. Keychain 里有分发证书: "Apple Distribution: NutShell AI(Beijing)Technology Co., Ltd (6FMVHL6RLY)"
#   3. 已装 App Store 分发 profile "file-xiao" (bundle id: cn.nutshellai.momo),
#      位于 ~/Library/Developer/Xcode/UserData/Provisioning Profiles/
#   4. ASC API key 的 .p8 放在: ~/private_keys/AuthKey_3BK9Z2DVQ7.p8
#   5. App Store Connect 里已有 App: cn.nutshellai.momo (团队 6FMVHL6RLY)
# ============================================================================
# 踩过的坑 (排错必看):
#   A. gradle/编译必须在"真终端会话"里跑(Terminal.app, 或 aimux 的 mac-terminal profile)。
#      在 headless 终端(ssh / aimux mac-pty)里, macOS java 启动器会在 daemon fork 时死锁
#      (CreateExecutionEnvironment 卡死), 表现为 gradle 永远停在"single-use Daemon will be forked"。
#   B. 别在临时 git clone 里跑: 某些 compose klib 解析状态会导致 ComposeUIViewController 等
#      符号报"unresolved"假阳性(符号明明在 klib 里)。用真实工程目录(本仓库 checkout)跑, 环境正常。
#   C. 签名团队是 6FMVHL6RLY (NutShell AI)。仓库里旧的 ExportOptions.plist 曾写错成 T72Y4DY2FA
#      / "junwei Huang" / com.app.momo —— 都是错的; 本脚本会重写 ExportOptions.plist, 勿手改回旧的。
#   D. 签名必须用「手动 + Apple Distribution + profile file-xiao」。切勿用 CODE_SIGN_STYLE=Automatic:
#      Automatic 默认走 Development 签名, 需要注册设备 UDID; 本团队未注册任何设备, Development 会报
#      "no devices / No profiles"。App Store 分发(Distribution)不需要设备, 所以必须手动指定 Distribution。
#      (Automatic + 手动指定 CODE_SIGN_IDENTITY=Distribution 会冲突报 "conflicting provisioning settings"。)
#   E. CLI 的 -allowProvisioningUpdates 不会自动用 Xcode GUI 登录的 Apple ID; 上传(altool)靠 ASC API key
#      (.p8), 归档(archive)签名靠 keychain 里已装的分发证书 + 已安装的 profile。
#   F. 启动崩溃根因: Compose Multiplatform 的 PlistSanityCheck 要求 Info.plist 含
#      `CADisableMinimumFrameDurationOnPhone=true`(120Hz) 和 `UIApplicationSceneManifest`。
#      这两个键已在 iosApp/iosApp/Info.plist 里(已提交), 勿删; 删了启动即闪退。
#   G. altool 上传到 Apple 对网络敏感(连 northamerica-1.object-storage.apple.com), 偶发"network connection
#      lost / Checksums do not match"; 本脚本上传步会自动重试 3 次, 仍失败就换稳定网络或用 Transporter app。
# ============================================================================
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"          # momo-mobile 根
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
printf 'sdk.dir=%s/Library/Android/sdk\n' "$HOME" > local.properties

# —— 配置 (团队 / profile / ASC API key) ——
TEAM=6FMVHL6RLY                                # NutShell AI
PROFILE=file-xiao                              # App Store 分发 profile 名
KEYID=3BK9Z2DVQ7                               # ASC API Key ID (.p8 真正的密钥, 不进仓库)
ISSUER=7a7938e7-2e0f-46da-bd67-0ae44f1729ad    # ASC Issuer ID
BUNDLE=cn.nutshellai.momo
PLIST=iosApp/iosApp/Info.plist
ARCH=/tmp/iosApp.xcarchive
OUT=/tmp/ipa

echo "### [1/6] ExportOptions.plist (团队/证书/profile)"
cat > iosApp/ExportOptions.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>$TEAM</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key><dict>
    <key>$BUNDLE</key><string>$PROFILE</string>
  </dict>
</dict></plist>
EOF

echo "### [2/6] 自增 build 号 (CFBundleVersion) —— 避免手动改/TestFlight 拒收重复 build"
CUR="$(perl -0777 -ne 'print $1 if /<key>CFBundleVersion<\/key>\s*<string>(\d+)<\/string>/' "$PLIST" || true)"
CUR="${CUR:-0}"
NEXT=$((CUR + 1))
perl -0777 -pi -e "s/(<key>CFBundleVersion<\/key>\s*<string>)\d+(<\/string>)/\${1}${NEXT}\${2}/" "$PLIST"
echo "    CFBundleVersion: ${CUR} -> ${NEXT}  (已写入 $PLIST)"

echo "### [3/6] xcodegen 生成工程"
(cd iosApp && xcodegen generate --spec project.yml)

echo "### [4/6] archive (手动签名, 几分钟; preBuildScript 会自动构建+嵌入 MomoShared.framework)"
xcodebuild archive \
  -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -archivePath "$ARCH" -destination "generic/platform=iOS" \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Manual \
  CODE_SIGN_IDENTITY="Apple Distribution" "PROVISIONING_PROFILE_SPECIFIER=$PROFILE"

echo "### [5/6] export ipa"
rm -rf "$OUT" && mkdir -p "$OUT"
xcodebuild -exportArchive -archivePath "$ARCH" -exportOptionsPlist iosApp/ExportOptions.plist -exportPath "$OUT"
IPA="$(ls "$OUT"/*.ipa | head -1)"; echo "ipa: $IPA"

# 防旧病复发: 缺 compose-resources 的包启动即 MissingResourceException 闪退, 不要上传。
# 注意: 不能 grep -q, 它命中即退出会让 unzip 收到 SIGPIPE, 配合 pipefail 误判失败。
if ! unzip -l "$IPA" | grep "compose-resources/composeResources" >/dev/null; then
  echo "ERROR: ipa 里没有 compose-resources/, 说明 Kotlin 资源没同步进 bundle, 请勿上传!" >&2
  exit 1
fi
echo "OK: ipa 含 compose-resources, 可上传"

echo "### [6/6] 上传 TestFlight (失败自动重试 3 次; SKIP_UPLOAD=1 跳过)"
if [ "${SKIP_UPLOAD:-0}" = "1" ]; then
  echo "(SKIP_UPLOAD=1, 跳过上传; ipa 在 $OUT)"
else
  cp "$IPA" /tmp/momo.ipa                        # 统一成 ASCII 路径, altool 更稳
  for i in 1 2 3; do
    echo "    上传尝试 $i/3 ..."
    if xcrun altool --upload-app --type ios -f /tmp/momo.ipa --apiKey "$KEYID" --apiIssuer "$ISSUER"; then
      echo "### ✅ 完成 — build ${NEXT} 已传, 几分钟后 App Store Connect → TestFlight 可见"
      exit 0
    fi
    [ "$i" -lt 3 ] && { echo "    尝试 $i 失败, 等 10s 重试..." >&2; sleep 10; }
  done
  echo "### ❌ 上传 3 次均失败 — 多半是网络(连 Apple 不稳)。换稳定网络/关代理/或用 Transporter app 拖 /tmp/momo.ipa 重传" >&2
  exit 1
fi
