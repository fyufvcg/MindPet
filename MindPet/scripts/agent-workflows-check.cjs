const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const { test, after } = require('node:test')
const { EventEmitter } = require('node:events')
const ts = require('typescript')
// Real executors, with transport stubs; no desktop, shell or remote side effects.
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mindpet-workflow-check-'))
after(() => fs.rmSync(root, { recursive: true, force: true }))
function load(relative, stubs) {
  const filename = path.resolve(__dirname, '../src/main/tools/builtin', relative)
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true }
  }).outputText
  const mod = new Module(filename, module)
  mod.filename = filename
  mod.paths = Module._nodeModulePaths(path.dirname(filename))
  const realRequire = mod.require.bind(mod)
  mod.require = id => Object.hasOwn(stubs, id) ? stubs[id] : realRequire(id)
  mod._compile(compiled, filename)
  return mod.exports
}
test('fetch extracts DOM content without waiting for all page resources to finish', async () => {
  let destroyed = false
  const contents = new EventEmitter()
  contents.executeJavaScript = async () => '# Loaded article\n' + 'body '.repeat(40)
  contents.isLoading = () => true
  const window = {
    webContents: contents, isDestroyed: () => destroyed, destroy: () => { destroyed = true },
    loadURL: () => { queueMicrotask(() => contents.emit('dom-ready')); return new Promise(() => {}) }
  }
  const { LocalBrowser } = load('web/localBrowser.ts', {
    electron: {}, dns: { promises: {} }, './externalBrowser': {}
  })
  LocalBrowser.assertSafeRemoteUrl = async value => new URL(value)
  LocalBrowser.createHiddenWindow = async () => window
  const result = await LocalBrowser.localFetch('https://example.com', { timeoutMs: 5000 })
  assert.match(result, /Loaded article/)
  assert.equal(destroyed, true)
})
test('concurrent MCP discovery shares a connection and backs off after 403', async () => {
  let attempts = 0
  class Client {
    async connect() { attempts++; await new Promise(resolve => setTimeout(resolve, 5)); throw Object.assign(new Error('Forbidden'), { code: 403 }) }
    async close() {}
  }
  const { McpManager } = load('../mcp/mcp-manager.ts', {
    '@modelcontextprotocol/sdk/client/index.js': { Client },
    '@modelcontextprotocol/sdk/client/sse.js': { SSEClientTransport: class {} },
    '@modelcontextprotocol/sdk/client/streamableHttp.js': { StreamableHTTPClientTransport: class {} },
    '../../storage-path': { getDefaultDataDir: () => root }, './mcp-name-mapper.js': {},
    '../../security/secure-mcp-config': {}
  })
  const manager = McpManager.getInstance()
  const config = { id: 'one', name: 'fixture', enabled: true, url: 'https://example.com', type: 'auto' }
  manager.setConfigs([config])
  await Promise.all([manager.ensureConnected(), manager.ensureConnected(), manager.ensureConnected()])
  assert.equal(attempts, 1)
  await manager.ensureConnected()
  assert.equal(attempts, 1)
})
const paths = {
  getSessionFilesDir: () => root, getActiveStorageDir: () => root, getAllowedFileRoots: () => [root],
  resolveSessionPath: value => path.resolve(root, value), resolveLocalPath: value => path.resolve(root, value),
  isPathWithinRoots: value => value === root || value.startsWith(root + path.sep)
}
const context = { sessionId: 'test', messageId: 1 }
test('nonzero terminal exit fails with output preserved', async () => {
  const { TerminalExecutor } = load('terminal/executor.ts', {
    './shell-manager': { shellManager: { exec: async () => ({ stdout: '', stderr: 'compile failed', exitCode: 7 }) } },
    './ssh-manager': { sshManager: { getDeviceType: () => 'local' } }, '../../utils/paths': paths
  })
  const result = await new TerminalExecutor().execute('run_terminal_command', { command: 'fake' }, context)
  assert.equal(result.success, false)
  assert.equal(result.state.exitCode, 7)
  assert.match(result.content, /compile failed/)
})
test('asynchronous command polling reports finished failures and missing sessions', async () => {
  let observation = { output: 'failed build', isRunning: false, exitCode: 3 }
  const { TerminalExecutor } = load('terminal/executor.ts', {
    './shell-manager': { shellManager: { getOutput: () => observation } },
    './ssh-manager': { sshManager: {} }, '../../utils/paths': paths
  })
  const executor = new TerminalExecutor()
  assert.equal((await executor.execute('get_command_output', { shell_id: 'one' }, context)).success, false)
  observation = { output: 'waiting', isRunning: true }
  assert.equal((await executor.execute('get_command_output', { shell_id: 'one' }, context)).success, true)
  observation = { output: 'gone', isRunning: false, error: 'SESSION_NOT_FOUND' }
  assert.equal((await executor.execute('get_command_output', { shell_id: 'one' }, context)).success, false)
})
test('an invalid Office artifact is not reported as a successful result', async () => {
  const { OfficeSkillExecutor } = load('office/skills/executor.ts', {
    './registry': { loadOfficeSkill: async () => ({
      descriptor: { name: 'test', operations: { validate: { inputSchema: { type: 'object', properties: {} } } } },
      execute: async () => ({ content: 'invalid', state: { validation: { valid: false } }, success: true })
    }) },
    './shared': { readToolResultState: result => result.state, skillError: error => ({ success: false, content: error.message }) }
  })
  const result = await new OfficeSkillExecutor().execute('run_office_skill', { skill: 'xlsx', action: 'validate', input: {} }, context)
  assert.equal(result.success, false)
  assert.equal(result.state.validation.valid, false)
})
test('bounded Markdown reads and character paging preserve access to long lines', async () => {
  const { FileExecutor } = load('file/executor.ts', { electron: { shell: {} }, '../../utils/paths': paths })
  const file = path.join(root, 'large.md')
  fs.writeFileSync(file, Array.from({ length: 1000 }, (_, i) => `row ${i + 1}`).join('\n'))
  const executor = new FileExecutor()
  const result = await executor.execute('read_file', { file_path: file }, context)
  assert.equal(result.success, true)
  assert.match(result.content, /row 300/)
  assert.doesNotMatch(result.content, /row 301/)
  fs.writeFileSync(file, 'x'.repeat(20000) + 'THE_END')
  const tail = await executor.execute('read_file', { file_path: file, offset: 19995, length: 50 }, context)
  assert.match(tail.content, /THE_END/)
  assert.match(tail.content, /has_more=false/)
  fs.writeFileSync(path.join(root, 'missing_已更新.md'), 'different document')
  const missing = await executor.execute('read_file', { file_path: path.join(root, 'missing.md') }, context)
  assert.equal(missing.success, false)
  assert.doesNotMatch(missing.content, /different document/)
})
test('an authorized path cannot follow a junction outside the authorized root', async () => {
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'mindpet-outside-fixture-'))
  try {
    fs.writeFileSync(path.join(outside, 'secret.txt'), 'outside data')
    fs.symlinkSync(outside, path.join(root, 'linked'), process.platform === 'win32' ? 'junction' : 'dir')
    const { FileExecutor } = load('file/executor.ts', { electron: { shell: {} }, '../../utils/paths': paths })
    const result = await new FileExecutor().execute('read_file', { file_path: path.join(root, 'linked', 'secret.txt') }, context)
    assert.equal(result.success, false)
    assert.doesNotMatch(result.content, /outside data/)
  } finally { fs.rmSync(outside, { recursive: true, force: true }) }
})
test('search cancellation differs from rg exit 1 for no matches', async () => {
  for (const [code, expected] of [['ABORT_ERR', false], [1, true], [2, false]]) {
    const { SearchExecutor } = load('search/executor.ts', {
      child_process: { execFile: (_file, _args, _options, cb) => cb({ code, message: 'failure' }, '', '') },
      'vscode-ripgrep': { rgPath: 'fake' }, '../../utils/paths': paths
    })
    assert.equal((await new SearchExecutor().execute('grep_content', { pattern: 'anything' }, context)).success, expected)
  }
})
test('SSH preserves exit status and cancellation closes the channel', async () => {
  const { SshManager } = load('terminal/ssh-manager.ts', { ssh2: { Client: class {} }, './shell-manager': { shellManager: {} } })
  const manager = SshManager.getInstance()
  let stream, closed = false
  manager.clients.set('test', { exec: (_command, callback) => {
    stream = new EventEmitter()
    stream.stderr = new EventEmitter()
    stream.close = () => { closed = true; stream.emit('close') }
    callback(null, stream)
  } })
  const failed = manager.executeCommand('test', 'fake')
  stream.emit('close', 9)
  assert.equal((await failed).exitCode, 9)
  const controller = new AbortController()
  const pending = manager.executeCommand('test', 'fake', undefined, { signal: controller.signal })
  controller.abort()
  await assert.rejects(pending, /SSH/)
  assert.equal(closed, true)
})
test('search and fetch preserve distinct compatible citations across sources', async () => {
  const { WebExecutor } = load('web/executor.ts', {
    '../../utils/paths': paths, '../../security/permission-manager': {}, './externalBrowser': {},
    './search-provider': { WebSearchProvider: { search: async () => ({ provider: 'test', results: [{ title: 'one', url: 'https://one.example/', snippet: 'one' }, { title: 'two', url: 'https://two.example/', snippet: 'two' }] }) } },
    './localBrowser': { LocalBrowser: {
      localSearch: async () => [{ title: 'one', url: 'https://one.example/', snippet: 'one' }, { title: 'two', url: 'https://two.example/', snippet: 'two' }],
      localFetch: async () => '# page\nuseful evidence'
    } }
  })
  const executor = new WebExecutor()
  const search = await executor.execute('web_search', { query: 'query' }, context)
  const fetched = await executor.execute('web_fetch', { url: 'https://two.example/' }, context)
  assert.notEqual(search.state.sources[0].id, search.state.sources[1].id)
  assert.equal(fetched.state.sources[0].id, search.state.sources[1].id)
  assert.match(fetched.state.sources[0].id, /^S\d+$/)
  assert.doesNotMatch(fetched.content, /必须.*read_file/)
})


test('MCP identities include service; errors and non-text blocks survive without replay', async () => {
  const { McpNameMapper } = load('../mcp/mcp-name-mapper.ts', {})
  const { McpManager } = load('../mcp/mcp-manager.ts', {
    './mcp-name-mapper.js': { McpNameMapper }, '../../storage-path': { getDefaultDataDir: () => root },
    '../../security/secure-mcp-config': {}
  })
  const manager = McpManager.getInstance()
  manager.systemMcpConfig = { servers: [
    { id: 'a', name: 'A', enabled: true, timeout: 1, tools: [{ name: 'search' }] },
    { id: 'b', name: 'B', enabled: true, timeout: 1, tools: [{ name: 'search' }] }
  ] }
  let calls = 0
  manager.connections.set('a', { tools: [{ name: 'search' }], client: { callTool: async () => {
    calls++; return { isError: true, content: [{ type: 'text', text: 'failed' }, { type: 'image', data: 'AA==', mimeType: 'image/png' }] }
  } } })
  manager.connections.set('b', { tools: [{ name: 'search' }], client: { callTool: async () => {
    calls++; throw new Error('lost response after write')
  } } })
  const names = manager.getTools().map(t => t.name)
  assert.notEqual(names[0], names[1])
  assert.equal(manager.resolveTool('search'), undefined)
  const first = await manager.executeTool(names[0], {})
  assert.equal(first.success, false)
  assert.equal(first.state.contentBlocks[1].type, 'image')
  const second = await manager.executeTool(names[1], {})
  assert.equal(second.outcome, 'unknown')
  assert.equal(calls, 2)
})

test('external MCP without read-only declaration requires approval and keeps failure status', async () => {
  let executions = 0
  const mcp = {
    hasTool: () => true,
    resolveTool: () => ({ server: { name: 'external' }, tool: { name: 'submit' } }),
    executeTool: async () => { executions++; return { success: false, content: 'failed', outcome: 'unknown' } }
  }
  let approved = false
  const { unifiedToolExecutor } = load('../core/tool-executor.ts', {
    './tool-registry': { toolRegistry: { getManifest: () => undefined, getExecutor: () => undefined } },
    '../security/audit-pipeline': {}, '../security/permission-manager': { permissionManager: { requestCommandPermission: async () => approved } },
    '../mcp/mcp-manager': { mcpManager: mcp }, '../utils/paths': { getSessionFilesDir: () => root }
  })
  const denied = await unifiedToolExecutor.execute('submit', {}, { workspacePath: root })
  assert.equal(denied.success, false); assert.equal(executions, 0)
  approved = true
  const result = await unifiedToolExecutor.execute('submit', {}, { workspacePath: root })
  assert.equal(result.success, false); assert.equal(result.outcome, 'unknown'); assert.equal(executions, 1)
})

test('structured search returns snippets without fetching result pages; provider failure falls back once', async () => {
  const oldFetch = global.fetch
  const previous = { provider: process.env.MINDPET_SEARCH_PROVIDER, key: process.env.TAVILY_API_KEY }
  let browserCalls = 0, apiCalls = 0
  const { WebSearchProvider } = load('web/search-provider.ts', {
    './localBrowser': { LocalBrowser: { localSearch: async () => { browserCalls++; return [{ title: 'fallback', url: 'https://example.com', snippet: 'data' }] } } }
  })
  try {
    process.env.MINDPET_SEARCH_PROVIDER = 'tavily'; process.env.TAVILY_API_KEY = 'test-only'
    global.fetch = async () => { apiCalls++; return { ok: true, json: async () => ({ results: [{ title: 'result', url: 'https://example.com', content: 'enough facts' }] }) } }
    const result = await WebSearchProvider.search('query', { timeoutMs: 10000, maxResults: 3 })
    assert.equal(result.provider, 'tavily'); assert.equal(browserCalls, 0); assert.equal(apiCalls, 1)
    await WebSearchProvider.search('query', { timeoutMs: 10000, maxResults: 3 })
    assert.equal(apiCalls, 1)
    global.fetch = async () => ({ ok: false, status: 429 })
    const fallback = await WebSearchProvider.search('other query', { timeoutMs: 10000, maxResults: 3 })
    assert.equal(fallback.provider, 'browser'); assert.equal(browserCalls, 1)
    assert.match(fallback.limitation, /HTTP 429/)
  } finally {
    global.fetch = oldFetch
    if (previous.provider === undefined) delete process.env.MINDPET_SEARCH_PROVIDER; else process.env.MINDPET_SEARCH_PROVIDER = previous.provider
    if (previous.key === undefined) delete process.env.TAVILY_API_KEY; else process.env.TAVILY_API_KEY = previous.key
  }
})


test('MCP settings preserve process-local bridge and keep external credentials out of Java', () => {
  let persisted
  const { McpManager } = load('../mcp/mcp-manager.ts', {
    './mcp-name-mapper.js': {}, '../../storage-path': { getDefaultDataDir: () => root },
    '../../security/secure-mcp-config': { saveSecureSystemMcpConfig: config => { persisted = config; return config } }
  })
  const manager = McpManager.getInstance()
  manager.systemMcpConfig = { servers: [{ id: 'desktop-tools', source: 'DESKTOP_BUILTIN', apiKey: 'old' }] }
  manager.registerDesktopBridge({ id: 'desktop-tools', name: 'Desktop', url: 'http://127.0.0.1:9339', apiKey: 'ephemeral', enabled: true })
  const server = { id: 'one', name: 'External', url: 'https://example.com?token=private', apiKey: 'external-key', enabled: true }
  manager.saveSystemMcpConfig({ servers: [server] })
  manager.saveSystemMcpConfig({ servers: [server, { id: 'desktop-tools', apiKey: '' }] })
  assert.equal(persisted.servers.length, 1)
  const backend = manager.getBackendConfig().servers
  assert.equal(backend.find(s => s.id === 'desktop-tools').apiKey, 'ephemeral')
  assert.equal(backend.find(s => s.id === 'one').apiKey, '')
  assert.doesNotMatch(JSON.stringify(backend), /external-key|token=private/)
})

test('system schedule tools share backend store and capture task scope', async () => {
  const calls = []
  const { SystemExecutor } = load('system/executor.ts', {
    '../../../agent/schedule-client': { scheduleClient: {
      upsert: async (...args) => { calls.push(['upsert', ...args]); return [] },
      delete: async (...args) => { calls.push(['delete', ...args]); return [] }
    } }, electron: {}, '../../security/permission-manager': {}, '../../interaction/clarification-manager': {}
  })
  const executor = new SystemExecutor()
  const scope = { ...context, selectedMcpServerIds: ['allowed'] }
  const created = await executor.execute('manage_cron_task', { action_type: 'create', name: 'check', interval: 60, action: 'check files' }, scope)
  assert.equal(created.success, true)
  assert.equal(calls[0][2].workspacePath, scope.workspacePath)
  assert.deepEqual(calls[0][2].selectedMcpServerIds, ['allowed'])
  const id = JSON.parse(created.content).taskId
  assert.equal((await executor.execute('manage_cron_task', { action_type: 'delete', taskId: id }, scope)).success, true)
  assert.equal(calls[1][1], id)
})

test('approval queue serializes prompts and cancellation settles stale UI', async () => {
  const ipcMain = new EventEmitter(), events = []
  const win = { webContents: { send: (channel, value) => events.push({ channel, value }) },
    isDestroyed: () => false, isFocused: () => true, isMinimized: () => false, isVisible: () => true }
  const { PermissionManager } = load('../security/permission-manager.ts', { electron: {
    ipcMain, app: {}, Notification: {}, BrowserWindow: { fromWebContents: () => win, getFocusedWindow: () => win, getAllWindows: () => [win] }
  } })
  const manager = PermissionManager.getInstance(), controller = new AbortController()
  const first = manager.requestCommandPermission({ command: 'one', execCwd: root, abortSignal: controller.signal })
  const second = manager.requestCommandPermission({ command: 'two', execCwd: root })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(events.filter(e => e.channel === 'api:request-permission').length, 1)
  controller.abort()
  assert.equal(await first, false)
  await new Promise(resolve => setImmediate(resolve))
  const prompts = events.filter(e => e.channel === 'api:request-permission')
  assert.equal(prompts.length, 2)
  assert.equal(events.find(e => e.channel === 'api:permission-settled').value.requestId, prompts[0].value.requestId)
  ipcMain.emit('api:permission-response', {}, { requestId: prompts[1].value.requestId, approved: true, scope: 'once' })
  assert.equal(await second, true)
  const third = manager.requestCommandPermission({ command: 'three', execCwd: root })
  manager.clearPendingPermissions()
  assert.equal(await third, false)
})


test('live MCP catalog refresh follows pagination and configuration changes invalidate connections', async () => {
  const { McpManager } = load('../mcp/mcp-manager.ts', {
    './mcp-name-mapper.js': {}, '../../storage-path': { getDefaultDataDir: () => root }, '../../security/secure-mcp-config': {}
  })
  const manager = McpManager.getInstance()
  const config = { id: 'catalog', url: 'https://example.com', enabled: true, apiKey: '' }
  let closed = 0, calls = 0
  const client = { close: async () => { closed++ }, listTools: async params => {
    calls++; return params?.cursor ? { tools: [{ name: 'second' }] } : { tools: [{ name: 'first' }], nextCursor: 'next' }
  } }
  manager.setConfigs([config])
  manager.connections.set('catalog', { client, tools: [{ name: 'stale' }], config })
  assert.deepEqual((await manager.getServerTools('catalog')).map(t => t.name), ['first', 'second'])
  assert.equal(calls, 2)
  manager.setConfigs([{ ...config, apiKey: 'changed' }])
  assert.equal(manager.connections.has('catalog'), false)
  assert.equal(closed, 1)
})

test('token persistence uses the latest shared state and clear cancels stale writes', () => {
  const { TokenLogPersistence } = load('../../../renderer/src/utils/tokenLogPersistence.ts', {})
  const writes = [], writer = new TokenLogPersistence(value => writes.push(value))
  writer.queue([{ id: 'chat' }]); writer.queue([{ id: 'chat' }, { id: 'scheduled' }]); writer.flush()
  assert.deepEqual(writes[0].map(item => item.id), ['chat', 'scheduled'])
  writer.queue([{ id: 'stale' }]); writer.queue([]); writer.flush()
  assert.deepEqual(writes[1], [])
  assert.equal(writes.length, 2)
})
