#!/usr/bin/env node
/**
 * Syncs site/src/content/docs/**\/*.mdx → docs/**\/*.md
 *
 * Converts MDX to GitHub-readable Markdown:
 *   - Strips `import` statements (MDX-only, not valid in .md)
 *   - Converts Starlight admonitions (:::note etc.) to blockquotes
 *   - Rewrites absolute doc-site links (/guides/foo) to relative .md paths
 *     (../guides/foo.md) so links work when browsing docs/ on GitHub
 *   - Leaves frontmatter, prose, and code blocks untouched
 *   - JSX component tags (<Tabs>, <Card>) are left as-is; GitHub's
 *     markdown renderer ignores unknown HTML tags so content still reads.
 *
 * Usage:
 *   node scripts/sync-docs.js
 *
 * Run this whenever site/src/content/docs/ changes and you want the
 * docs/ mirror to stay up to date for GitHub browsing.
 */

import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'fs';
import { join, relative, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const SRC  = join(ROOT, 'site', 'src', 'content', 'docs');
const DST  = join(ROOT, 'docs');

// ── Collect all .mdx files ────────────────────────────────────────────────────
function findMdx(dir) {
  const results = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...findMdx(full));
    } else if (entry.name.endsWith('.mdx')) {
      results.push(full);
    }
  }
  return results;
}

// ── Rewrite absolute doc-site links → relative .md paths ─────────────────────
// Matches [text](/path) and [text](/path#anchor).
// srcRel is the source file path relative to SRC (e.g. 'guides/tile-grid.mdx').
// External links (https://…) and same-page anchors (#…) are left untouched.
function rewriteLinks(content, srcRel) {
  const srcDir = dirname(srcRel.replace(/\\/g, '/'));
  return content.replace(
    /\[([^\]]*)\]\(\/((?:[^)#\s])+)(#[^)\s]*)?\)/g,
    (_, text, path, anchor = '') => {
      const relPath = relative(srcDir, path.replace(/\/$/, '') + '.md').replace(/\\/g, '/');
      return `[${text}](${relPath}${anchor})`;
    }
  );
}

// ── Convert MDX → plain Markdown ─────────────────────────────────────────────
function convert(content, srcRel) {
  return rewriteLinks(
    content
      // Strip import statements
      .replace(/^import\s+\{[^}]*\}\s+from\s+['"][^'"]+['"]\s*;?\s*\n/gm, '')
      .replace(/^import\s+\S+\s+from\s+['"][^'"]+['"]\s*;?\s*\n/gm, '')
      // Starlight admonitions → GitHub blockquotes
      .replace(/^:::note\b[^\n]*\n/gm,    '> **Note**\n>\n')
      .replace(/^:::tip\b[^\n]*\n/gm,     '> **Tip**\n>\n')
      .replace(/^:::caution\b[^\n]*\n/gm, '> **Caution**\n>\n')
      .replace(/^:::danger\b[^\n]*\n/gm,  '> **Danger**\n>\n')
      .replace(/^:::\s*\n/gm,             '\n')
      // Collapse runs of 3+ blank lines to 2
      .replace(/\n{3,}/g, '\n\n')
      .trimEnd() + '\n',
    srcRel
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────
const files = findMdx(SRC);
let count = 0;

for (const src of files) {
  const rel = relative(SRC, src);
  const dst = join(DST, rel.replace(/\.mdx$/, '.md'));

  mkdirSync(dirname(dst), { recursive: true });
  writeFileSync(dst, convert(readFileSync(src, 'utf8'), rel));

  console.log(`  ${rel.replace(/\\/g, '/')}  →  docs/${rel.replace(/\\/g, '/').replace(/\.mdx$/, '.md')}`);
  count++;
}

console.log(`\n✓ Synced ${count} files.`);
