// ============================================================
// Novel Factory Electron 壳——主进程
// 职责：单实例锁 → 拉起 Java sidecar → 等端口就绪 → 开窗口加载 UI
//       → 退出时杀整棵 Java 进程树（不留孤儿）
//       → 应用内更新（electron-updater，经 preload 暴露给页面）
// 开发模式（NF_DEV=1 环境变量）：不拉 sidecar，只开窗口加载已运行的后端
// ============================================================
const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const net = require('net')

const PORT = 8080
const APP_ORIGIN = 'http://127.0.0.1:' + PORT   // 页面唯一可信来源（IPC 也按它校验）
const DEV = process.env.NF_DEV === '1'          // 开发模式：Java 已手动起好，壳只管窗口
const isPackaged = app.isPackaged               // electron-builder 打包后为 true

// ---------- sidecar 路径解析（两套模式） ----------
// 打包后：resources 目录下 sidecar/novel-factory/novel-factory.exe（6c 的 extraResources 放进去）
// 开发时：直接指向 jpackage 产物，省去打包环节
function sidecarCommand() {
  if (isPackaged) {
    return path.join(process.resourcesPath, 'sidecar', 'novel-factory', 'novel-factory.exe')
  }
  return path.join(__dirname, '..', 'dist', 'jpackage', 'novel-factory', 'novel-factory.exe')
}

// sidecar 的工作目录：数据目录（config/ 与 data/ 按工作目录解析——
// 与 zip 版 start.bat 的 cd /d %~dp0 同理，钉死在这里是为了不受启动方式影响）
function sidecarCwd() {
  if (isPackaged) {
    // 用户数据放 %APPDATA%/NovelFactory（安装后进程目录在 Program Files，不可写）
    return app.getPath('userData')
  }
  return path.join(__dirname, '..', 'dist', 'jpackage', 'novel-factory')
}

let javaProc = null      // sidecar 子进程句柄
let quitting = false     // 区分"用户关窗退出"与"崩溃弹窗"的标志位

// ---------- 端口就绪探测（TCP 连一下就断，比 HTTP 快且无副作用） ----------
function isPortReady(port) {
  return new Promise((resolve) => {
    const socket = new net.Socket()
    socket.setTimeout(800)
    socket.once('connect', () => { socket.destroy(); resolve(true) })
    socket.once('timeout', () => { socket.destroy(); resolve(false) })
    socket.once('error', () => resolve(false))
    socket.connect(port, '127.0.0.1')
  })
}

async function waitBackendReady(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await isPortReady(PORT)) return true
    if (javaProc && javaProc.exitCode !== null) return false   // sidecar 已死，别傻等
    await new Promise(r => setTimeout(r, 500))
  }
  return false
}

// ---------- 拉起 / 杀死 Java sidecar ----------
function startSidecar() {
  const cmd = sidecarCommand()
  const cwd = sidecarCwd()
  console.log('[shell] 启动 sidecar:', cmd, 'cwd:', cwd)
  javaProc = spawn(cmd, [], {
    cwd,
    windowsHide: true,          // 不弹控制台黑窗
    stdio: ['ignore', 'pipe', 'pipe']
  })
  javaProc.stdout.on('data', d => process.stdout.write('[java] ' + d))
  javaProc.stderr.on('data', d => process.stderr.write('[java] ' + d))
  // 崩溃检测：窗口还开着但 Java 死了 → 弹窗告知（不自动重启，让用户决策）
  javaProc.on('exit', (code) => {
    if (!quitting) {
      dialog.showErrorBox('后端进程退出', 'StartDrafts 后端已退出（code=' + code + '）。\n'
        + '常见原因：端口 8080 被占用、数据目录不可写。\n请关闭本窗口后重新启动应用。')
    }
  })
}

/**
 * 杀整棵 sidecar 进程树。返回 Promise：安装更新前必须等它真正结束——
 * 否则 NSIS 覆盖 resources/sidecar/** 时文件仍被 java.exe 占用（安装半失败），
 * 或装完自启时端口 8080 还被残留进程占着（弹“端口被占用”）。
 */
function killSidecarTree() {
  return new Promise((resolve) => {
    if (!javaProc || javaProc.exitCode !== null) {
      resolve()
      return
    }
    // 杀整棵进程树而非单个 PID：Java 之下可能还有子进程；
    // taskkill /T /F 是 Windows 上唯一可靠的树杀——与 stop.bat 同款
    const killer = spawn('taskkill', ['/PID', String(javaProc.pid), '/T', '/F'], { windowsHide: true })
    let settled = false
    const finish = () => {
      if (!settled) {
        settled = true
        resolve()
      }
    }
    killer.once('close', finish)
    killer.once('error', finish)
    // 兜底超时：不能因为 taskkill 卡住就让“安装并重启”永远转圈
    setTimeout(finish, 5000)
  })
}

// ---------- 单实例锁：二次双击聚焦已有窗口，不开新进程 ----------
const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    // 第二次启动触发这里：把已有窗口提到前台
    const win = BrowserWindow.getAllWindows()[0]
    if (win) { if (win.isMinimized()) win.restore(); win.focus() }
  })

  app.whenReady().then(async () => {
    if (!DEV) {
      // 先探端口：已被占用（残留进程/其他应用）比启动失败更常见——给出明确提示而非傻等
      if (await isPortReady(PORT)) {
        dialog.showErrorBox('端口被占用', '端口 8080 已被占用。\n'
          + '可能是上次未完全退出的 Novel Factory（任务管理器结束 java 进程后重试）。')
        app.quit()
        return
      }
      startSidecar()
    }
    const ready = await waitBackendReady()
    if (!ready) {
      dialog.showErrorBox('后端启动失败', '60 秒内后端服务未就绪。\n请查看日志目录 data/log/ 排查。')
      app.quit()
      return
    }
    const win = new BrowserWindow({
      width: 1400,
      height: 900,
      title: 'StartDrafts',
      icon: path.join(__dirname, 'icon-256x256.png'),   // PNG 直指：排除 ico 解析/缓存干扰；打包仍用 icon.ico
      autoHideMenuBar: true,         // 隐藏默认菜单栏（File/Edit 那排没用的）
      webPreferences: {
        // 更新能力经 preload 的最小接口暴露给页面；页面本身来自本地 Java sidecar，
        // 不放 nodeIntegration（渲染进程拿不到文件/进程权限）
        preload: path.join(__dirname, 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false
      }
    })
    // 页面 <title> 会覆盖窗口标题——拦截之，标题恒定为产品名
    win.on('page-title-updated', (e) => e.preventDefault())
    win.loadURL(APP_ORIGIN)
    // 主窗口只允许停留在本机 UI：XSS 一旦把窗口导航到外部站点，那个页面同样持有 preload；
    // 外部链接改交系统浏览器打开
    win.webContents.on('will-navigate', (event, url) => {
      if (!String(url).startsWith(APP_ORIGIN)) {
        event.preventDefault()
        if (/^https?:\/\//i.test(url)) shell.openExternal(url)
      }
    })
    // window.open / target="_blank" 不经过 will-navigate：不拦会开出一个继承 preload、
    // 无地址栏的裸 Electron 子窗口去加载外部站点（通知面板的「打开下载页」就是这条路径）。
    // 与 will-navigate 同一口径：外部页面一律交系统浏览器，本应用不再开新窗口
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (/^https?:\/\//i.test(url)) shell.openExternal(url)
      return { action: 'deny' }
    })
    // 关窗即退出（before-quit 会杀 sidecar）
    win.on('closed', () => { if (!quitting) app.quit() })

    setupUpdater(win)
  })

  app.on('before-quit', () => {
    quitting = true
    killSidecarTree()
  })

  app.on('window-all-closed', () => app.quit())
}

// ---------- 应用内更新（electron-updater） ----------
// 只对打包版生效：开发模式没有 latest.yml，autoUpdater 会直接报错，
// 因此 DEV/未打包时所有 IPC 都回 supported=false，页面据此隐藏"下载并安装"按钮
// 并继续用"打开下载页"兜底。
//
// 依赖链：builder-config.js 的 publish 配置 → electron-builder 产出 latest.yml →
// 上传到 GitHub Release → 客户端按该文件比对/下载。缺任何一环 autoUpdater 都无从工作。
function setupUpdater(win) {
  const supported = isPackaged && !DEV
  let updater = null
  let downloaded = false

  const send = (payload) => {
    if (!win.isDestroyed()) win.webContents.send('updater:event', payload)
  }

  /**
   * 只信任本机 UI 页面。OAuth 弹窗（accounts.openai.com）与 window.open 打开的外部页面
   * 同样继承 preload，但绝不允许它们调用“下载/安装”——那等于把安装器交给第三方页面。
   */
  const isTrustedSender = (event) => {
    try {
      const frame = event && event.senderFrame
      return !!frame && String(frame.url || '').startsWith(APP_ORIGIN)
    } catch (e) {
      return false
    }
  }

  if (supported) {
    try {
      // 延迟 require：开发模式下没装 electron-updater 也不该让整个壳启动失败
      const { autoUpdater } = require('electron-updater')
      updater = autoUpdater
      // 更新日志走 stdout（壳的日志由 electron 捕获），不弹系统通知避免打扰
      updater.autoDownload = false          // 由用户在页面点"下载"再下，不偷偷吃带宽
      updater.autoInstallOnAppQuit = true   // 下载完成后即便用户直接关窗，也顺手装上

      updater.on('checking-for-update', () => send({ type: 'checking' }))
      updater.on('update-available', (info) => send({ type: 'available', version: info && info.version }))
      updater.on('update-not-available', () => send({ type: 'not-available' }))
      updater.on('download-progress', (p) => send({
        type: 'progress',
        percent: Math.round(p && p.percent ? p.percent : 0)
      }))
      updater.on('update-downloaded', (info) => {
        downloaded = true
        send({ type: 'downloaded', version: info && info.version })
      })
      updater.on('error', (err) => send({
        type: 'error',
        message: err && err.message ? err.message : String(err)
      }))
    } catch (e) {
      console.error('[updater] electron-updater 不可用：', e && e.message)
      updater = null
    }
  }

  ipcMain.handle('updater:check', async (event) => {
    if (!isTrustedSender(event)) return { supported: false }
    if (!updater) return { supported: false }
    try {
      const result = await updater.checkForUpdates()
      const info = result && result.updateInfo
      return {
        supported: true,
        // currentVersion 由壳自身提供：后端旧版（无 /api/app/version）时页面仍能比对
        currentVersion: app.getVersion(),
        // autoUpdater 自己会按 currentVersion 比对；这里只把结果原样告诉页面
        version: info ? info.version : null,
        releaseNotes: info ? info.releaseNotes : null
      }
    } catch (e) {
      return { supported: true, error: e && e.message ? e.message : String(e) }
    }
  })

  ipcMain.handle('updater:download', async (event) => {
    if (!isTrustedSender(event)) return { supported: false }
    if (!updater) return { supported: false }
    try {
      await updater.downloadUpdate()
      return { supported: true, ok: true }
    } catch (e) {
      return { supported: true, ok: false, error: e && e.message ? e.message : String(e) }
    }
  })

  ipcMain.handle('updater:install', async (event) => {
    if (!isTrustedSender(event)) return { supported: false }
    if (!updater) return { supported: false }
    if (!downloaded) return { supported: true, ok: false, error: '更新包尚未下载完成' }
    // 安装会退出应用：先杀 sidecar 并**等它退出**（否则残留的 java.exe 会占住待覆盖的
    // resources/sidecar 文件与新实例的 8080 端口）
    quitting = true
    await killSidecarTree()
    // isSilent=false 走安装向导，isForceRunAfter=true 装完自动拉起新版本
    setImmediate(() => updater.quitAndInstall(false, true))
    return { supported: true, ok: true }
  })
}
