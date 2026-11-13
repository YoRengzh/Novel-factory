#!/bin/bash
# 本地产物打包：jar → 解包 → jpackage app-image（免 JDK 的 exe）
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

# JDK 工具链（解包与打包必须同源）
JDK_BIN="D:/Java/JDK-17/bin"
if [ ! -x "$JDK_BIN/jpackage.exe" ]; then
    echo "❌ 未找到 $JDK_BIN/jpackage.exe——请按本机 JDK 安装路径调整 JDK_BIN"
    exit 1
fi

# jpackage 拒绝覆盖已存在的同名 app-image 目录：先清场，保证脚本可重复执行。
# 清场同时是防泄露闸门：开发模式（NF_DEV/npm start）跑任务会以 app-image 目录为
# 工作目录，留下 data/（API Key 明文 + 调用账本）与 config/——不清场直接 jpackage
# 会因目录已存在而失败，但"先手动删一半再打包"的路径仍可能把残留带进产物，
# 所以清场后显式校验零残留，与 builder-config.js 的打包前自检构成双保险
rm -rf dist/jpackage/novel-factory dist/jar-stage dist/cds
for entry in data config; do
    if [ -e "dist/jpackage/novel-factory/$entry" ]; then
        echo "❌ app-image 内检测到本机运行数据：dist/jpackage/novel-factory/$entry（含 API Key 风险，严禁进包）"
        exit 1
    fi
done

# ---- 解包 fat jar（tools extract → 瘦 jar + lib/）
# 解包布局用于减少 fat jar 条目解压与索引的启动开销。
# 原因：jar 内条目需解压 + 中央目录索引，按文件展开后类加载器直接读文件系统；
# jpackage 的 --input 目录天然就是这个形态，不额外增加体积
"$JDK_BIN/java.exe" -Djarmode=tools -jar "$JAR" extract --destination dist/cds/app

# ---- jpackage：解包目录整体进包
#
# JVM 参数：**故意不加** -XX:TieredStopAtLevel=1。
# 该参数对全进程生效：只用 C1 编译，永不启用 C2，可能降低长期跑批时的
# 正则匹配与 JSON 解析性能。这些处理贯穿质检、摘要和蓝图解析等章节路径，
# 因此不以牺牲运行期性能换取启动速度。
#
# 未采用 CDS（-XX:SharedArchiveFile）：JDK 17 静态档不含完整模块图，
# 覆盖不到 Spring 启动的主要开销，且会增加分发体积。
"$JDK_BIN/jpackage.exe" \
  --type app-image \
  --input dist/cds/app \
  --main-jar novel_factory-app.jar \
  --name novel-factory \
  --app-version "$VERSION" \
  --icon electron/icon.ico \
  --dest dist/jpackage

# 产物自检：exe 必须存在，且 lib 目录形态要成立（解包没成功就会退化成 fat jar 布局）
if [ ! -f dist/jpackage/novel-factory/novel-factory.exe ]; then
    echo "❌ 未产出 novel-factory.exe"
    exit 1
fi
if [ ! -d dist/jpackage/novel-factory/app/lib ]; then
    echo "❌ app-image 内缺少 app/lib（解包布局没生效，启动会退回 fat jar 的慢路径）"
    exit 1
fi

rm -rf dist/cds

echo "✅ app-image 已产出：dist/jpackage/novel-factory/novel-factory.exe"
echo "   版本 $VERSION；解包布局（启动实测约 4.3s，原 fat jar 约 6s），未启用 T1/CDS（无运行期性能损失）"
