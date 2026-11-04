#!/usr/bin/env bash
# 本地构建带完整协议常量的 release APK。
#
# 用法：
#   bash tools/build-keyed.sh              # 完整构建（单测+lint+assembleRelease）
#   bash tools/build-keyed.sh --no-test    # 跳过单测和 lint，只构建 APK
#   bash tools/build-keyed.sh --debug      # 构建 debug APK（快，不带签名）
#
# 依赖：
#   - .env（仓库根目录，已包含 10 个协议/MQTT 密钥）
#   - JDK 21、Android SDK（ANDROID_HOME 或 ANDROID_SDK_ROOT）
#   - client/keystore.properties + client/keystore/tlkey-release.jks（签名）
#
# 产物：
#   client/app/build/outputs/apk/release/app-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/.env"
CLIENT_DIR="$ROOT/client"

# ---- 参数解析 ----
SKIP_TEST=0
BUILD_DEBUG=0
for arg in "$@"; do
    case "$arg" in
        --no-test) SKIP_TEST=1 ;;
        --debug)   BUILD_DEBUG=1 ;;
        *) echo "未知参数: $arg" >&2; exit 1 ;;
    esac
done

# ---- 检查 .env ----
if [ ! -f "$ENV_FILE" ]; then
    echo "错误：找不到 .env（仓库根目录）。" >&2
    echo "    先运行逆向提取脚本生成 .env。" >&2
    exit 1
fi

# ---- 加载 .env ----
export $(grep -v '^#' "$ENV_FILE" | xargs)
echo "已加载 .env（$(grep -c '^[A-Z]' "$ENV_FILE") 个环境变量）"

# ---- 检查 Android SDK ----
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "警告：ANDROID_HOME / ANDROID_SDK_ROOT 未设置，Gradle 可能失败。" >&2
fi

# ---- 构建 ----
cd "$CLIENT_DIR"

if [ "$BUILD_DEBUG" -eq 1 ]; then
    echo ">>> 构建 debug APK（跳过签名）..."
    ./gradlew :app:assembleDebug --stacktrace
    APK="app/build/outputs/apk/debug/app-debug.apk"
elif [ "$SKIP_TEST" -eq 1 ]; then
    echo ">>> 跳过单测/lint，直接构建 release..."
    ./gradlew :app:assembleRelease --stacktrace
    APK="app/build/outputs/apk/release/app-release.apk"
else
    echo ">>> 完整构建（单测 + lint + release）..."
    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --stacktrace
    APK="app/build/outputs/apk/release/app-release.apk"
fi

# ---- 产物检查 ----
if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo ""
    echo "✓ 构建成功：$APK  ($SIZE)"
    echo "  路径：$CLIENT_DIR/$APK"
    if [ "$BUILD_DEBUG" -eq 0 ] && [ -f "$ROOT/client/keystore.properties" ]; then
        echo ""
        echo "签名 APK 验证（可选）："
        echo "  $ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs $APK"
    fi
else
    echo "✗ 构建失败：找不到产物 $APK" >&2
    exit 1
fi
