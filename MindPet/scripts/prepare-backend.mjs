import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const desktopDir = resolve(here, '..')
const backendDir = resolve(desktopDir, '..', 'MindPet-java')
const outputDir = join(desktopDir, 'build', 'backend')
const runtimeDir = join(outputDir, 'runtime')
const isWindows = process.platform === 'win32'
const mvn = isWindows ? 'mvn.cmd' : 'mvn'

function run(command, args, cwd) {
  // Windows batch launchers such as mvn.cmd must be invoked through cmd.exe.
  const useCmd = isWindows && command.toLowerCase().endsWith('.cmd')
  const executable = useCmd ? (process.env.ComSpec || 'cmd.exe') : command
  const commandArgs = useCmd ? ['/d', '/s', '/c', command, ...args] : args
  const result = spawnSync(executable, commandArgs, { cwd, stdio: 'inherit', shell: false })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`)
}

run(mvn, ['clean', 'package', '-DskipTests'], backendDir)
const jar = join(backendDir, 'target', 'weather-wechat-bot-1.0.0.jar')
if (!existsSync(jar)) throw new Error(`Backend JAR missing: ${jar}`)

rmSync(outputDir, { recursive: true, force: true })
mkdirSync(outputDir, { recursive: true })
cpSync(jar, join(outputDir, 'mindpet-backend.jar'))

const javaHome = process.env.JAVA_HOME
if (!javaHome) throw new Error('JAVA_HOME is required to build the bundled Java runtime')
const jlink = join(javaHome, 'bin', isWindows ? 'jlink.exe' : 'jlink')
if (!existsSync(jlink)) throw new Error(`jlink missing: ${jlink}`)

const modules = [
  'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging',
  'java.management', 'java.naming', 'java.net.http', 'java.prefs', 'java.rmi',
  'java.scripting', 'java.security.jgss', 'java.security.sasl', 'java.sql',
  'java.transaction.xa', 'java.xml', 'jdk.crypto.ec', 'jdk.httpserver',
  'jdk.management', 'jdk.naming.dns', 'jdk.unsupported'
].join(',')
run(jlink, ['--add-modules', modules, '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6', '--output', runtimeDir], desktopDir)
console.log(`[prepare-backend] created ${outputDir}`)
