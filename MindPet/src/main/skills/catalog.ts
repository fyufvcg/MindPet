import * as fs from 'fs/promises'
import { join, relative, dirname, extname, resolve, sep } from 'path'

export interface InstalledSkill {
  content: string
  description: string
  rootPath: string
  resources: Record<string, string>
}

export function skillDescription(content: string): string {
  const frontmatter = content.match(/^\uFEFF?---\s*\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)?.[1]
  const description = frontmatter?.match(/^description:\s*(.+)$/m)?.[1]?.trim()
  if (description && !['|', '>'].includes(description)) return description.replace(/^["']|["']$/g, '').slice(0, 320)
  if (description) {
    const block = frontmatter?.match(/^description:\s*[|>]\s*\r?\n((?:[ \t]+.*(?:\r?\n|$))+)/m)?.[1]
    if (block) return block.trim().replace(/\s+/g, ' ').slice(0, 320)
  }
  const body = content.replace(/^\uFEFF?---[\s\S]*?\r?\n---(?:\r?\n|$)/, '')
  return body.split(/\r?\n/).map(line => line.trim())
    .find(line => line && !line.startsWith('#') && !line.startsWith('```'))?.slice(0, 320)
    || body.match(/^#+\s+(.+)$/m)?.[1]?.slice(0, 320) || '用户安装的技能，可按任务需要加载。'
}

export async function readSkillPackage(folder: string, packageName: string): Promise<Record<string, InstalledSkill>> {
  const root = await fs.realpath(folder)
  const files: string[] = []
  const scan = async (directory: string, depth: number): Promise<void> => {
    if (depth > 6 || files.length >= 500) return
    for (const item of await fs.readdir(directory, { withFileTypes: true })) {
      if (item.isSymbolicLink() || item.name.startsWith('.') || ['node_modules', '__pycache__'].includes(item.name)) continue
      const path = join(directory, item.name)
      if (item.isDirectory()) await scan(path, depth + 1)
      else if (item.isFile() && files.length < 500) files.push(path)
    }
  }
  await scan(root, 0)
  let customDescription = ''
  try { customDescription = String(JSON.parse(await fs.readFile(join(root, '.mindpet-skill.json'), 'utf8')).description || '') } catch { /* optional */ }
  const result: Record<string, InstalledSkill> = {}
  for (const entry of files.filter(path => /(?:^|[\\/])skill\.md$/i.test(path))) {
    const stat = await fs.stat(entry)
    if (stat.size > 512_000) throw new Error(`Skill 正文过大：${packageName}`)
    const content = await fs.readFile(entry, 'utf8')
    if (!content.trim()) continue
    const skillRoot = dirname(entry)
    const suffix = relative(root, skillRoot).replace(/\\/g, '/')
    const id = `user/${packageName}${suffix ? `/${suffix}` : ''}`
    const resources: Record<string, string> = {}
    let total = 0
    for (const path of files) {
      if (path === entry || !path.startsWith(skillRoot + sep)) continue
      if (!['.md', '.txt', '.json', '.yaml', '.yml', '.py', '.js', '.ts', '.ps1', '.sh', '.csv'].includes(extname(path).toLowerCase())) continue
      const resourceStat = await fs.stat(path)
      if (resourceStat.size > 256_000 || total + resourceStat.size > 2_000_000) continue
      resources[relative(skillRoot, path).replace(/\\/g, '/')] = await fs.readFile(path, 'utf8')
      total += resourceStat.size
    }
    result[id] = { content, description: (customDescription || skillDescription(content)).slice(0, 320), rootPath: skillRoot, resources }
  }
  return result
}

export function skillPackagePath(base: string, name: string): string {
  if (!name || name !== name.replace(/^.*[\\/]/, '') || name === '.' || name === '..') throw new Error('技能包名称无效')
  const target = resolve(base, name.replace(/\.zip$/i, ''))
  if (!target.startsWith(resolve(base) + sep)) throw new Error('技能包路径无效')
  return target
}
