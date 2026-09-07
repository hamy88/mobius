#!/usr/bin/env bash
# =============================================================================
# build-ios-ipa.sh — 小莫助理 iOS 真机安装包（.ipa）端到端构建脚本
# -----------------------------------------------------------------------------
# 为什么需要这个脚本：
#   iOS 真机 IPA 必须在 macOS + Xcode 上构建，且需要 Apple 代码签名材料。
#   当前 Linux 环境（无 Xcode、无签名材料、Kotlin/Native 已禁用 iOS target）
#   无法产出任何 iOS 产物。本脚本封装了「在 Mac 上拿到真机 IPA」的完整流程。
#
# 流程：
#   1. 前置检查（macOS / Xcode / XcodeGen / Java17 / 签名材料）
#   2. （可选）导入 Distribution .p12 到登录钥匙串
#   3. Gradle 构建 arm64 真机 MomoShared.framework
#   4. 复制框架 → iosApp/Frameworks/，XcodeGen 生成 Xcode 工程
#   5. xcodebuild archive（generic/platform=iOS，手动签名）
#   6. xcodebuild -exportArchive 导出 .ipa（按 method 生成 ExportOptions.plist）
#   7. 计算 sha256，把 .ipa 与 checksum 暂存到 DATA_DIR（默认仓库根 data/）
#
# 用法示例（在 macOS 终端，仓库根目录下）：
#   # 自动签名（Xcode 已托管证书 + Provisioning Profile，最简单）：
#   TEAM_ID=ABCDE12345 ./scripts/build-ios-ipa.sh
#
#   # 手动签名（指定 provisioning profile 名称）：
#   TEAM_ID=ABCDE12345 PP_NAME="momo_ios_distribution" \
#     SIGNING_METHOD=app-store ./scripts/build-ios-ipa.sh
#
#   # 连同 .p12 一起导入并构建：
#   ./scripts/build-ios-ipa.sh --import-p12 ~/dist.p12:$(cat ~/.pp_pass)
#
# 完整参数见下方 USAGE 或 `./scripts/build-ios-ipa.sh -h`。
# =============================================================================
set -euo pipefail

# ---- 默认配置（可被同名环境变量覆盖）-----------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${CONFIG:-Release}"                       # Release / Debug
BUNDLE_ID="${BUNDLE_ID:-cn.nutshellai.momo}"  # 必须与 provisioning profile 匹配
TEAM_ID="${TEAM_ID:-}"                            # Apple Developer Team ID（如 ABCDE12345）
SIGNING_STYLE="${SIGNING_STYLE:-manual}"          # manual / automatic
SIGNING_METHOD="${SIGNING_METHOD:-app-store}"     # app-store / ad-hoc / development / enterprise
PP_NAME="${PP_NAME:-}"                            # provisioning profile 名称（manual 时可留空走 uuid）
PP_UUID="${PP_UUID:-}"                            # provisioning profile UUID（与 PP_NAME 二选一）
SIGN_CERT="${SIGN_CERT:-iPhone Distribution}"     # 签名证书名（automatic 时可忽略）
FRAMEWORK_TASK="${FRAMEWORK_TASK:-linkReleaseFrameworkIosArm64}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
SCHEME="iosApp"
IOSAPP_DIR="$REPO_ROOT/iosApp"
BUILD_DIR="$IOSAPP_DIR/build"
ARCHIVE_PATH="$BUILD_DIR/iosApp.xcarchive"
EXPORT_PATH="$BUILD_DIR/ipa"
IMPORT_P12=""

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

环境变量参数：
  TEAM_ID          Apple Developer Team ID（强烈建议设置）
  SIGNING_STYLE    manual（默认）/ automatic（Xcode 托管签名）
  SIGNING_METHOD   app-store（默认）/ ad-hoc / development / enterprise
  PP_NAME          provisioning profile 名称（manual）
  PP_UUID          provisioning profile UUID（manual，与 PP_NAME 二选一）
  SIGN_CERT        签名证书名，默认 "iPhone Distribution"
  BUNDLE_ID        默认 com.mobius.momo.iosApp（须与 profile 匹配）
  CONFIG           Release（默认）/ Debug
  DATA_DIR         IPA 暂存目录，默认 仓库根/data
  --import-p12 F:P  导入 Distribution .p12（路径:密码）到登录钥匙串
  -h               显示本帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --import-p12) IMPORT_P12="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "未知参数: $1" >&2; usage; exit 2;;
  esac
done

# ---- 颜色 --------------------------------------------------------------------
c_red()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
c_cyn()  { printf '\033[36m%s\033[0m\n' "$*"; }
step()   { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die()    { c_red "✗ $*"; exit 1; }

# =============================================================================
# 1) 前置检查
# =============================================================================
step "1/7 前置检查"

[[ "$(uname -s)" == "Darwin" ]] || die "必须在 macOS 上运行（当前: $(uname -s)）。iOS 真机 IPA 需要 Xcode + iOS SDK。"
command -v xcodebuild >/dev/null || die "未找到 xcodebuild，请先安装 Xcode 并运行 sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
command -v xcodegen   >/dev/null || { c_ylw "未找到 xcodegen，正在通过 brew 安装..."; brew install xcodegen || die "安装 xcodegen 失败，请手动 brew install xcodegen"; }
command -v java       >/dev/null || die "未找到 java，请安装 JDK 17。"
[[ -d "$IOSAPP_DIR" ]] || die "未找到 iosApp 目录: $IOSAPP_DIR（请在仓库根运行本脚本）"

# 自动选择最新 Xcode
LATEST_XCODE="$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -1 || true)"
if [[ -n "$LATEST_XCODE" ]]; then
  c_cyn "选择 Xcode: $LATEST_XCODE"
  sudo xcode-select -s "$LATEST_XCODE/Contents/Developer" 2>/dev/null || true
fi
xcodebuild -version | sed 's/^/    /'

# 签名材料检查
if [[ "$SIGNING_STYLE" == "automatic" ]]; then
  [[ -n "$TEAM_ID" ]] || die "automatic 签名也需提供 TEAM_ID。"
else
  [[ -n "$TEAM_ID" ]] || die "缺少 TEAM_ID。export TEAM_ID=你的AppleTeamID"
  [[ -n "$PP_NAME" || -n "$PP_UUID" ]] || die "manual 签名需提供 PP_NAME 或 PP_UUID（provisioning profile）。"
fi
c_grn "前置检查通过。"

# =============================================================================
# 2) （可选）导入 .p12
# =============================================================================
if [[ -n "$IMPORT_P12" ]]; then
  step "2/7 导入 Distribution .p12"
  p12_file="${IMPORT_P12%%:*}"
  p12_pass="${IMPORT_P12#*:}"
  [[ "$p12_file" == "$p12_pass" ]] && p12_pass=""   # 未带密码
  [[ -f "$p12_file" ]] || die "p12 文件不存在: $p12_file"
  # 锁定后导入（CI 友好）
  security import "$p12_file" -P "$p12_pass" -A -t cert -k ~/Library/Keychains/login.keychain-db \
    || die "导入 p12 失败（密码错误？钥匙串不可达？）"
  c_grn ".p12 已导入登录钥匙串。"
else
  step "2/7 跳过 .p12 导入（--import-p12 未提供）"
fi

# =============================================================================
# 3) 构建 arm64 真机框架
# =============================================================================
step "3/7 构建 MomoShared.framework ($CONFIG / iosArm64)"
cd "$REPO_ROOT"
./gradlew --no-daemon ":shared:$FRAMEWORK_TASK"
FW_SRC="$REPO_ROOT/shared/build/bin/iosArm64/$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')Framework/MomoShared.framework"
[[ -d "$FW_SRC" ]] || die "框架未生成: $FW_SRC"
c_grn "框架就绪: $FW_SRC"

# =============================================================================
# 4) 复制框架 + XcodeGen
# =============================================================================
step "4/7 复制框架 → iosApp/Frameworks 并生成 Xcode 工程"
mkdir -p "$IOSAPP_DIR/Frameworks"
rm -rf "$IOSAPP_DIR/Frameworks/MomoShared.framework"
cp -R "$FW_SRC" "$IOSAPP_DIR/Frameworks/"
( cd "$IOSAPP_DIR" && xcodegen generate )
PROJ="$IOSAPP_DIR/iosApp.xcodeproj"
[[ -d "$PROJ" ]] || die "xcodegen 未生成 $PROJ"
c_grn "Xcode 工程已生成: $PROJ"

# =============================================================================
# 5) Archive
# =============================================================================
step "5/7 xcodebuild archive（generic/platform=iOS）"
SIGN_IDENTITY="$SIGN_CERT"
ARCHIVE_EXTRA=()
if [[ "$SIGNING_STYLE" == "automatic" ]]; then
  ARCHIVE_EXTRA+=(CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM="$TEAM_ID")
else
  ARCHIVE_EXTRA+=(CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$TEAM_ID" CODE_SIGN_IDENTITY="$SIGN_IDENTITY")
  if [[ -n "$PP_UUID" ]]; then
    ARCHIVE_EXTRA+=("PROVISIONING_PROFILE_SPECIFIER=" "PROVISIONING_PROFILE_UUID=$PP_UUID")
  else
    ARCHIVE_EXTRA+=("PROVISIONING_PROFILE_SPECIFIER=$PP_NAME")
  fi
fi
rm -rf "$ARCHIVE_PATH"
ALLOW_PROV=()
[[ "$SIGNING_STYLE" == "automatic" ]] && ALLOW_PROV+=(-allowProvisioningUpdates)
xcodebuild archive \
  -project "$PROJ" \
  -scheme "$SCHEME" \
  -configuration "$CONFIG" \
  -archivePath "$ARCHIVE_PATH" \
  -destination "generic/platform=iOS" \
  -sdk iphoneos \
  "${ARCHIVE_EXTRA[@]}" \
  ${ALLOW_PROV[@]+"${ALLOW_PROV[@]}"} \
  || die "archive 失败。检查签名证书/provisioning profile 是否匹配 BUNDLE_ID=$BUNDLE_ID"
c_grn "归档完成: $ARCHIVE_PATH"

# =============================================================================
# 6) 导出 IPA
# =============================================================================
step "6/7 xcodebuild -exportArchive 导出 .ipa"
EXPORT_OPTS="$BUILD_DIR/ExportOptions.plist"
mkdir -p "$BUILD_DIR"
PROF_BLOCK=""
if [[ "$SIGNING_STYLE" == "manual" ]]; then
  PROF_KEY="${PP_UUID:-$PP_NAME}"
  PROF_VAL="$PP_NAME"
  [[ -z "$PROF_VAL" && -n "$PP_UUID" ]] && PROF_VAL="<use-uuid>"
  PROF_BLOCK="<key>provisioningProfiles</key>
    <dict>
        <key>$BUNDLE_ID</key>
        <string>${PP_NAME:-$PP_UUID}</string>
    </dict>"
fi
cat > "$EXPORT_OPTS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>$SIGNING_METHOD</string>
    <key>teamID</key>
    <string>$TEAM_ID</string>
    <key>signingStyle</key>
    <string>$SIGNING_STYLE</string>
    <key>signingCertificate</key>
    <string>$SIGN_CERT</string>
    $PROF_BLOCK
    <key>stripSwiftSymbols</key>
    <true/>
    <key>compileBitcode</key>
    <false/>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
</dict>
</plist>
EOF
rm -rf "$EXPORT_PATH"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportPath "$EXPORT_PATH" \
  -exportOptionsPlist "$EXPORT_OPTS" \
  || die "导出 IPA 失败。请检查 ExportOptions.plist 与签名材料。"
IPA="$(find "$EXPORT_PATH" -name '*.ipa' | head -1)"
[[ -n "$IPA" ]] || die "未找到导出的 .ipa"
c_grn "IPA 已生成: $IPA"

# =============================================================================
# 7) 暂存到 DATA_DIR + sha256
# =============================================================================
step "7/7 暂存到 data/ 并计算 sha256"
mkdir -p "$DATA_DIR"
STAMP="$(date -u +%Y%m%d-%H%M)"
STAGED="$DATA_DIR/momo-mobile-ios-$SIGNING_METHOD-$STAMP.ipa"
cp "$IPA" "$STAGED"
( cd "$DATA_DIR" && shasum -a 256 "$(basename "$STAGED")" > "$(basename "$STAGED").sha256" )
c_grn "完成 ✅"
echo "  IPA     : $STAGED"
echo "  校验和  : $STAGED.sha256"
echo "  bundleId: $BUNDLE_ID  method: $SIGNING_METHOD  team: $TEAM_ID"
