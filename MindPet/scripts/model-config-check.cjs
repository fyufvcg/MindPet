const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const { test, after } = require('node:test')
const ts = require('typescript')
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mindpet-model-config-'))
after(() => fs.rmSync(root, { recursive: true, force: true }))
const cache = new Map()
function load(file) {
  const filename = path.resolve(__dirname, '../src/main', file)
  if (cache.has(filename)) return cache.get(filename).exports
  const mod = new Module(filename, module)
  cache.set(filename, mod)
  mod.filename = filename
  mod.paths = Module._nodeModulePaths(path.dirname(filename))
  const requireReal = mod.require.bind(mod)
  mod.require = id => id.startsWith('.') && fs.existsSync(path.resolve(path.dirname(filename), id + '.ts'))
    ? load(path.relative(path.resolve(__dirname, '../src/main'), path.resolve(path.dirname(filename), id + '.ts')))
    : requireReal(id)
  mod._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true }
  }).outputText, filename)
  return mod.exports
}
const { LlmConfigStore } = load('security/llm-config-store.ts')
const { EmbeddingConfigStore } = load('security/embedding-config-store.ts')
const { ModelConfigSync } = load('model-config-sync.ts')
function store(Class) {
  const secrets = new Map()
  const file = path.join(root, Math.random().toString(36) + '.json')
  const vault = { setSecret: (id, key) => secrets.set(id, key), getSecret: id => secrets.get(id), deleteSecret: id => secrets.delete(id) }
  return { value: new Class(file, vault), file }
}

test('switching providers without a new key clears the previous credential', () => {
  const { value, file } = store(LlmConfigStore)
  value.save({ provider: 'deepseek', baseUrl: 'https://api.deepseek.com/v1', apiKey: 'fixture-A' })
  const next = value.save({ provider: 'openai', baseUrl: 'https://api.openai.com/v1', apiKey: '', hasApiKey: true })
  assert.equal(next.apiKey, '')
  assert.equal(next.hasApiKey, false)
  assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /fixture-A/)
})
test('changing a custom base URL cannot carry a saved key to that address', () => {
  const { value } = store(LlmConfigStore)
  value.save({ provider: 'custom', baseUrl: 'https://one.example/v1', apiKey: 'fixture-A' })
  assert.equal(value.save({ baseUrl: 'https://two.example/v1' }).hasApiKey, false)
})
test('same service preserves a key during model changes, explicit replacement and clear work', () => {
  const { value } = store(LlmConfigStore)
  value.save({ provider: 'custom', baseUrl: 'https://one.example/v1', apiKey: 'fixture-A' })
  assert.equal(value.save({ model: 'new-model', apiKey: '' }).apiKey, 'fixture-A')
  assert.equal(value.save({ provider: 'openai', baseUrl: 'https://api.openai.com/v1', apiKey: 'fixture-B' }).apiKey, 'fixture-B')
  assert.equal(value.save({ clearApiKey: true }).apiKey, '')
})
test('embedding endpoint change clears credentials and renderer never receives a saved key', () => {
  const { value } = store(EmbeddingConfigStore)
  const saved = value.save({ apiKey: 'fixture-A', mode: 'DOUBAO' })
  assert.equal(value.toRenderer(saved).apiKey, '')
  assert.equal(value.toRenderer(saved).hasApiKey, true)
  assert.equal(value.save({ endpoint: 'https://other.example/embeddings' }).hasApiKey, false)
})
test('service URL rejects embedded credentials before changing the saved configuration', () => {
  const { value } = store(LlmConfigStore)
  value.save({ apiKey: 'fixture-A' })
  assert.throws(() => value.save({ baseUrl: 'https://example.com/v1?api_key=fixture-B' }))
  assert.equal(value.load().apiKey, 'fixture-A')
})
function sync(fetcher) {
  let llm = { provider: 'custom', apiKey: 'fixture-A', baseUrl: 'https://one.example/v1', model: 'a', hasApiKey: true }
  return {
    instance: new ModelConfigSync({ url: p => 'http://127.0.0.1:8080' + p,
      llm: () => llm, embedding: () => ({ mode: 'DOUBAO', apiKey: '', model: '', endpoint: '' }),
      systemPrompt: () => '', fetch: fetcher }),
    set: next => { llm = next }
  }
}
test('HTTP error, semantic error and transport failure remain pending without leaking responses', async () => {
  for (const fetcher of [async () => new Response('fixture-secret', { status: 500 }),
    async () => Response.json({ status: 'error', message: 'fixture-secret' }),
    async () => { throw new Error('fixture-secret') }]) {
    const { instance } = sync(fetcher)
    const result = await instance.apply('llm')
    assert.equal(instance.state('llm').state, 'pending')
    assert.doesNotMatch(JSON.stringify(result), /fixture-secret/)
  }
})
test('offline startup retry reads the latest saved snapshot', async () => {
  let online = false
  const sent = []
  const { instance, set } = sync(async (_, options) => {
    sent.push(JSON.parse(options.body))
    assert.ok(options.signal)
    if (!online) throw new Error('offline')
    return Response.json({ status: 'ok' })
  })
  await instance.apply('llm')
  set({ provider: 'custom', apiKey: 'fixture-B', baseUrl: 'https://two.example/v1', model: 'b' })
  online = true
  await instance.retryPending()
  assert.equal(sent.findLast(v => v.baseUrl)?.apiKey, 'fixture-B')
  assert.equal(instance.state('llm').state, 'applied')
})
test('overlapping saves cannot mark an old snapshot as the current applied configuration', async () => {
  let release
  const bodies = []
  const { instance, set } = sync(async (_, options) => {
    bodies.push(JSON.parse(options.body))
    if (bodies.length === 1) await new Promise(resolve => { release = resolve })
    return Response.json({ status: 'ok' })
  })
  const first = instance.apply('llm')
  await new Promise(resolve => setImmediate(resolve))
  set({ provider: 'custom', apiKey: '', baseUrl: 'https://two.example/v1', model: 'b' })
  const second = instance.apply('llm')
  release(); await Promise.all([first, second])
  assert.deepEqual(bodies.map(v => v.apiKey), ['fixture-A', ''])
  assert.equal(instance.state('llm').state, 'applied')
})
test('refresh includes the saved embedding snapshot and requests verification', async () => {
  let body
  const { instance } = sync(async (_, options) => { body = JSON.parse(options.body); return Response.json({ status: 'ok', available: false }) })
  const result = await instance.apply('embedding', true)
  assert.equal(body.mode, 'DOUBAO'); assert.equal(body.apiKey, ''); assert.equal(body.verify, true)
  assert.equal(result.available, false)
  assert.equal(instance.state('embedding').state, 'applied')
})
