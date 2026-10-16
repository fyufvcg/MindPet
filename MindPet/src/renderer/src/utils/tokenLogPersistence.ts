/** One writer per renderer: chat events, schedules and clearing share the latest state. */
export class TokenLogPersistence {
  private timer: ReturnType<typeof setTimeout> | undefined
  private latest: unknown[] | undefined
  constructor(private readonly write: (logs: unknown[]) => void, private readonly delay = 1000) {}
  queue(logs: unknown[]): void {
    this.latest = logs
    if (!logs.length) { this.flush(); return }
    if (!this.timer) this.timer = setTimeout(() => this.flush(), this.delay)
  }
  flush(): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = undefined
    if (!this.latest) return
    const logs = this.latest; this.latest = undefined
    this.write(logs)
  }
}

export const tokenLogPersistence = new TokenLogPersistence(logs => {
  localStorage.setItem('mindpet_token_logs', JSON.stringify(logs))
})
if (typeof window !== 'undefined') window.addEventListener('beforeunload', () => tokenLogPersistence.flush())
