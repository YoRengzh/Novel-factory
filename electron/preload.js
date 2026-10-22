// ============================================================
// 预加载脚本：把主进程的更新能力安全地暴露给渲染进程
//
// 页面本身由 Java sidecar 提供（http://127.0.0.1:8080），渲染进程拿不到
// Node 能力（contextIsolation 默认开、nodeIntegration 默认关），所以必须
// 经 contextBridge 只暴露这三个动作——不给渲染进程任何文件/进程权限。
// ============================================================
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('nfUpdater', {
  // 是否具备应用内更新能力：主进程在开发模式下会回 supported=false
  check: () => ipcRenderer.invoke('updater:check'),
  download: () => ipcRenderer.invoke('updater:download'),
  install: () => ipcRenderer.invoke('updater:install'),
  /**
   * 订阅下载/安装进度事件。返回取消订阅函数，避免组件重渲染后监听器堆积。
   * payload: { type, percent?, version?, message? }
   */
  onEvent: (cb) => {
    const listener = (_event, payload) => cb(payload)
    ipcRenderer.on('updater:event', listener)
    return () => ipcRenderer.removeListener('updater:event', listener)
  }
})
