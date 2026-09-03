import { createLowlight } from 'lowlight'
import bash from 'highlight.js/lib/languages/bash'
import diff from 'highlight.js/lib/languages/diff'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import groovy from 'highlight.js/lib/languages/groovy'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { visit } from 'unist-util-visit'
import type { Element, Root } from 'hast'

/**
 * Syntax highlighting for the languages this corpus actually contains.
 *
 * Written by hand rather than using `rehype-highlight` for one concrete reason: that package
 * references highlight.js's `common` bundle at module scope, so the ~35 languages in it are
 * reachable from the entry graph and no bundler can tree-shake them. It costs well over 100KB
 * gzipped to highlight a YAML block.
 *
 * Registering a subset directly costs only what is listed below. The set is aimed at internal
 * engineering documentation — configuration, commands, and the JVM stack this project runs on —
 * and an unregistered language degrades to unhighlighted text, never to an error.
 */

const lowlight = createLowlight()

lowlight.register({
  bash,
  diff,
  dockerfile,
  groovy,
  ini,
  java,
  javascript,
  json,
  python,
  shell,
  sql,
  typescript,
  xml,
  yaml,
})

/** Aliases readers actually type in a fence, mapped to what is registered. */
const ALIASES: Record<string, string> = {
  sh: 'bash',
  zsh: 'bash',
  console: 'shell',
  shell: 'shell',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  yml: 'yaml',
  html: 'xml',
  svg: 'xml',
  properties: 'ini',
  toml: 'ini',
  conf: 'ini',
  postgresql: 'sql',
  psql: 'sql',
  gradle: 'groovy',
  py: 'python',
  docker: 'dockerfile',
}

function resolveLanguage(className: unknown): string | null {
  if (!Array.isArray(className)) return null

  for (const entry of className) {
    if (typeof entry !== 'string' || !entry.startsWith('language-')) continue
    const raw = entry.slice('language-'.length).toLowerCase()
    const name = ALIASES[raw] ?? raw
    if (lowlight.registered(name)) return name
  }
  return null
}

/**
 * A rehype plugin that highlights fenced code blocks.
 *
 * Only `<pre><code class="language-x">` is touched: inline code carries no language and
 * highlighting it produces noise inside a sentence.
 */
export function rehypeHighlightSubset() {
  return (tree: Root): void => {
    visit(tree, 'element', (node: Element, _index, parent) => {
      if (node.tagName !== 'code') return
      if (!parent || parent.type !== 'element' || parent.tagName !== 'pre') return

      const language = resolveLanguage(node.properties?.className)
      if (!language) return

      const source = toText(node)
      if (!source.trim()) return

      try {
        const highlighted = lowlight.highlight(language, source)
        node.children = highlighted.children as Element['children']
        node.properties = {
          ...node.properties,
          className: [...asArray(node.properties?.className), 'hljs'],
        }
      } catch {
        // A grammar can throw on pathological input. Plain text is a fine outcome; a crashed
        // render of an entire answer is not.
      }
    })
  }
}

function asArray(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((entry): entry is string => typeof entry === 'string')
  return typeof value === 'string' ? [value] : []
}

function toText(node: Element): string {
  let text = ''
  visit(node, 'text', textNode => { text += textNode.value })
  return text
}

/** The languages that will be highlighted. Exported so a test can assert the budget. */
export const SUPPORTED_LANGUAGES = lowlight.listLanguages()
