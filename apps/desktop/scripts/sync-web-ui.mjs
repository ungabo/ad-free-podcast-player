import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const desktopDir = resolve(scriptDir, '..')
const rootDir = resolve(desktopDir, '..', '..')
const webDistDir = resolve(rootDir, 'apps', 'web', 'dist')
const playerUiDir = resolve(desktopDir, 'player-ui')
const command = process.platform === 'win32' ? 'cmd.exe' : 'npm'
const args = process.platform === 'win32'
  ? ['/d', '/s', '/c', 'npm run build -w apps/web']
  : ['run', 'build', '-w', 'apps/web']

execFileSync(command, args, {
  cwd: rootDir,
  stdio: 'inherit',
})

if (!existsSync(resolve(webDistDir, 'index.html'))) {
  throw new Error(`Web build output is missing: ${webDistDir}`)
}

rmSync(playerUiDir, { recursive: true, force: true })
mkdirSync(playerUiDir, { recursive: true })
cpSync(webDistDir, playerUiDir, { recursive: true })

console.log(`Synced web UI into ${playerUiDir}`)
