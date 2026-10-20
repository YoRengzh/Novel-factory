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

# jpackage 拒绝覆盖已存在的同名 app-image 目录：先清场，保证脚本可重复执行
rm -rf dist/jpackage/novel-factory

# 中转目录只放这一个 jar（--input 会整个复制进产物，不能混入 target 的构建中间物）
rm -rf dist/jar-stage
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
