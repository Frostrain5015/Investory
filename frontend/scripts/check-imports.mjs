import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = path.join(root, 'src')
const configPath = path.join(root, 'tsconfig.app.json')
const config = ts.readConfigFile(configPath, ts.sys.readFile)
if (config.error) throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText, '\n'))
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root)
if (parsed.errors.length)
  throw new Error(
    parsed.errors
      .map((error) => ts.flattenDiagnosticMessageText(error.messageText, '\n'))
      .join('\n'),
  )
const program = ts.createProgram(parsed.fileNames, parsed.options)
const sources = new Map(
  program
    .getSourceFiles()
    .filter((file) => !file.isDeclarationFile)
    .map((file) => [path.resolve(file.fileName), file])
    .filter(([filename]) => filename.startsWith(sourceRoot + path.sep)),
)
const entry = path.join(sourceRoot, 'main.tsx')
if (!sources.has(entry))
  throw new Error('Application entry main.tsx was not included in the import graph')

const reachable = new Set()
function visit(filename) {
  const resolved = path.resolve(filename)
  if (reachable.has(resolved)) return
  reachable.add(resolved)
  const source = sources.get(resolved)
  if (!source) return
  function walk(node) {
    const specifier =
      ts.isImportDeclaration(node) || ts.isExportDeclaration(node)
        ? node.moduleSpecifier
        : ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword
          ? node.arguments[0]
          : undefined
    if (specifier && ts.isStringLiteral(specifier)) {
      const dependency = ts.resolveModuleName(
        specifier.text,
        resolved,
        parsed.options,
        ts.sys,
      ).resolvedModule
      if (dependency) visit(dependency.resolvedFileName)
    }
    ts.forEachChild(node, walk)
  }
  walk(source)
}
visit(entry)
const unused = [...sources.keys()].filter((filename) => !reachable.has(filename)).sort()
if (unused.length) {
  console.error('Unreachable source modules (add a real import or remove the module):')
  for (const filename of unused) console.error('  ' + path.relative(root, filename))
  process.exitCode = 1
} else {
  console.log(`Import graph: all ${sources.size} application modules are reachable from main.tsx.`)
}

// Keep this check scoped to application source. Runtime assets and global .d.ts
// declarations are not modules and need separate reference/contract review.
