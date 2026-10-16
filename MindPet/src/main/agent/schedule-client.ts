import { backendUrl } from '../backend-endpoint'

/** One backend contract for settings, tools and legacy migration. */
class ScheduleRequestError extends Error {
  constructor(message: string, readonly outcome: 'confirmed' | 'unknown') { super(message) }
}
async function request(path = '', options?: RequestInit): Promise<any[]> {
  try {
    const timeout = AbortSignal.timeout(15000)
    const response = await fetch(backendUrl(`/api/desktop/schedules${path}`), { ...options,
      signal: options?.signal ? AbortSignal.any([options.signal, timeout]) : timeout })
    if (!response.ok) throw new ScheduleRequestError(`定时任务请求失败 (${response.status})${response.status === 409 ? '：请先停止正在执行的任务。' : ''}`, 'confirmed')
    const body = await response.json() as { tasks?: any[] }
    if (!Array.isArray(body.tasks)) throw new Error('后台定时任务响应无效')
    return body.tasks
  } catch (e) {
    if (e instanceof ScheduleRequestError) throw e
    throw new ScheduleRequestError(`后台定时任务通信失败：${e instanceof Error ? e.message : String(e)}`,
      options?.method && options.method !== 'GET' ? 'unknown' : 'confirmed')
  }
}
export const scheduleClient = {
  list: (): Promise<any[]> => request(),
  migrate: (tasks: any[]): Promise<any[]> => request('?migration=true', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(tasks) }),
  upsert: (id: string, task: any, signal?: AbortSignal): Promise<any[]> => request(`/${encodeURIComponent(id)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(task), signal
  }),
  delete: (id: string, signal?: AbortSignal): Promise<any[]> => request(`/${encodeURIComponent(id)}`, { method: 'DELETE', signal }),
  clearLogs: (id: string): Promise<any[]> => request(`/${encodeURIComponent(id)}/logs`, { method: 'DELETE' })
}
