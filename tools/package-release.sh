#!/bin/bash
# ============================================================================
# 发布打包脚本：构建 → 组装发布目录 → 产出 zip
# 职责边界：build-release.sh 负责产出干净的 jar，本脚本只做组装与打包。
# 用法：仓库根目录执行 bash tools/package-release.sh
# 产物：dist/novel-factory-<VERSION>.zip（解压即用的完整发布物）
# ============================================================================
set -e
cd "$(dirname "$0")/.."

# ---- 版本号：与 git tag 保持一致，发版时改这里 ----
VERSION=v1.3.1
DIST="dist/$VERSION"
ZIP="dist/novel-factory-$VERSION.zip"

# ---- 1. 复用构建脚本：产物只信脚本，不信任 target/ 里可能过期的东西 ----
bash tools/build-release.sh

# ---- 2. 清场重建：防止上次组装的残留混进本次 zip ----
rm -rf "$DIST"
rm -f "$ZIP"
mkdir -p "$DIST/config"

# ---- 3. 按清单组装 ----
cp novel_factory-app/target/novel_factory-app.jar "$DIST/"
cp tools/start.bat tools/stop.bat "$DIST/"
cp config/application-local.example.yml "$DIST/config/"
cp docs/RELEASE-CHECKLIST.md README.md LICENSE "$DIST/"

# ---- 4. 模板头部改写：仓库版模板写的是"复制到 src/main/resources"（面向源码开发者），
#         zip 用户没有 src 目录，正确用法是放在 jar 旁的 config/ 下。
#         用 awk 原地改写前 12 行说明区，避免 sed 处理中文的转义坑。 ----
awk 'BEGIN{replaced=0}
     /^# 用法：复制到/{ if(!replaced){ print "# 用法：复制本文件为同目录下的 application-local.yml（即 config/application-local.yml），"; print "#      填入你的 API Key 后保存，双击 start.bat 即可。无需重新打包。"; replaced=1; next } }
     /^# 加载方式：/{ next }
     /^#           它排在/{ next }
     { print }' "$DIST/config/application-local.example.yml" > "$DIST/config/application-local.example.yml.tmp"
mv "$DIST/config/application-local.example.yml.tmp" "$DIST/config/application-local.example.yml"

# ---- 5. 打包：优先用 zip（Git Bash 自带），没有则退 PowerShell ----
# Compress-Archive 只认 .zip 后缀的目标，先压成 <名>.zip 再挪到最终名
if command -v zip >/dev/null 2>&1; then
    (cd dist && zip -qr "novel-factory-$VERSION.zip" "$VERSION")
else
    PS_DIST='dist/'"${VERSION}"'.zip'
    powershell -NoProfile -Command "Compress-Archive -Path 'dist/$VERSION' -DestinationPath '$PS_DIST' -Force"
    mv "dist/$VERSION.zip" "$ZIP" 2>/dev/null || true
fi

# ---- 6. 产物自检：zip 在位 + 内容清单 + 密钥零容忍扫描 ----
if [ ! -f "$ZIP" ]; then
    echo "❌ 打包失败：未找到 $ZIP"
    exit 1
fi
echo "✅ 发布包已产出：$ZIP（$(du -h "$ZIP" | cut -f1)）"
echo "---- zip 内容清单 ----"
if command -v unzip >/dev/null 2>&1; then
    unzip -l "$ZIP"
else
    powershell -NoProfile -Command "[System.IO.Compression.ZipFile]::OpenRead('$ZIP').Entries | Select-Object -ExpandProperty FullName"
fi

# 密钥扫描：解压临时目录后 grep，零命中才放行
SCAN_DIR=$(mktemp -d)
if command -v unzip >/dev/null 2>&1; then
    unzip -qr "$ZIP" -d "$SCAN_DIR"
else
    powershell -NoProfile -Command "Expand-Archive -Path '$ZIP' -DestinationPath '$SCAN_DIR' -Force"
fi
if grep -rn "sk-[A-Za-z0-9]\{20,\}" "$SCAN_DIR" --include="*.yml" --include="*.json" --include="*.md" 2>/dev/null; then
    echo "❌ 密钥扫描命中！发布中止——先清理上面列出的文件"
    rm -rf "$SCAN_DIR"
    exit 1
fi
rm -rf "$SCAN_DIR"
echo "✅ 密钥扫描通过（零命中）"

# ---- 7. 本地预演提示：发版前把 zip 解压到全新目录双击 start.bat 验一遍 ----
echo "⏭️  发布前自证：解压 $ZIP 到全新目录 → 双击 start.bat → 跑通 RELEASE-CHECKLIST"
