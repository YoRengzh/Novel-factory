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

# ---- 前置自检：版本号三处必须一致 ----
# electron/package.json 决定安装包与 app-image 版本；application.yml 的 app.version
# 决定前端「通知」面板显示的当前版本。两者漂移会让用户看到"已是最新"却其实装了新版
PKG_VERSION=$(node -p "require('./electron/package.json').version")
YML_VERSION=$(grep -A2 "^app:" novel_factory-app/src/main/resources/application.yml \
    | grep "version:" | head -1 | sed 's/.*version:[[:space:]]*//' | tr -d '\r')
if [ "$PKG_VERSION" != "$YML_VERSION" ]; then
    echo " 版本号不一致：electron/package.json=$PKG_VERSION，application.yml=$YML_VERSION"
    echo " 请同步这两处（前端通知面板按 application.yml 显示当前版本）"
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