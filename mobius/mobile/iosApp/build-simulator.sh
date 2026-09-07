#!/bin/bash
# ============================================================================
# iOS 模拟器构建 → 安装 → 启动 (在 Mac 上跑)
# 用法: cd 到 momo-mobile 根目录, bash iosApp/build-simulator.sh
#       列出并选择模拟器: bash iosApp/build-simulator.sh --choose
#       指定模拟器型号: DEVICE="iPhone 15 Pro" bash iosApp/build-simulator.sh
#       只想构建不安装/启动: BUILD_ONLY=1 bash iosApp/build-simulator.sh
#       已安装时自动卸载重装(清旧数据), 无需额外参数
# ============================================================================
# 与 build-testflight.sh 的区别:
#   - 用 Debug 配置(模拟器不签名)
#   - destination 走 simulator(generic/platform=iOS Simulator,name=$DEVICE)
#   - 不打包成 ipa, 直接装 .app 到 simctl
#   - 不调用 altool
#   - **不**自增 CFBundleVersion —— 模拟器不需要; 已安装时先 uninstall(清旧数据)再 install。
#     build 号自增是 TestFlight 那边为防止"重复 build 被拒"的策略, 本地反复构建会污染
#     Info.plist 并抢走团队真实发版号。TestFlight 请用 build-testflight.sh。
# ============================================================================
# 一次性前提 (没配好会在对应步骤报错):
#   1. Mac: Xcode + `brew install xcodegen` + JDK17 + Android SDK(local.properties)
#   2. 至少装过一台 iOS 模拟器(Xcode → Settings → Platforms → iOS → 勾选版本)
# ============================================================================
# 踩过的坑:
#   A. 同 build-testflight.sh A 条: gradle 必须在真终端会话里跑(Terminal.app / aimux mac-terminal)。
#      headless 终端(ssh/aimux mac-pty)会让 gradle 卡 "single-use Daemon will be forked"。
#   B. 同 build-testflight.sh F 条: Info.plist 必须含 CADisableMinimumFrameDurationOnPhone=true
#      和 UIApplicationSceneManifest, 缺则启动即闪退; 已在 iosApp/iosApp/Info.plist 提交。
#   C. preBuildScript 跑 :shared:embedAndSignAppleFrameworkForXcode, 模拟器编译时同样会跑
#      (把 MomoShared.framework + compose-resources/ 同步进 app bundle)。如果只想编译 shared 不
#      嵌入给 Xcode, 用 SKIP_EMBED=1(默认不嵌, 当前脚本依赖 embed 才能运行, 慎用)。
#   D. 模拟器首选 iOS 17/18+ 的设备, 老设备(iPhone 8 等)跑 SwiftUI/Compose 可能黑屏。
# ============================================================================
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"          # momo-mobile 根
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
printf 'sdk.dir=%s/Library/Android/sdk\n' "$HOME" > local.properties

BUNDLE=cn.nutshellai.momo
DEVICE="${DEVICE:-iPhone 15 Pro}"               # 默认 iPhone 15 Pro, 可用 DEVICE=xxx 覆盖
BUILD_ONLY="${BUILD_ONLY:-0}"
APP_NAME="Mobius"

# 派生路径
BUILD_DIR="$(pwd)/iosApp/build/simulator"
APP_PATH="$BUILD_DIR/Build/Products/Debug-iphonesimulator/${APP_NAME}.app"

# ===== 模拟器列表 + 交互选择 =====
# 列出所有可用的 iOS 模拟器 (排除 Unavailable / Apple Watch / Apple TV)
list_simulators() {
  # 使用 while-read 逐行解析, 兼容 macOS bash 3.2 (无 mapfile) 和 BSD awk
  xcrun simctl list devices available 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      "-- iOS"*) in_ios=1; continue ;;
      "-- "*)    in_ios=0; continue ;;
    esac
    [ "${in_ios:-0}" = 1 ] || continue
    # 格式: "    iPhone 15 Pro (ABCD-EFGH-IJKL-MNOP) (Booted)"
    # 提取 UDID (括号内的 UUID)
    udid=$(echo "$line" | sed -n 's/.*(\([A-F0-9-]\{36\}\)) .*/\\1/p')
    [ -n "$udid" ] || continue
    # 提取设备名 (UDID 之前的内容, 去首尾空格)
    name=$(echo "$line" | sed 's/([A-F0-9-]\{36\}).*//' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$line" in
      *"(Booted)"*) status="Booted" ;;
      *)            status="Shutdown" ;;
    esac
    echo "$name|$udid|$status"
  done
}

# 交互式选择模拟器 (--choose 或未设置 DEVICE 时触发)
if [ "${1:-}" = "--choose" ] || [ "${1:-}" = "-c" ] || [ "${1:-}" = "--list" ] || [ "${1:-}" = "-l" ]; then
  echo "=== 可用 iOS 模拟器 ==="
  DEVICES=()
  while IFS= read -r line; do DEVICES+=("$line"); done < <(list_simulators)
  if [ ${#DEVICES[@]} -eq 0 ]; then
    echo "未找到可用的 iOS 模拟器。请在 Xcode → Settings → Platforms 安装 iOS 模拟器。" >&2
    exit 1
  fi
  for i in "${!DEVICES[@]}"; do
    IFS='|' read -r name udid status <<< "${DEVICES[$i]}"
    tag=""
    [ "$status" = "Booted" ] && tag="  ← 已启动"
    printf "  %2d) %-35s %s%s\n" $((i+1)) "$name" "$udid" "$tag"
  done

  if [ "${1:-}" = "--list" ] || [ "${1:-}" = "-l" ]; then
    echo ""
    echo "用法: DEVICE=\"设备名\" bash iosApp/build-simulator.sh"
    echo "      bash iosApp/build-simulator.sh --choose    (交互选择)"
    exit 0
  fi

  echo ""
  printf "选择模拟器 [1-%d] (默认 1): " ${#DEVICES[@]}
  read -r CHOICE
  CHOICE="${CHOICE:-1}"
  if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt ${#DEVICES[@]} ]; then
    echo "无效选择: $CHOICE" >&2
    exit 1
  fi
  IFS='|' read -r DEVICE _ _ <<< "${DEVICES[$((CHOICE-1))]}"
  echo "→ 已选择: $DEVICE"
elif [ -z "${DEVICE:-}" ] && [ -t 0 ]; then
  # 未指定 DEVICE 且 stdin 是终端: 自动进入交互选择
  echo "(未指定 DEVICE, 自动进入模拟器选择)"
  DEVICES=()
  while IFS= read -r line; do DEVICES+=("$line"); done < <(list_simulators)
  if [ ${#DEVICES[@]} -gt 0 ]; then
    for i in "${!DEVICES[@]}"; do
      IFS='|' read -r name udid status <<< "${DEVICES[$i]}"
      tag=""
      [ "$status" = "Booted" ] && tag="  ← 已启动"
      printf "  %2d) %-35s %s%s\n" $((i+1)) "$name" "$udid" "$tag"
    done
    echo ""
    printf "选择模拟器 [1-%d] (默认 1): " ${#DEVICES[@]}
    read -r CHOICE
    CHOICE="${CHOICE:-1}"
    if [[ "$CHOICE" =~ ^[0-9]+$ ]] && [ "$CHOICE" -ge 1 ] && [ "$CHOICE" -le ${#DEVICES[@]} ]; then
      IFS='|' read -r DEVICE _ _ <<< "${DEVICES[$((CHOICE-1))]}"
      echo "→ 已选择: $DEVICE"
    fi
  fi
fi
# 回退默认值
DEVICE="${DEVICE:-iPhone 15 Pro}"

echo "### [1/4] xcodegen 生成工程"
(cd iosApp && xcodegen generate --spec project.yml)

echo "### [2/4] build for simulator (Debug, 无签名; preBuildScript 自动构建 MomoShared.framework)"
xcodebuild \
  -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "generic/platform=iOS Simulator,name=$DEVICE" \
  -derivedDataPath "$BUILD_DIR" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  ONLY_ACTIVE_ARCH=YES build

echo "### [3/4] 校验产物: ${APP_PATH}"
if [ ! -d "$APP_PATH" ]; then
  echo "ERROR: 找不到 ${APP_PATH}, 编译可能失败。请往上翻 xcodebuild 输出。" >&2
  exit 1
fi
# 防旧病复发: 缺 compose-resources 启动即 MissingResourceException 闪退。
if ! unzip -l "$APP_PATH/${APP_NAME}" 2>/dev/null | grep -q "compose-resources"; then
  if ! find "$APP_PATH" -path "*compose-resources*" -type d | grep -q .; then
    echo "ERROR: ${APP_PATH} 里没找到 compose-resources/, 装上也会启动崩溃。" >&2
    exit 1
  fi
fi
echo "    OK: 含 compose-resources"

if [ "$BUILD_ONLY" = "1" ]; then
  echo "### ✅ BUILD_ONLY=1, 跳过安装/启动。产物: ${APP_PATH}"
  exit 0
fi

echo "### [4/4] 模拟器: 启动 → 安装 → 打开"
# 5.1 拿一台可用的模拟器 UDID: 优先选状态为 Booted 的; 否则按 DEVICE 名搜一台 Shutdown 的并 boot。
UDID="$(xcrun simctl list devices booted 2>/dev/null | awk -F'[()]' '/Booted/{print $2; exit}' || true)"
if [ -z "${UDID:-}" ]; then
  echo "    没有已启动的模拟器, 找一台 ${DEVICE}..."
  UDID="$(xcrun simctl list devices available 2>/dev/null | awk -F'[()]' "/$DEVICE \\(/{print \$2; exit}" || true)"
  if [ -z "${UDID:-}" ]; then
    echo "ERROR: 找不到名为 '$DEVICE' 的可用模拟器。运行 'xcrun simctl list devices available' 看现有设备," >&2
    echo "       或用 DEVICE=\"iPhone 15 Pro\" 等显式指定一台。" >&2
    exit 1
  fi
  echo "    boot ${DEVICE} (UDID: $UDID)..."
  xcrun simctl boot "$UDID" || true
  # boot 后等待 simulator 服务就绪
  xcrun simctl bootstatus "$UDID" -b 2>/dev/null || true
fi
echo "    UDID: $UDID"

# 5.2 安装: 已安装则先 terminate + uninstall(清旧数据/缓存), 再装新版(干净重装)
EXISTING="$(xcrun simctl listapps "$UDID" 2>/dev/null | grep -c "\"$BUNDLE\"" || true)"
if [ "${EXISTING:-0}" -gt 0 ]; then
  echo "    检测到已安装 $BUNDLE, terminate + uninstall (清旧数据/缓存) 再装新版..."
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  xcrun simctl uninstall "$UDID" "$BUNDLE"
fi
xcrun simctl install "$UDID" "$APP_PATH"
echo "    安装完成"

# 5.3 启动: -e 可模拟启动参数(空); --console-pty 把 app stdout/stderr 接到终端, 调试崩溃方便
echo "    启动 $BUNDLE..."
xcrun simctl launch --console-pty "$UDID" "$BUNDLE" &
LAUNCH_PID=$!

# 5.4 同时把 Simulator.app 拉到前台, 让用户看到窗口
open -a Simulator

# 提示退出方式
cat <<EOF

### ✅ 完成
   - 模拟器: ${DEVICE} (${UDID})
   - 应用:   ${APP_PATH}
   - 日志:   xcrun simctl spawn $UDID log stream --predicate 'process == "$APP_NAME"'
   - 停止:   xcrun simctl terminate $UDID $BUNDLE
   - 卸载:   xcrun simctl uninstall $UDID $BUNDLE
   - 后台日志流已附到本终端(本 shell 退出后结束)

EOF

# 等模拟器进程退出(Ctrl+C 也可中断)
wait "$LAUNCH_PID" || true
