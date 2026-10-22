// ============================================================
// electron-builder 配置：StartDrafts 安装包
// 产物：StartDrafts-Setup-<版本>.exe（NSIS 向导）
// 组成：Electron 运行时 + 本应用（壳）+ Java app-image（sidecar）
// ============================================================
const { execSync } = require('child_process')
const path = require('path')
const fs = require('fs')

// 打包前自检：app-image 必须存在且是新的（防止把旧 Java 打进安装包）
const sidecarSrc = path.join(__dirname, '..', 'dist', 'jpackage', 'novel-factory')
if (!fs.existsSync(path.join(sidecarSrc, 'novel-factory.exe'))) {
  console.error('❌ 未找到 sidecar：' + sidecarSrc)
  console.error('   先运行 bash tools/build-release.sh && bash tools/package-native.sh')
  process.exit(1)
}

// 打包前自检：sidecar 里严禁夹带本机运行数据（防泄露，零容忍）。
// 开发模式（NF_DEV/npm start）下 sidecar 以 dist/jpackage/novel-factory 为工作目录，
// 跑过任务后会留下 data/（API Key 明文的 llm-config-override.json + 全部调用账本与日志）
// 和 config/（zip 版配置）。extraResources 是整目录复制（filter: **/*），残留即打进 exe
// 分发给用户——密钥泄露，且不可撤回。发现即拒绝打包，先清场再重来
const forbiddenEntries = ['data', 'config']
for (const entry of forbiddenEntries) {
  const p = path.join(sidecarSrc, entry)
  if (fs.existsSync(p)) {
    console.error('❌ sidecar 内检测到本机运行数据：' + p)
    console.error('   开发模式跑任务会在 app-image 里留下 data/（含 API Key 明文）与 config/。')
    console.error('   清理后重试：rm -rf dist/jpackage/novel-factory/' + entry)
    console.error('   （或整体重建：bash tools/package-native.sh——它已含清场逻辑）')
    process.exit(1)
  }
}

module.exports = {
  appId: 'com.startdrafts.app',
  productName: 'StartDrafts',
  // 产物文件名必须与 latest.yml 里的 url 逐字一致，否则 electron-updater 按该 url
  // 去 Release 找资产会 404。默认命名含空格（StartDrafts Setup 1.2.0.exe），
  // 上传到 GitHub 后又可能被改成点号形式——三处一旦漂移，应用内更新必然失败。
  // 这里钉死为连字符形式：本地产物、latest.yml、Release 资产三者同名。
  artifactName: 'StartDrafts-Setup-${version}.${ext}',
  directories: {
    output: '../dist/electron'          // 安装包输出到 dist/electron/
  },
  files: [
    'main.js',
    'preload.js',
    'package.json'
  ],
  // 更新源：electron-builder 据此产出 latest.yml（含 sha512 与安装包文件名），
  // 客户端 electron-updater 读它比对版本并下载。缺这段配置就不会生成 latest.yml，
  // 应用内更新在客户端会永远报 "Cannot find latest.yml"。
  // 用 github provider：无需额外 token，直接走公开 Release 的 HTTPS 下载地址。
  publish: [
    {
      provider: 'github',
      owner: 'YoRengzh',
      repo: 'Novel-factory',
      releaseType: 'release'
    }
  ],
  // Java app-image 作为额外资源塞进安装包 → 运行时经 process.resourcesPath/sidecar 访问
  // （main.js 的 sidecarCommand() 打包分支就是按这个路径取的）
  extraResources: [
    {
      from: sidecarSrc,
      to: 'sidecar/novel-factory',
      filter: ['**/*']
    }
  ],
  win: {
    icon: path.join(__dirname, 'icon.ico'),
    target: [
      { target: 'nsis', arch: ['x64'] }
    ]
  },
  nsis: {
    oneClick: false,                    // 向导式安装（可选目录），非一键
    allowToChangeInstallationDirectory: true,
    installerLanguages: ['zh_CN'],
    language: '1052',                   // 简体中文 LCID
    createDesktopShortcut: true,
    createStartMenuShortcut: true,
    shortcutName: 'StartDrafts'
  }
}
