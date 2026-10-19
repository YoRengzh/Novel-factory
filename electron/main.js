// ============================================================
// Novel Factory Electron 壳——主进程
// 职责：单实例锁 → 拉起 Java sidecar → 等端口就绪 → 开窗口加载 UI
//       → 退出时杀整棵 Java 进程树（不留孤儿）
// 开发模式（NF_DEV=1 环境变量）：不拉 sidecar，只开窗口加载已运行的后端
// ============================================================
const { app, BrowserWindow, dialog } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const net = require('net')

const PORT = 8080
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

function killSidecarTree() {
  if (!javaProc || javaProc.exitCode !== null) return
  // 杀整棵进程树而非单个 PID：Java 之下可能还有子进程；
  // taskkill /T /F 是 Windows 上唯一可靠的树杀——与 stop.bat 同款
  spawn('taskkill', ['/PID', String(javaProc.pid), '/T', '/F'], { windowsHide: true })
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
      autoHideMenuBar: true          // 隐藏默认菜单栏（File/Edit 那排没用的）
    })
    // 页面 <title> 会覆盖窗口标题——拦截之，标题恒定为产品名
    win.on('page-title-updated', (e) => e.preventDefault())
    win.loadURL('http://127.0.0.1:8080')
    // 关窗即退出（before-quit 会杀 sidecar）
    win.on('closed', () => { if (!quitting) app.quit() })
  })

  app.on('before-quit', () => {
    quitting = true
    killSidecarTree()
  })

  app.on('window-all-closed', () => app.quit())
}
