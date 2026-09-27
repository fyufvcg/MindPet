import { app, BrowserWindow, ipcMain } from 'electron'
import { autoUpdater } from 'electron-updater'

const DOMESTIC_UPDATE_FEED_URL =
  'https://gh-proxy.com/https://github.com/fyufvcg/MindPet/releases/latest/download'
const GITHUB_UPDATE_FEED = { provider: 'github', owner: 'fyufvcg', repo: 'MindPet' } as const

export type AppUpdateState = {
  state: 'unsupported' | 'idle' | 'checking' | 'available' | 'downloading' | 'downloaded' | 'up-to-date' | 'error'
  currentVersion: string
  version?: string
  percent?: number
  message?: string
}

let updateState: AppUpdateState = {
  state: 'idle',
  currentVersion: app.getVersion()
}

function publishState(next: AppUpdateState): void {
  updateState = { ...next, currentVersion: app.getVersion() }
  for (const window of BrowserWindow.getAllWindows()) {
    if (!window.isDestroyed()) window.webContents.send('app-update:state', updateState)
  }
}

async function checkForUpdates(): Promise<AppUpdateState> {
  if (!app.isPackaged) return updateState
  if (updateState.state === 'checking' || updateState.state === 'downloading') return updateState

  publishState({ state: 'checking', currentVersion: app.getVersion(), message: '正在检查更新…' })
  try {
    autoUpdater.setFeedURL({ provider: 'generic', url: DOMESTIC_UPDATE_FEED_URL })
    const result = await autoUpdater.checkForUpdates()
    if (result?.downloadPromise) await result.downloadPromise
  } catch (proxyError) {
    publishState({
      state: 'checking',
      currentVersion: app.getVersion(),
      message: '国内镜像暂不可用，正在尝试 GitHub 直连…'
    })
    try {
      autoUpdater.setFeedURL(GITHUB_UPDATE_FEED)
      const result = await autoUpdater.checkForUpdates()
      if (result?.downloadPromise) await result.downloadPromise
    } catch (githubError) {
      const proxyMessage = proxyError instanceof Error ? proxyError.message : String(proxyError)
      const githubMessage = githubError instanceof Error ? githubError.message : String(githubError)
      publishState({
        state: 'error',
        currentVersion: app.getVersion(),
        message: `国内镜像和 GitHub 直连均失败。镜像：${proxyMessage}；直连：${githubMessage}`
      })
    }
  }
  return updateState
}

export function initializeAppUpdater(): void {
  ipcMain.handle('app-update:get-state', () => updateState)
  ipcMain.handle('app-update:check', () => checkForUpdates())
  ipcMain.handle('app-update:install', () => {
    if (updateState.state !== 'downloaded') return false
    autoUpdater.quitAndInstall()
    return true
  })

  if (!app.isPackaged) {
    publishState({
      state: 'unsupported',
      currentVersion: app.getVersion(),
      message: '开发模式不检查更新；请在已安装的桌面版中检查。'
    })
    return
  }

  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true
  autoUpdater.allowPrerelease = false
  autoUpdater.on('checking-for-update', () => {
    publishState({ state: 'checking', currentVersion: app.getVersion(), message: '正在检查更新…' })
  })
  autoUpdater.on('update-available', info => {
    publishState({
      state: 'available',
      currentVersion: app.getVersion(),
      version: info.version,
      message: `发现新版本 ${info.version}，正在后台下载。`
    })
  })
  autoUpdater.on('download-progress', progress => {
    publishState({
      state: 'downloading',
      currentVersion: app.getVersion(),
      percent: Math.round(progress.percent),
      message: `正在下载更新：${Math.round(progress.percent)}%`
    })
  })
  autoUpdater.on('update-not-available', () => {
    publishState({
      state: 'up-to-date',
      currentVersion: app.getVersion(),
      message: `当前已是最新版本（${app.getVersion()}）。`
    })
  })
  autoUpdater.on('update-downloaded', info => {
    publishState({
      state: 'downloaded',
      currentVersion: app.getVersion(),
      version: info.version,
      message: `版本 ${info.version} 已下载，重启后完成安装。`
    })
  })
  autoUpdater.on('error', error => {
    publishState({ state: 'error', currentVersion: app.getVersion(), message: error.message })
  })

  // Give the main window and local services time to start before making a network request.
  const startupCheck = setTimeout(() => { void checkForUpdates() }, 8000)
  startupCheck.unref()
}
