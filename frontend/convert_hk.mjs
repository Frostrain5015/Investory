import { Converter } from 'opencc-js'
import { readFileSync, writeFileSync } from 'fs'

const converter = Converter({ from: 'cn', to: 'tw' })

const src = readFileSync('src/i18n/translations.ts', 'utf8')

// Extract the zh block
const zhStart = src.indexOf('const zh = {')
const zhEnd = src.indexOf('\n} as const', zhStart)
const zhBlock = src.slice(zhStart, zhEnd + 2)

// Convert all Chinese strings in the zh block
const stringRegex = /"([^"]*[一-鿿][^"]*)"/g
const conversions = new Map()
let match
while ((match = stringRegex.exec(zhBlock)) !== null) {
  const original = match[1]
  if (!conversions.has(original)) {
    const converted = converter(original)
    conversions.set(original, converted)
    if (converted !== original) {
      console.log(`  ${original.slice(0, 30)} → ${converted.slice(0, 30)}`)
    }
  }
}
console.log(`Converted ${conversions.size} unique strings`)

// Build hk block by replacing each string in the zh block
let hkBlock = zhBlock
for (const [orig, conv] of conversions) {
  // Replace only within string quotes (not in keys/comments)
  hkBlock = hkBlock.split(`"${orig}"`).join(`"${conv}"`)
}
hkBlock = hkBlock.replace('const zh = {', 'const hk = {')

// Replace the old hk block in the source
const hkStartMarker = '// Traditional Chinese — copy zh as base'
const hkOldStart = src.indexOf(hkStartMarker)
let result
if (hkOldStart >= 0) {
  const hkOldEnd = src.indexOf('\nexport const LANGS', hkOldStart)
  result = src.slice(0, hkOldStart) + hkBlock + '\n' + src.slice(hkOldEnd)
} else {
  // Find where to insert hk
  const insertPoint = src.lastIndexOf('\nexport const LANGS')
  result = src.slice(0, insertPoint) + '\n' + hkBlock + '\n' + src.slice(insertPoint)
}

writeFileSync('src/i18n/translations.ts', result, 'utf8')
console.log('Done — translations.ts updated with proper hk block')
