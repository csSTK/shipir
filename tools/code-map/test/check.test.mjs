import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { check, expandBraces, splitPaths, parseArgs } from '../check.mjs';

/** Builds a throwaway repo: `files` are repo files, `map` are files below docs/code-map. */
function fixture(map, files = ['src/A.java', 'src/B.java', 'src/D.java', 'src/deep/C.java', 'web/x.ts']) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'code-map-'));
  for (const f of files) {
    fs.mkdirSync(path.dirname(path.join(root, f)), { recursive: true });
    fs.writeFileSync(path.join(root, f), '');
  }
  for (const [name, body] of Object.entries(map)) {
    const full = path.join(root, 'docs/code-map', name);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, body);
  }
  return root;
}
const run = (map, opts = {}, files) => {
  const root = fixture(map, files);
  return check(path.join(root, 'docs/code-map'), { root, ...opts });
};
const INDEX = '# Index\n\n| prefix | file |\n|---|---|\n| src/ | [src](src.md) |\n';
const messages = (v) => v.map((x) => x.message);

test('passing map: plain, brace and glob paths', () => {
  const v = run({
    'index.md': INDEX,
    'src.md': '# src\n- src/A.java: the A class\n- src/{B,D}.java, web/x.ts: pair\n- src/deep/**/*.java: deep glob\n- web/*.ts: top-level glob\n',
  });
  assert.deepEqual(v, []);
});

test('rule 1: description over the limit fails, at the limit passes', () => {
  const ok = run({ 'index.md': INDEX, 'src.md': `- src/A.java: ${'x'.repeat(160)}\n` });
  assert.deepEqual(ok, []);
  const bad = run({ 'index.md': INDEX, 'src.md': `- src/A.java: ${'x'.repeat(161)}\n` });
  assert.equal(bad.length, 1);
  assert.match(bad[0].message, /161 characters/);
  assert.equal(bad[0].line, 1);
  assert.equal(bad[0].file, 'docs/code-map/src.md');
});

test('rule 1: --max is configurable', () => {
  const v = run({ 'index.md': INDEX, 'src.md': '- src/A.java: 0123456789\n' }, { max: 5 });
  assert.match(v[0].message, /max 5/);
});

test('rule 2: bead ID fails, plain text and other prefix pass', () => {
  assert.deepEqual(run({ 'index.md': INDEX, 'src.md': '- src/A.java: shipir proxy, no id\n' }), []);
  const bad = run({ 'index.md': INDEX, 'src.md': '- src/A.java: added in shipr-abc1.23\n' });
  assert.match(bad[0].message, /shipr-abc1\.23/);
  assert.deepEqual(run({ 'index.md': INDEX, 'src.md': '- src/A.java: see shipr-abc\n' }, { idPrefix: 'other-' }), []);
  assert.equal(run({ 'index.md': INDEX, 'src.md': '- src/A.java: see other-9z\n' }, { idPrefix: 'other-' }).length, 1);
});

test('rule 3: missing plain, brace and glob paths fail', () => {
  const v = run({
    'index.md': INDEX,
    'src.md': '- src/Missing.java: x\n- src/{A,Z}.java: x\n- src/**/*.kt: x\n- nodir/*: x\n',
  });
  assert.deepEqual(messages(v), [
    'path does not exist: src/Missing.java',
    'path does not exist: src/Z.java',
    'path does not exist: src/**/*.kt',
    'path does not exist: nodir/*',
  ]);
});

test('rule 3: a directory path exists', () => {
  assert.deepEqual(run({ 'index.md': INDEX, 'src.md': '- src/deep/: folder\n' }), []);
});

test('rule 4: unlinked map file and dead index link fail, all linked passes', () => {
  const unlinked = run({ 'index.md': INDEX, 'src.md': '- src/A.java: x\n', 'web.md': '- web/x.ts: x\n' });
  assert.deepEqual(messages(unlinked), ['map file is not linked from index.md']);
  assert.equal(unlinked[0].file, 'docs/code-map/web.md');
  const dead = run({ 'index.md': INDEX + '[gone](gone.md)\n', 'src.md': '- src/A.java: x\n' });
  assert.match(dead[0].message, /link target does not exist: gone\.md/);
  assert.deepEqual(run({ 'index.md': INDEX + '[web](sub/web.md)\n', 'src.md': '', 'sub/web.md': '- web/x.ts: x\n' }), []);
});

test('rule 5: a path in two map files fails (also via brace and repeated in one file)', () => {
  const v = run({
    'index.md': INDEX + '[web](web.md)\n',
    'src.md': '- src/A.java: x\n',
    'web.md': '- src/{A,B}.java: x\n',
  });
  assert.equal(v.length, 1);
  assert.match(v[0].message, /also listed at docs\/code-map\/src\.md:1: src\/A\.java/);
  const same = run({ 'index.md': INDEX, 'src.md': '- src/A.java: x\n- src/A.java: y\n' });
  assert.equal(same.length, 1);
});

test('non-entry bullet in a map file fails; headings and prose are ignored; index.md is not parsed for entries', () => {
  const v = run({ 'index.md': INDEX + '- a rule bullet: with colon\n', 'src.md': '# h\nprose\n- just text\n' });
  assert.deepEqual(messages(v), ["entry must look like '- <path>: <description>'"]);
});

test('missing directory and missing index are reported', () => {
  const root = fixture({});
  assert.match(check(path.join(root, 'nope'), { root })[0].message, /does not exist/);
  const v = run({ 'src.md': '- src/A.java: x\n' });
  assert.ok(messages(v).includes('index.md is missing'));
});

test('helpers: brace expansion, path splitting, args', () => {
  assert.deepEqual(expandBraces('a/{b,c{d,e}}/f'), ['a/b/f', 'a/cd/f', 'a/ce/f']);
  assert.deepEqual(splitPaths('`a/{b,c}.ts`, d.ts'), ['a/{b,c}.ts', 'd.ts']);
  assert.deepEqual(parseArgs(['x', '--id-prefix', 'p-', '--max', '80']), { dir: 'x', idPrefix: 'p-', max: 80 });
  assert.throws(() => parseArgs(['--bogus']));
});

test('rule 3: path case must match exactly', () => {
  const v = run({ 'index.md': INDEX, 'src.md': '- src/a.java: x\n- SRC/B.java: x\n' });
  assert.deepEqual(messages(v), ['path does not exist: src/a.java', 'path does not exist: SRC/B.java']);
});

test('rule 3: non-normalized or escaping paths are rejected', () => {
  const v = run({
    'index.md': INDEX,
    'src.md': [
      '- ../x: x', '- ./src/A.java: x', '- src/{A,B}/../x: x', '- src/./A.java: x', '- /etc/passwd: x',
      '- src\\A.java: x', '- src//A.java: x', '- ../*: x',
    ].join('\n') + '\n',
  });
  assert.equal(v.length, 9); // the brace entry expands to two paths
  for (const m of messages(v)) assert.match(m, /^path is not a normalized repo-relative path: /);
});

test('rule 3: a symlink leaving the repo does not count as existing', () => {
  const root = fixture({ 'index.md': INDEX, 'src.md': '- src/escape: x\n- src/escape/*: x\n- src/inside: x\n' });
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'outside-'));
  fs.writeFileSync(path.join(outside, 'f'), '');
  fs.symlinkSync(outside, path.join(root, 'src/escape'));
  fs.symlinkSync(path.join(root, 'src/deep'), path.join(root, 'src/inside'));
  const v = check(path.join(root, 'docs/code-map'), { root });
  assert.deepEqual(messages(v), ['path does not exist: src/escape', 'path does not exist: src/escape/*']);
});

test('non-entry bullets (indented, * and +) and CRLF line endings', () => {
  const v = run({ 'index.md': INDEX, 'src.md': '  - src/A.java: x\n* src/B.java: x\n+ src/B.java: x\n' });
  assert.equal(v.length, 3);
  for (const m of messages(v)) assert.match(m, /entry must look like/);
  assert.deepEqual(run({ 'index.md': INDEX, 'src.md': '# h\r\n- src/A.java: ok\r\n' }), []);
  const long = run({ 'index.md': INDEX, 'src.md': `- src/A.java: ${'x'.repeat(160)}\r\n` });
  assert.deepEqual(long, []);
});
