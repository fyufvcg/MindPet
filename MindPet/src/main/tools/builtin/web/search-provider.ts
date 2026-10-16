import { LocalBrowser } from './localBrowser'
import type { SearchResult } from './localBrowser'

type SearchResponse = { results: SearchResult[]; provider: string; limitation?: string }
/** Structured search when configured, bounded browser fallback otherwise. No page fetching. */
export class WebSearchProvider {
  private static cache = new Map<string, { expires: number; value: SearchResponse }>()
  public static async search(query: string, options: {
    timeoutMs: number; maxResults: number; abortSignal?: AbortSignal
  }): Promise<SearchResponse> {
    const provider = (process.env.MINDPET_SEARCH_PROVIDER || 'auto').toLowerCase()
    const kind = provider === 'auto' ? process.env.TAVILY_API_KEY ? 'tavily' : process.env.BRAVE_SEARCH_API_KEY ? 'brave' : 'browser' : provider
    if (!['tavily', 'brave', 'browser'].includes(kind)) throw new Error('MINDPET_SEARCH_PROVIDER 仅支持 auto、tavily、brave、browser')
    const key = JSON.stringify([kind, query.trim().replace(/\s+/g, ' '), options.maxResults])
    if (options.abortSignal?.aborted) throw new Error('UserAborted')
    const cached = this.cache.get(key)
    if (cached && cached.expires > Date.now()) return cached.value
    const deadline = Date.now() + options.timeoutMs
    let limitation = ''
    if (kind !== 'browser') {
      try {
        const credential = kind === 'tavily' ? process.env.TAVILY_API_KEY : process.env.BRAVE_SEARCH_API_KEY
        if (!credential) throw new Error('未配置搜索服务凭据')
        const signal = options.abortSignal ? AbortSignal.any([options.abortSignal, AbortSignal.timeout(Math.min(12000, options.timeoutMs))])
          : AbortSignal.timeout(Math.min(12000, options.timeoutMs))
        const url = kind === 'tavily' ? 'https://api.tavily.com/search'
          : `https://api.search.brave.com/res/v1/web/search?q=${encodeURIComponent(query)}&count=${options.maxResults}`
        const response = await fetch(url, kind === 'tavily' ? {
          method: 'POST', signal, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${credential}` },
          body: JSON.stringify({ query, max_results: options.maxResults, search_depth: 'basic', include_raw_content: false, include_answer: false })
        } : { signal, headers: { 'X-Subscription-Token': credential, Accept: 'application/json' } })
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        const data = await response.json() as any
        const items = kind === 'tavily' ? data.results : data.web?.results
        if (!Array.isArray(items)) throw new Error('搜索响应格式不正确')
        const results: SearchResult[] = items.flatMap(item => {
          try {
            const url = new URL(String(item.url))
            if (!['http:', 'https:'].includes(url.protocol)) return []
            return [{ title: String(item.title || '').slice(0, 300), url: url.toString(), snippet: String(item.content || item.description || '').slice(0, 1600) }]
          } catch { return [] }
        }).slice(0, options.maxResults)
        return this.remember(key, { provider: kind, results })
      } catch (error) {
        if (options.abortSignal?.aborted) throw new Error('UserAborted')
        // Never log credential-bearing request objects or provider response bodies.
        limitation = `${kind} 搜索失败（${error instanceof Error && /^HTTP \d+$/.test(error.message) ? error.message : '连接、凭据或响应异常'}），已尝试浏览器搜索。`
      }
    }
    const remaining = deadline - Date.now()
    if (remaining < 5000) throw new Error(limitation || '搜索时间预算已用完')
    try {
      const results = await LocalBrowser.localSearch(query, { ...options, timeoutMs: remaining })
      return this.remember(key, { provider: 'browser', results, ...(limitation ? { limitation } : {}) })
    } catch {
      if (options.abortSignal?.aborted) throw new Error('UserAborted')
      throw new Error(`${limitation} 浏览器搜索失败：页面加载或结果解析未完成。可依据已有知识回答并标明时效限制。`)
    }
  }
  private static remember(key: string, value: SearchResponse): SearchResponse {
    if (value.results.length > 0) {
      if (this.cache.size >= 64) this.cache.delete(this.cache.keys().next().value!)
      this.cache.set(key, { expires: Date.now() + 60000, value })
    }
    return value
  }
}
