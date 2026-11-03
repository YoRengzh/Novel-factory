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
# 顺带清理历史版本的组装暂存目录：每次发版都会在 dist/ 留下一个 v<版本>/ 目录，
# 只清当前版本会让它们无限堆积（v1.2.9/ v1.3.0/ v1.3.1/ …）。显式跳过本次目录，
# 避免与上面的 rm 顺序耦合；glob 无匹配时保留字面量，由 [ -d ] 兜住
for stale in dist/v[0-9]*/; do
    [ -d "$stale" ] || continue
    [ "${stale%/}" = "$DIST" ] && continue
    echo "🧹 清理历史组装目录：$stale"
    rm -rf "$stale"
done
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
    PS_DIST="dist/${VERSION}.zip"
    # 同样转原生路径：PowerShell 是原生程序，喂 MSYS 路径（/d/...）会找不到文件
    PS_ZIP="$(cygpath -w "$(cd dist && pwd)")\\novel-factory-$VERSION.zip"
    PS_SRC="$(cygpath -w "$(cd dist && pwd)")\\$VERSION"
    powershell -NoProfile -Command "Compress-Archive -Path '$PS_SRC' -DestinationPath '$PS_ZIP' -Force"
fi

# ---- 6. 产物自检：zip 在位 + 内容清单 + 密钥零容忍扫描 ----
if [ ! -f "$ZIP" ]; then
    echo "❌ 打包失败：未找到 $ZIP"
    exit 1
fi
echo "✅ 发布包已产出：$ZIP（$(du -h "$ZIP" | cut -f1)）"

# Windows 自带的 unzip / PowerShell 都是原生程序，不认 Git Bash 的 MSYS 路径
# （/d/... 或 /tmp/...），必须经 cygpath 转成 C:\... 形式，否则会打印 usage 并以非 0 退出，
# 在 set -e 下直接终止脚本，导致密钥扫描与清理被静默跳过
to_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}
ZIP_NATIVE="$(to_native "$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")")"

echo "---- zip 内容清单 ----"
# 注意用 -l 单独调用；本机 unzip 对 "-qr" 合并写法会打印 usage（分开写 -q -r 才正常）
if command -v unzip >/dev/null 2>&1; then
    unzip -l "$ZIP_NATIVE" || echo "（清单读取失败，不影响打包；可用压缩软件手动查看）"
else
    powershell -NoProfile -Command "[System.IO.Compression.ZipFile]::OpenRead('$ZIP_NATIVE').Entries | Select-Object -ExpandProperty FullName" \
        || echo "（清单读取失败，不影响打包）"
fi

# 密钥扫描：解压临时目录后 grep，零命中才放行。
# 解压方式：把 zip 复制进临时目录、cd 进去用相对文件名解压——本机 unzip 对
# Git Bash 路径（/d/...、/tmp/...）与 -qr 合并写法都会误打印 usage，这是最稳的组合。
# ⚠️ 不能只看退出码：PowerShell 的 Compress-Archive 生成的 zip 用反斜杠作路径分隔符
#    （v1.3.1\LICENSE），Info-ZIP 对此告警后返回 1，但文件实际全部正确解出。
#    故以"解出的条目数 > 0"判定成功，避免把成功当失败而中断发布。
SCAN_DIR=$(mktemp -d)
cp "$ZIP" "$SCAN_DIR/payload.zip" || { echo "❌ 复制 zip 到临时目录失败，无法执行密钥扫描"; rm -rf "$SCAN_DIR"; exit 1; }
if command -v unzip >/dev/null 2>&1; then
    (cd "$SCAN_DIR" && unzip -q -o payload.zip 2>/dev/null) || true
    rm -f "$SCAN_DIR/payload.zip"
    if [ -z "$(find "$SCAN_DIR" -mindepth 1 -not -name payload.zip -print -quit)" ]; then
        echo "❌ 解压失败（临时目录为空），无法执行密钥扫描"
        rm -rf "$SCAN_DIR"
        exit 1
    fi
else
    SCAN_NATIVE="$(to_native "$SCAN_DIR")"
    powershell -NoProfile -Command "Expand-Archive -Path '$SCAN_NATIVE\\payload.zip' -DestinationPath '$SCAN_NATIVE\\x' -Force" \
        || { echo "❌ 解压失败，无法执行密钥扫描"; rm -rf "$SCAN_DIR"; exit 1; }
fi
if grep -rn "sk-[A-Za-z0-9]\{20,\}" "$SCAN_DIR" --include="*.yml" --include="*.json" --include="*.md" 2>/dev/null; then
    echo "❌ 密钥扫描命中！发布中止——先清理上面列出的文件"
    rm -rf "$SCAN_DIR"
    exit 1
fi
rm -rf "$SCAN_DIR"
echo "✅ 密钥扫描通过（零命中）"

# ---- 7. 清理组装暂存目录：zip 已产出且两项自检（内容清单/密钥扫描）都过了，
#         暂存目录只是中间产物，留着会让 dist/ 逐版本堆积。
#         放在自检之后而非打包之后：任何一步失败时目录仍在，便于排查。
#         zip 内容如需复核，重新解压 $ZIP 即可。 ----
rm -rf "$DIST"
echo "🧹 已清理组装暂存目录：$DIST"

# ---- 8. 本地预演提示：发版前把 zip 解压到全新目录双击 start.bat 验一遍 ----
echo "⏭️  发布前自证：解压 $ZIP 到全新目录 → 双击 start.bat → 跑通 RELEASE-CHECKLIST"
