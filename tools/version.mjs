import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// Maven owns the product version. Dependency versions and historical docs do not.
export function readVersion(root = repositoryRoot) {
  const pom = readFileSync(resolve(root, 'backend/pom.xml'), 'utf8')
  const version = pom.match(/<artifactId>investory<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1]
  if (!version || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error('backend/pom.xml must contain a valid Investory release version')
  }
  return version
}

export function syncVersions(root = repositoryRoot, check = false) {
  const version = readVersion(root)
  const updates = []
  for (const directory of ['frontend', 'desktop']) {
    for (const filename of ['package.json', 'package-lock.json']) {
      const relative = `${directory}/${filename}`
      const path = resolve(root, relative)
      const document = JSON.parse(readFileSync(path, 'utf8'))
      let changed = document.version !== version
      document.version = version
      if (filename === 'package-lock.json') {
        if (!document.packages?.['']) throw new Error(`Missing root package in ${relative}`)
        changed ||= document.packages[''].version !== version
        document.packages[''].version = version
      }
      if (changed) updates.push({ path, relative, content: JSON.stringify(document, null, 2) + '\n' })
    }
  }
  if (check && updates.length) {
    throw new Error(`Version manifests need synchronization: ${updates.map(item => item.relative).join(', ')}. Run node tools/version.mjs`)
  }
  for (const update of updates) writeFileSync(update.path, update.content)
  return version
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    console.log(`Investory v${syncVersions(repositoryRoot, process.argv.includes('--check'))}`)
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}
