import { app } from 'electron'
import { ChildProcess, spawn } from 'child_process'
import { existsSync, mkdirSync } from 'fs'
import { join, resolve } from 'path'
import { DEFAULT_BACKEND_BASE_URL, backendBaseUrl, probeBackend } from './backend-endpoint'

let backendProcess: ChildProcess | null = null

function runtimePaths(): { java: string; jar: string } {
  if (app.isPackaged) {
    return {
      java: join(process.resourcesPath, 'backend', 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
      jar: join(process.resourcesPath, 'backend', 'mindpet-backend.jar')
    }
  }
  return {
    java: process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java',
    jar: resolve(process.cwd(), '..', 'MindPet-java', 'target', 'weather-wechat-bot-1.0.0.jar')
  }
}

export async function startLocalBackend(): Promise<void> {
  if (backendBaseUrl() !== DEFAULT_BACKEND_BASE_URL || process.argv.includes('--no-local-backend')) return
  if ((await probeBackend(DEFAULT_BACKEND_BASE_URL, 800)).ok) return

  const paths = runtimePaths()
  if (!existsSync(paths.jar) || (app.isPackaged && !existsSync(paths.java))) {
    console.warn('[Backend] 本地后端运行文件不存在:', paths)
    return
  }
  const dataDir = join(app.getPath('userData'), 'backend')
  mkdirSync(dataDir, { recursive: true })
  backendProcess = spawn(paths.java, [
    '-Dfile.encoding=UTF-8', '-jar', paths.jar, '--mode=bot',
    '--spring.config.location=classpath:/application-desktop.yml',
    `--app.storage.sqlite.path=${join(dataDir, 'mindpet.db')}`
  ], {
    cwd: dataDir,
    windowsHide: true,
    env: { ...process.env, MINDPET_DATA_DIR: dataDir },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  backendProcess.stdout?.on('data', chunk => console.log('[Backend]', String(chunk).trimEnd()))
  backendProcess.stderr?.on('data', chunk => console.error('[Backend]', String(chunk).trimEnd()))
  backendProcess.once('exit', code => { console.log('[Backend] exited:', code); backendProcess = null })

  for (let attempt = 0; attempt < 40; attempt++) {
    if ((await probeBackend(DEFAULT_BACKEND_BASE_URL, 500)).ok) return
    await new Promise(resolveWait => setTimeout(resolveWait, 250))
  }
  throw new Error('MindPet 本地后端启动超时')
}

export function stopLocalBackend(): void {
  const child = backendProcess
  backendProcess = null
  if (!child || child.killed) return
  child.kill()
}
