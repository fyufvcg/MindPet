/**
 * HTTP RPA Run Repository — 将 RPA 运行记录通过 HTTP 持久化到 Java 后端 PostgreSQL。
 * 替代原 SqliteRpaRunRepository（依赖 mock-db stub，实际不工作）。
 */

import { randomUUID } from 'crypto'
import type {
  JsonValue,
  RpaAction,
  RpaArtifactRecord,
  RpaRunEvent,
  RpaRunRecord
} from '../domain/types'
import type {
  AppendRpaRunEventInput,
  CreateRpaArtifactInput,
  CreateRpaRunInput,
  RpaRunRepository,
  UpdateRpaRunStatusInput
} from './run-repository'

const BACKEND = 'http://127.0.0.1:8080/api/desktop/rpa'

async function post(path: string, body: unknown): Promise<any> {
  const res = await fetch(`${BACKEND}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return res.json()
}

async function get(path: string): Promise<any> {
  const res = await fetch(`${BACKEND}${path}`)
  return res.json()
}

async function put(path: string, body: unknown): Promise<any> {
  const res = await fetch(`${BACKEND}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return res.json()
}

export class HttpRpaRunRepository implements RpaRunRepository {

  async initialize(): Promise<void> {
    // 建表由 Java 后端 @PostConstruct 自动完成
  }

  async createRun(input: CreateRpaRunInput): Promise<RpaRunRecord> {
    const payload: Record<string, unknown> = {
      id: input.id ?? randomUUID(),
      workflowId: input.workflowId,
      workflowVersion: input.workflowVersion,
      sessionId: input.sessionId,
      status: input.status ?? 'pending',
      inputs: input.inputs ?? {},
      createdAt: input.createdAt ?? Date.now()
    }
    const resp = await post('/runs', payload)
    if (resp.status === 'error') throw new Error(resp.message)
    return resp.run as RpaRunRecord
  }

  async getRun(runId: string): Promise<RpaRunRecord | null> {
    const resp = await get(`/runs/${encodeURIComponent(runId)}`)
    if (resp.status === 'error') throw new Error(resp.message)
    return resp.run ?? null
  }

  async updateRunStatus(input: UpdateRpaRunStatusInput): Promise<void> {
    const payload: Record<string, unknown> = {
      status: input.status,
      startedAt: input.startedAt,
      finishedAt: input.finishedAt
    }
    if (input.output !== undefined) payload.output = input.output
    if (input.error !== undefined) payload.error = input.error
    const resp = await put(`/runs/${encodeURIComponent(input.runId)}/status`, payload)
    if (resp.status === 'error') throw new Error(resp.message)
  }

  async appendEvent(input: AppendRpaRunEventInput): Promise<RpaRunEvent> {
    const payload: Record<string, unknown> = {
      id: input.id ?? randomUUID(),
      type: input.type,
      createdAt: input.createdAt ?? Date.now()
    }
    if (input.action !== undefined) payload.action = input.action
    if (input.payload !== undefined) payload.payload = input.payload
    const resp = await post(`/runs/${encodeURIComponent(input.runId)}/events`, payload)
    if (resp.status === 'error') throw new Error(resp.message)
    const event = resp.event as Record<string, unknown>
    return {
      id: event.id as string,
      runId: event.runId as string,
      sequence: event.sequence as number,
      type: event.type as RpaRunEvent['type'],
      action: event.action as RpaAction | undefined,
      payload: event.payload as JsonValue | undefined,
      createdAt: event.createdAt as number
    }
  }

  async listEvents(runId: string, afterSequence = 0): Promise<RpaRunEvent[]> {
    const resp = await get(`/runs/${encodeURIComponent(runId)}/events?afterSequence=${afterSequence}`)
    if (resp.status === 'error') throw new Error(resp.message)
    return (resp.events as any[]).map((e: Record<string, unknown>) => ({
      id: e.id as string,
      runId: e.runId as string,
      sequence: e.sequence as number,
      type: e.type as RpaRunEvent['type'],
      action: e.action as RpaAction | undefined,
      payload: e.payload as JsonValue | undefined,
      createdAt: e.createdAt as number
    }))
  }

  async createArtifact(input: CreateRpaArtifactInput): Promise<RpaArtifactRecord> {
    const payload: Record<string, unknown> = {
      id: input.id ?? randomUUID(),
      eventId: input.eventId,
      type: input.type,
      filePath: input.filePath,
      sha256: input.sha256,
      createdAt: input.createdAt ?? Date.now()
    }
    const resp = await post(`/runs/${encodeURIComponent(input.runId)}/artifacts`, payload)
    if (resp.status === 'error') throw new Error(resp.message)
    return resp.artifact as RpaArtifactRecord
  }

  async close(): Promise<void> {
    // HTTP 仓库无需关闭连接
  }
}
