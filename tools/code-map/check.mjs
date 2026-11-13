#!/usr/bin/env node
// Code map checker: mechanical rules for a folder of per-module code map files.
// Usage: node tools/code-map/check.mjs [dir] [--id-prefix shipr-] [--max 160]
// Self-contained (Node built-ins only) so it can be shipped on its own.
//
// Entry line: "- <path>[, <path>...]: <description>". Rules:
//   1. description at most --max characters
//   2. no bead ID (<id-prefix><alnum>[.<digits>]...)
//   3. every path exists (after {A,B} brace expansion; * ? ** globs match at least one entry)
//   4. every map file except index.md is linked from index.md, every index link exists
//   5. no path is listed in two map files (or twice in one)
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SKIP_DIRS = new Set(['.git', 'node_modules', 'target']);

/** Expands {a,b} groups (nested allowed) into a list of strings. */
export function expandBraces(s) {
  const open = s.indexOf('{');
  if (open < 0) return [s];
  let depth = 0;
  let close = -1;
  for (let i = open; i < s.length; i++) {
    if (s[i] === '{') depth++;
    else if (s[i] === '}' && --depth === 0) { close = i; break; }
  }
  if (close < 0) return [s];
  const parts = [];
  let cur = '';
  depth = 0;
  for (const c of s.slice(open + 1, close)) {
    if (c === '{') depth++;
    if (c === '}') depth--;
    if (c === ',' && depth === 0) { parts.push(cur); cur = ''; } else cur += c;
  }
  parts.push(cur);
  const pre = s.slice(0, open);
  const post = s.slice(close + 1);
  return parts.flatMap((p) => expandBraces(pre + p + post));
}

/** Splits a path list on commas that are outside braces. */
export function splitPaths(s) {
  const out = [];
  let cur = '';
  let depth = 0;
  for (const c of s) {
    if (c === '{') depth++;
    if (c === '}') depth--;
    if (c === ',' && depth === 0) { out.push(cur.trim()); cur = ''; } else cur += c;
  }
  out.push(cur.trim());
  return out.filter(Boolean).map((p) => p.replace(/^`+|`+$/g, ''));
}

function segmentRegex(seg) {
  const re = seg.replace(/[.+^$()|[\]\\]/g, '\\$&').replace(/\*/g, '[^/]*').replace(/\?/g, '[^/]');
  return new RegExp(`^${re}$`);
}

/** True if `p` is a normalized repo-relative path or glob: no absolute, backslash, ./ prefix, empty, . or .. segments. */
export function pathNormalized(p) {
  if (p.startsWith('/') || /^[A-Za-z]:/.test(p) || p.includes('\\') || p.startsWith('./')) return false;
  return !p.replace(/\/$/, '').split('/').some((s) => s === '' || s === '.' || s === '..');
}

/**
 * True if a path or glob (* ? ** segments) matches at least one file or directory below root.
 * Names match case-sensitively on every platform; a match whose real path leaves the real root
 * (symlink escape) does not count.
 */
export function globMatches(root, pattern) {
  const realRoot = fs.realpathSync(root);
  const confined = (p) => {
    try {
      const real = fs.realpathSync(p);
      return real === realRoot || real.startsWith(realRoot + path.sep);
    } catch { return false; }
  };
  const isDir = (dir, e) => {
    if (e.isDirectory()) return true;
    try { return e.isSymbolicLink() && fs.statSync(path.join(dir, e.name)).isDirectory(); } catch { return false; }
  };
  const segs = pattern.replace(/\/+$/, '').split('/').filter(Boolean);
  const walk = (dir, i) => {
    if (i === segs.length) return confined(dir);
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return false; }
    const seg = segs[i];
    if (seg === '**') {
      if (walk(dir, i + 1)) return true;
      return entries.some((e) => e.isDirectory() && !SKIP_DIRS.has(e.name) && walk(path.join(dir, e.name), i));
    }
    const re = segmentRegex(seg);
    return entries.some((e) => re.test(e.name) && (i === segs.length - 1 || isDir(dir, e)) && walk(path.join(dir, e.name), i + 1));
  };
  return walk(root, 0);
}

export function pathExists(root, p) {
  return globMatches(root, p);
}

function listMarkdown(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...listMarkdown(full));
    else if (e.name.endsWith('.md')) out.push(full);
  }
  return out.sort();
}

/**
 * Checks the map folder `dir` against the repo at `root`.
 * Returns violations as { file, line, message } (file relative to root, line 1-based).
 */
export function check(dir, { root, idPrefix = 'shipr-', max = 160 } = {}) {
  const violations = [];
  const rel = (f) => path.relative(root, f).split(path.sep).join('/');
  const add = (file, line, message) => violations.push({ file: rel(file), line, message });
  const idRe = new RegExp(`\\b${idPrefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[a-z0-9]+(\\.[0-9]+)*\\b`);

  if (!fs.existsSync(dir)) {
    violations.push({ file: rel(dir), line: 1, message: 'code map directory does not exist' });
    return violations;
  }
  const files = listMarkdown(dir);
  const indexFile = path.join(dir, 'index.md');
  const seen = new Map(); // path -> "file:line"

  for (const file of files) {
    if (file === indexFile) continue;
    fs.readFileSync(file, 'utf8').split('\n').forEach((raw, idx) => {
      const line = idx + 1;
      const text = raw.replace(/\r$/, '');
      if (!text.startsWith('- ')) {
        if (/^\s*[-*+]\s/.test(text)) add(file, line, "entry must look like '- <path>: <description>'");
        return;
      }
      const sep = text.indexOf(': ');
      if (sep < 0) { add(file, line, "entry must look like '- <path>: <description>'"); return; }
      const description = text.slice(sep + 2).trim();
      if (description.length > max) add(file, line, `description is ${description.length} characters, max ${max}`);
      const id = idRe.exec(text);
      if (id) add(file, line, `bead ID '${id[0]}' in entry`);
      for (const raw of splitPaths(text.slice(2, sep))) {
        for (const p of expandBraces(raw)) {
          if (!pathNormalized(p)) { add(file, line, `path is not a normalized repo-relative path: ${p}`); continue; }
          if (!pathExists(root, p)) add(file, line, `path does not exist: ${p}`);
          const key = p.replace(/\/+$/, '');
          if (seen.has(key)) add(file, line, `path also listed at ${seen.get(key)}: ${p}`);
          else seen.set(key, `${rel(file)}:${line}`);
        }
      }
    });
  }

  if (!fs.existsSync(indexFile)) {
    add(indexFile, 1, 'index.md is missing');
    return violations;
  }
  const linked = new Set();
  fs.readFileSync(indexFile, 'utf8').split('\n').forEach((text, idx) => {
    for (const m of text.matchAll(/\]\(([^)\s]+)\)/g)) {
      const target = m[1].split('#')[0];
      if (!target || /^[a-z]+:/i.test(target)) continue;
      const full = path.resolve(dir, target);
      if (!fs.existsSync(full)) add(indexFile, idx + 1, `link target does not exist: ${target}`);
      else linked.add(full);
    }
  });
  for (const file of files) {
    if (file !== indexFile && !linked.has(path.resolve(file))) add(file, 1, 'map file is not linked from index.md');
  }
  return violations;
}

function resolveRoot() {
  try {
    return execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  }
}

export function parseArgs(argv) {
  const opts = { dir: 'docs/code-map', idPrefix: 'shipr-', max: 160 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--id-prefix') opts.idPrefix = argv[++i];
    else if (a === '--max') opts.max = Number(argv[++i]);
    else if (a.startsWith('--')) throw new Error(`unknown option ${a}`);
    else opts.dir = a;
  }
  if (!opts.idPrefix || !Number.isInteger(opts.max) || opts.max < 1) throw new Error('invalid --id-prefix or --max');
  return opts;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const opts = parseArgs(process.argv.slice(2));
    const root = resolveRoot();
    const violations = check(path.resolve(root, opts.dir), { root, idPrefix: opts.idPrefix, max: opts.max });
    for (const v of violations) console.error(`${v.file}:${v.line}: ${v.message}`);
    if (violations.length) {
      console.error(`${violations.length} code map violation(s)`);
      process.exit(1);
    }
    console.log('code map ok');
  } catch (e) {
    console.error(e.message);
    process.exit(2);
  }
}
