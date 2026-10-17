#!/bin/bash
# 发布构建脚本：产出可发布的 novel_factory-app.jar
# 用法：仓库根目录执行 bash tools/build-release.sh
set -e   # 任何一步失败立即停止，不带着问题往下跑

cd "$(dirname "$0")/.."   # 钉到仓库根目录，从哪调用都行

# ---- 前置自检：本地覆盖文件严禁进包 ----
# 它一旦进 jar，会压过 zip 用户的 config/application-local.yml（import 顺序决定）
if [ -f novel_factory-app/src/main/resources/application-local.yml ]; then
    echo " 检测到 src/main/resources/application-local.yml——它会随 jar 发布并压过用户配置"
    echo " 请移走（gitignore 只防提交，不防打包；打包读的是磁盘文件）"
    exit 1
fi

# ---- 构建 ----
mvn -DskipTests clean package -pl novel_factory-app -am

# ---- 产物自检 ----
JAR=novel_factory-app/target/novel_factory-app.jar
if [ ! -f "$JAR" ]; then
    echo " 构建完成但未找到 $JAR"
    exit 1
fi
echo " 构建成功：$JAR（$(du -h "$JAR" | cut -f1)）"
echo " 本脚本职责到 jar 为止"