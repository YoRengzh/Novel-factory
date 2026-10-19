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

module.exports = {
  appId: 'com.startdrafts.app',
  productName: 'StartDrafts',
  directories: {
    output: '../dist/electron'          // 安装包输出到 dist/electron/
  },
  files: [
    'main.js',
    'package.json'
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
