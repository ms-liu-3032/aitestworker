import { spawnSync } from 'node:child_process'
import { mkdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const worker = join(root, '..', 'local-browser-worker')
const release = join(root, 'release')

function run(cwd, ...args) {
  const result = spawnSync('npm', args, { cwd, stdio: 'inherit' })
  if (result.status !== 0) process.exit(result.status ?? 1)
}

run(worker, 'run', 'build')
// npm keeps a previous file: snapshot when its version has not changed.
// Refresh it so the bundle always contains the worker source just built.
rmSync(join(root, 'node_modules', 'local-browser-worker'), { recursive: true, force: true })
run(root, 'install', '--install-links')
run(root, 'run', 'build')
mkdirSync(release, { recursive: true })
run(root, 'pack', '--pack-destination', release)
