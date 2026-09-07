#!/usr/bin/env bash
# =============================================================================
# build-mac-dmg.sh — 小莫助理 macOS 桌面安装包（.dmg）端到端构建脚本
# -----------------------------------------------------------------------------
# 为什么需要这个脚本：
#   DMG 是 macOS 专属格式，jpackage 依赖 macOS 的 hdiutil 打包。当前 Linux
#   构建环境（无 macOS、无 hdiutil）上 :desktopApp:packageDmg 会被 SKIPPED，
#   无法产出任何 DMG 产物。本脚本封装了「在 Mac 上拿到 DMG」的完整流程。
#
# 流程：
#   1. 前置检查（macOS / JDK 17+ / 可选 Apple 签名材料）
#   2. ./gradlew :desktopApp:packageDmg（compose desktop nativeDistributions）
#   3. 可选代码签名（MOMO_MAC_SIGN=true + MOMO_MAC_SIGNING_IDENTITY）
#   4. 计算 sha256，把 .dmg 与 checksum 暂存到 DATA_DIR（默认仓库根 data/）
#
# 用法示例（在 macOS 终端，仓库根目录下）：
#   # 未签名 DMG（最简单；用户首次打开需右键 → 打开）：
#   ./scripts/build-mac-dmg.sh
#
#   # Developer ID 签名（免去「无法验证开发者」警告；需先在钥匙串装好证书）：
#   MOMO_MAC_SIGN=true \
#     MOMO_MAC_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
#     ./scripts/build-mac-dmg.sh
#
#   # 同时做 Apple 公证（notarization），需 App-Specific Password：
#   MOMO_MAC_SIGN=true \
#     MOMO_MAC_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
#     APPLE_ID=you@example.com APPLE_ID_PASSWORD=xxxx-xxxx-xxxx-xxxx \
#     APPLE_TEAM_ID=TEAMID ./scripts/build-mac-dmg.sh
# =============================================================================
set -euo pipefail

# ---------- 1. 前置检查 ----------
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "❌ ERROR: DMG 只能在 macOS 上构建（jpackage 依赖 hdiutil）。当前系统: $(uname -s)" >&2
    echo "   请在一台 macOS（推荐 macOS 12+，JDK 17+）上运行本脚本。" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "❌ ERROR: 未找到 java。请先安装 JDK 17+（brew install --cask temurin@17）。" >&2
    exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | awk -F[\".] '/version/ {print $2; exit}')
if [[ "$JAVA_MAJOR" -lt 17 ]]; then
    echo "❌ ERROR: 需要 JDK 17+，当前为 $JAVA_MAJOR。" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
mkdir -p "$DATA_DIR"

# ---------- 2. 组装签名 / 公证参数 ----------
GRADLE_OPTS=()
if [[ "${MOMO_MAC_SIGN:-}" == "true" ]]; then
    if [[ -z "${MOMO_MAC_SIGNING_IDENTITY:-}" ]]; then
        echo "❌ ERROR: MOMO_MAC_SIGN=true 但未设置 MOMO_MAC_SIGNING_IDENTITY" >&2
        exit 1
    fi
    GRADLE_OPTS+=(
        "-Pcompose.desktop.mac.sign=true"
        "-Pcompose.desktop.mac.signing.identity=${MOMO_MAC_SIGNING_IDENTITY}"
    )
    # Apple 公证（可选）
    [[ -n "${APPLE_ID:-}" ]]               && export APPLE_ID
    [[ -n "${APPLE_ID_PASSWORD:-}" ]]      && export APPLE_ID_PASSWORD
    [[ -n "${APPLE_TEAM_ID:-}" ]]          && export APPLE_TEAM_ID
fi

# ---------- 3. 构建 DMG ----------
echo "▶ 构建 DMG：./gradlew :desktopApp:packageDmg ${GRADLE_OPTS[*]:-}"
# macOS 自带 bash 3.2 在 set -u 下展开空数组 ${GRADLE_OPTS[@]} 会报 unbound variable
# （bash 4.4+ 才修）。用 ${arr[@]+"${arr[@]}"} 兼容 3.2：数组非空才展开。
./gradlew :desktopApp:packageDmg ${GRADLE_OPTS[@]+"${GRADLE_OPTS[@]}"}

# ---------- 4. 定位产物并暂存 ----------
DMG_SRC="$(find "$REPO_ROOT/desktopApp/build/compose/binaries" -name '*.dmg' -type f 2>/dev/null | head -1)"
if [[ -z "$DMG_SRC" ]]; then
    echo "❌ ERROR: 构建结束但未找到 .dmg 产物（检查上方日志）。" >&2
    exit 1
fi

DMG_NAME="$(basename "$DMG_SRC")"
DMG_DST="$DATA_DIR/$DMG_NAME"
cp -f "$DMG_SRC" "$DMG_DST"

# ---------- 5. Apple 公证（Notarization）+ Staple ----------
if [[ "${MOMO_MAC_SIGN:-}" == "true" && -n "${APPLE_ID:-}" && -n "${APPLE_ID_PASSWORD:-}" && -n "${APPLE_TEAM_ID:-}" ]]; then
    echo
    echo "▶ 提交公证：xcrun notarytool submit ..."
    xcrun notarytool submit "$DMG_DST" \
        --apple-id    "${APPLE_ID}" \
        --password    "${APPLE_ID_PASSWORD}" \
        --team-id     "${APPLE_TEAM_ID}" \
        --wait \
        --timeout 30m
    echo "▶ 附加公证票据：xcrun stapler staple ..."
    xcrun stapler staple "$DMG_DST"
    echo "✅ 公证 + Staple 完成"
else
    echo
    echo "⚠️  跳过公证（APPLE_ID / APPLE_ID_PASSWORD / APPLE_TEAM_ID 未全部设置）。"
fi

SHA256="$(shasum -a 256 "$DMG_DST" | awk '{print $1}')"
printf '%s  %s\n' "$SHA256" "$DMG_NAME" > "$DMG_DST.sha256"

echo
echo "✅ DMG 构建完成"
echo "   产物 : $DMG_DST"
echo "   大小 : $(du -h "$DMG_DST" | awk '{print $1}')"
echo "   SHA256: $SHA256"
if [[ "${MOMO_MAC_SIGN:-}" != "true" ]]; then
    echo
    echo "⚠️  未签名 DMG（ad-hoc）："
    echo "   - 首次打开：macOS 提示「无法验证开发者」→ 右键 App → 打开。"
    echo "   - ⚠️ 麦克风权限：ad-hoc 签名无 Team ID，macOS 不会弹麦克风授权框、直接喂静音 buffer。"
    echo "     要让 DMG 端语音可用，必须用 Developer ID 签名（MOMO_MAC_SIGN=true + 证书）；"
    echo "     无证书请改用 java -jar（终端）方式运行，授权 Terminal 即可录音。"
fi
