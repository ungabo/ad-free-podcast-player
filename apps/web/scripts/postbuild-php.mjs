import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const distDir = resolve(process.cwd(), 'dist')
const indexHtmlPath = resolve(distDir, 'index.html')
const indexPhpPath = resolve(distDir, 'index.php')

if (!existsSync(indexHtmlPath)) {
  throw new Error(`Cannot create PHP entrypoint because ${indexHtmlPath} does not exist.`)
}

const indexHtml = readFileSync(indexHtmlPath, 'utf8')
const phpHeader = '<?php /* Generated from dist/index.html */ ?>\n'

writeFileSync(indexPhpPath, `${phpHeader}${indexHtml}`, 'utf8')
console.log(`Generated ${indexPhpPath}`)
