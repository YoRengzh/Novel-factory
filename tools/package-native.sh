#!/bin/bash
# 本地产物打包：jar → jpackage app-image（免 JDK 的 exe）
# 用法：先 bash tools/build-release.sh 出 jar，再运行本脚本
set -e
cd "$(dirname "$0")/.."

# jar 必须先构建好（产物只信 build-release.sh）
JAR=novel_factory-app/target/novel_factory-app.jar
if [ ! -f "$JAR" ]; then
    echo "❌ 未找到 $JAR——先运行 bash tools/build-release.sh"
    exit 1
fi

# ---- 版本号：与 electron/package.json 的 version 单一来源，发版只改那里 ----
VERSION=$(node -p "require('./electron/package.json').version")
echo "📦 app-image 版本：$VERSION（取自 electron/package.json）"

# jpackage 拒绝覆盖已存在的同名 app-image 目录：先清场，保证脚本可重复执行。
# 清场同时是防泄露闸门：开发模式（NF_DEV/npm start）跑任务会以 app-image 目录为
# 工作目录，留下 data/（API Key 明文 + 调用账本）与 config/——不清场直接 jpackage
# 会因目录已存在而失败，但"先手动删一半再打包"的路径仍可能把残留带进产物，
# 所以清场后显式校验零残留，与 builder-config.js 的打包前自检构成双保险
rm -rf dist/jpackage/novel-factory dist/jar-stage
for entry in data config; do
    if [ -e "dist/jpackage/novel-factory/$entry" ]; then
        echo "❌ app-image 内检测到本机运行数据：dist/jpackage/novel-factory/$entry（含 API Key 风险，严禁进包）"
        exit 1
    fi
done

# 中转目录只放这一个 jar（--input 会整个复制进产物，不能混入 target 的构建中间物）
mkdir -p dist/jar-stage
cp "$JAR" dist/jar-stage/

"D:/Java/JDK-17/bin/jpackage.exe" \
  --type app-image \
  --input dist/jar-stage \
  --main-jar novel_factory-app.jar \
  --name novel-factory \
  --app-version "$VERSION" \
  --icon electron/icon.ico \
  --dest dist/jpackage

rm -rf dist/jar-stage

echo "✅ app-image 已产出：dist/jpackage/novel-factory/novel-factory.exe（版本 $VERSION）"
