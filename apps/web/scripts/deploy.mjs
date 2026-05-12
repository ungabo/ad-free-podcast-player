/**
 * Deploy dist/ and server files to the production server via SCP.
 * Run via: npm run deploy  (which first builds, then calls this script)
 */
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve, join } from 'node:path'

const ROOT = resolve(process.cwd(), '../..')
const DIST = resolve(process.cwd(), 'dist')
const SCP = String.raw`C:\Windows\System32\OpenSSH\scp.exe`
const SSH = String.raw`C:\Windows\System32\OpenSSH\ssh.exe`
const HOST = 'agitated-engelbart_9pw3g4pzt1v@74.208.203.194'
const REMOTE_HTTPDOCS_ROOT = '/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/httpdocs'
const REMOTE_HTTPDOCS = `${REMOTE_HTTPDOCS_ROOT}/adfree-web`
const REMOTE_STACK = '/var/www/vhosts/agitated-engelbart.74-208-203-194.plesk.page/adfree-stack'

function scp(localPath, remotePath, recursive = false) {
  const args = ['-o', 'StrictHostKeyChecking=accept-new']
  if (recursive) args.push('-r')
  args.push(localPath, `${HOST}:${remotePath}`)
  console.log(`  scp ${localPath} → ${remotePath}`)
  execFileSync(SCP, args, { stdio: 'inherit' })
}

function ssh(cmd) {
  execFileSync(SSH, ['-o', 'StrictHostKeyChecking=accept-new', HOST, cmd], { stdio: 'inherit' })
}

if (!existsSync(join(DIST, 'index.php'))) {
  throw new Error('dist/index.php missing — run npm run build first')
}

console.log('\n── Deploying web app ─────────────────────────────')
scp(join(DIST, 'index.php'), `${REMOTE_HTTPDOCS}/index.php`)
// Remove old assets dir and SCP the folder into the parent — SCP creates the dir name itself
ssh(`rm -rf ${REMOTE_HTTPDOCS}/assets`)
scp(join(DIST, 'assets'), `${REMOTE_HTTPDOCS}/`, true)

console.log('\n── Deploying API ─────────────────────────────────')
scp(join(ROOT, 'apps/server/api/public/index.php'), `${REMOTE_HTTPDOCS_ROOT}/adfree-api/index.php`)

console.log('\n── Deploying worker & adcutforge ─────────────────')
scp(join(ROOT, 'apps/server/worker/process_jobs.py'), `${REMOTE_STACK}/worker/process_jobs.py`)
scp(join(ROOT, 'apps/server/adcutforge/src/ad_cut_forge.py'), `${REMOTE_STACK}/adcutforge/src/ad_cut_forge.py`)

console.log('\n✓ Deploy complete\n')
