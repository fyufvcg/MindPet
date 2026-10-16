import { useSyncExternalStore } from 'react'

const empty: string[] = []
const drafts = new Map<string, string[]>()
const running = new Map<string, { requestId: number; ids: string[] }>()
const listeners = new Set<() => void>()
const notify = (): void => listeners.forEach(listener => listener())

export function useTaskMcp(sessionId: string): { selectedIds: string[]; select: (id: string) => void; remove: (id: string) => void } {
  const selectedIds = useSyncExternalStore(
    listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
    () => drafts.get(sessionId) || empty
  )
  return {
    selectedIds,
    select: id => { drafts.set(sessionId, [...new Set([...(drafts.get(sessionId) || []), id])]); notify() },
    remove: id => { drafts.set(sessionId, (drafts.get(sessionId) || []).filter(item => item !== id)); notify() }
  }
}

export function beginTaskMcp(sessionId: string, requestId: number, steering: boolean, explicit: string[]): string[] {
  const ids = [...new Set([...(steering ? running.get(sessionId)?.ids || [] : []), ...(drafts.get(sessionId) || []), ...explicit])]
  running.set(sessionId, { requestId, ids })
  drafts.delete(sessionId)
  notify()
  return ids
}

export function finishTaskMcp(sessionId: string, requestId: number): void {
  if (running.get(sessionId)?.requestId === requestId) running.delete(sessionId)
}

export function parseMcpCommands(text: string, servers: Array<{ id: string; name: string }>): { text: string; ids: string[] } {
  let remaining = text
  const ids: string[] = []
  for (;;) {
    const match = remaining.match(/^\s*\/([^\s/]+)(?:\s+|$)/)
    if (!match) break
    const matches = servers.filter(server => server.id === match[1] || server.name === match[1])
    if (matches.length !== 1) break
    ids.push(matches[0].id)
    remaining = remaining.slice(match[0].length)
  }
  return { text: remaining.trim(), ids }
}
