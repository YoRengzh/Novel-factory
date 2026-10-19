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

# 中转目录只放这一个 jar（--input 会整个复制进产物，不能混入 target 的构建中间物）
mkdir -p dist/jar-stage
cp "$JAR" dist/jar-stage/

"D:/Java/JDK-17/bin/jpackage.exe" \
  --type app-image \
  --input dist/jar-stage \
  --main-jar novel_factory-app.jar \
  --name novel-factory \
  --app-version 0.2.0 \
  --icon electron/icon.ico \
  --dest dist/jpackage

echo "✅ app-image 已产出：dist/jpackage/novel-factory/novel-factory.exe"
