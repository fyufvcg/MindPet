import { scheduleClient } from './schedule-client'
import { ipcMain, BrowserWindow } from 'electron'
import * as fs from 'fs'
import { join } from 'path'
import { randomUUID } from 'crypto'
import { backendUrl } from '../backend-endpoint'
import { callJavaBackend } from '../backend-api'

export function registerAgentRuntimeIpc(deps: {
  storageDir: () => string
}): void {
  const migrateLegacy = async (browserTasks: any[] = []): Promise<void> => {
    const cronPath = join(deps.storageDir(), 'cron_tasks.json')
    const hasFile = fs.existsSync(cronPath)
    const fileTasks = hasFile ? JSON.parse(await fs.promises.readFile(cronPath, 'utf-8')) : []
    if (!Array.isArray(fileTasks) || !Array.isArray(browserTasks)) throw new Error('旧定时任务格式无效，原数据已保留')
    if (!hasFile && browserTasks.length === 0) return
    const tasks = new Map<string, any>()
    for (const task of [...browserTasks, ...fileTasks]) if (task?.id) tasks.set(String(task.id), task)
    await scheduleClient.migrate([...tasks.values()])
    if (hasFile && fs.existsSync(cronPath)) await fs.promises.rename(cronPath, `${cronPath}.migrated-${Date.now()}`)
  }
  ipcMain.handle('api:get-cron-tasks', async () => {
    try {
      await migrateLegacy()
      const tasks = await scheduleClient.list()
      return tasks
    } catch (e) {
      console.error('加载后台定时任务失败', e)
      throw e
    }
  })

  const broadcast = (): void => BrowserWindow.getAllWindows().forEach(win => win.webContents.send('api:cron-updated'))
  ipcMain.handle('api:upsert-cron-task', async (_, id: string, task: any) => {
    const tasks = await scheduleClient.upsert(id, task); broadcast(); return tasks
  })
  ipcMain.handle('api:delete-cron-task', async (_, id: string) => {
    const tasks = await scheduleClient.delete(id); broadcast(); return tasks
  })
  ipcMain.handle('api:migrate-cron-tasks', async (_, tasks: any[]) => { await migrateLegacy(tasks); return true })
  const taskResponse = async (url: string, options?: RequestInit): Promise<any> => {
    const response = await fetch(backendUrl(url), { ...options, signal: AbortSignal.timeout(15000) })
    if (!response.ok) throw new Error(`任务请求失败 (${response.status})`)
    return response.json()
  }
  ipcMain.handle('api:list-agent-tasks', async () => (await taskResponse('/api/desktop/agent/tasks')).tasks)
  ipcMain.handle('api:get-agent-task', async (_, id: string) => taskResponse(`/api/desktop/agent/tasks/${encodeURIComponent(id)}`))
  ipcMain.handle('api:cancel-agent-task', async (_, taskId: string) => taskResponse('/api/desktop/agent/tasks/cancel', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ taskId })
  }))
  ipcMain.handle('api:resume-agent-task', async (event, id: string, instruction: string, selectedMcpServerIds: string[]) => {
    const row = await taskResponse(`/api/desktop/agent/tasks/${encodeURIComponent(id)}`)
    let reply = ''
    for await (const step of callJavaBackend({ sessionId: row.session_id, resumeTaskId: id,
      workspacePath: row.snapshot?.workspacePath || '', selectedMcpServerIds,
      senderId: event.sender.id, messageId: randomUUID() }, [{ role: 'user', content: instruction || '继续' }])) {
      if (step.type === 'error') throw new Error(step.message || '恢复任务失败')
      if (step.type === 'text') reply = step.content || ''
      if (step.type === 'token_usage' && !event.sender.isDestroyed()) event.sender.send('api:llm-token-usage', {
        ...step, timestamp: Date.now(), sessionId: row.session_id, messageId: id, source: 'desktop'
      })
    }
    return { reply }
  })
  ipcMain.handle('api:clear-cron-logs', async (_, id: string) => { await scheduleClient.clearLogs(id); return true })

}
