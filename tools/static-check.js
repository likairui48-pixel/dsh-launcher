#!/usr/bin/env node
/* 静态自检：XML 良构 / 资源引用 / R.id / Java 括号 / YAML 基本结构 */
const fs = require('fs');
const path = require('path');

const ROOT = process.argv[2] || '.';
let errors = 0, warnings = 0, checks = 0;
const err = (m) => { errors++;  console.log('  ✗ ' + m); };
const warn = (m) => { warnings++; console.log('  ! ' + m); };
const ok = (m) => { checks++; console.log('  ✓ ' + m); };

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '.git' || e.name === 'build' || e.name === '.gradle') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

const files = walk(ROOT);
console.log(`\n扫描到 ${files.length} 个文件\n`);

/* ---------- 1. XML 良构 ---------- */
console.log('[1] XML 良构性');
const VOID_OK = new Set();
function checkXml(file) {
  const src = fs.readFileSync(file, 'utf8');
  const stack = [];
  // 先整体抓出“原始标签文本”，再自行判断是否自闭合（避免属性里的 / 被误判）
  const re = /<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[[\s\S]*?\]\]>|<\/?[A-Za-z_][^>]*>/g;
  let m, last = 0, line = 1;
  while ((m = re.exec(src)) !== null) {
    line += (src.slice(last, m.index).match(/\n/g) || []).length;
    last = m.index;
    const t = m[0];
    if (t.startsWith('</')) {
      const name = t.match(/^<\/\s*([\w.:-]+)/)[1];
      const top = stack.pop();
      if (top !== name) err(`${file}:${line} 闭合不匹配 </${name}>，期望 </${top}>`);
    } else if (t.startsWith('<?') || t.startsWith('<!--') || t.startsWith('<![')) {
      // 声明 / 注释 / CDATA，跳过
    } else {
      const name = t.match(/^<\s*([\w.:-]+)/)[1];
      if (!/\/\s*>$/.test(t)) stack.push(name);
    }
  }
  if (stack.length) err(`${file} 有未闭合标签: ${stack.join(', ')}`);
  return src;
}
const xmlFiles = files.filter(f => f.endsWith('.xml'));
for (const f of xmlFiles) checkXml(f);
ok(`${xmlFiles.length} 个 XML 文件标签闭合正常`);

/* ---------- 2. 资源引用 ---------- */
console.log('\n[2] 资源引用完整性');
const resDir = path.join(ROOT, 'app/src/main/res');
const defined = { string: new Set(), color: new Set(), drawable: new Set(), mipmap: new Set(), layout: new Set(), style: new Set() };
const typeOfDir = (d) => d.startsWith('values') ? 'values' : d.split('-')[0];
for (const f of walk(resDir)) {
  if (!f.endsWith('.xml')) continue;
  const rel = path.relative(resDir, f);
  const dir = rel.split(path.sep)[0];
  const t = typeOfDir(dir);
  const base = path.basename(f, '.xml');
  if (t === 'values') {
    const src = fs.readFileSync(f, 'utf8');
    for (const m of src.matchAll(/<(string|color|style|dimen|bool|integer)\s+name="([^"]+)"/g)) {
      if (defined[m[1]]) defined[m[1]].add(m[2]);
    }
  } else if (defined[t]) {
    defined[t].add(base);
  }
}
for (const k of Object.keys(defined)) {
  console.log(`      ${k}: ${[...defined[k]].join(', ') || '(无)'}`);
}
const refProblems = [];
for (const f of [...xmlFiles, path.join(ROOT, 'app/src/main/AndroidManifest.xml')]) {
  if (!fs.existsSync(f)) continue;
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/@(string|color|drawable|mipmap|layout|style)\/([\w.]+)/g)) {
    if (!defined[m[1]] || !defined[m[1]].has(m[2])) {
      refProblems.push(`${path.relative(ROOT, f)} → @${m[1]}/${m[2]}`);
    }
  }
}
if (refProblems.length) refProblems.forEach(p => err('引用不存在: ' + p));
else ok('所有 @string/@color/@drawable/@mipmap/@layout 引用都能解析');

/* ---------- 3. Java 里的 R.* 引用 ---------- */
console.log('\n[3] Java 中的 R.* 引用');
const layoutXml = fs.readFileSync(path.join(resDir, 'layout/activity_main.xml'), 'utf8');
const layoutIds = new Set([...layoutXml.matchAll(/android:id="@\+id\/(\w+)"/g)].map(m => m[1]));
const javaFiles = files.filter(f => f.endsWith('.java'));
for (const f of javaFiles) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/R\.id\.(\w+)/g)) {
    if (!layoutIds.has(m[1])) err(`${path.basename(f)}: R.id.${m[1]} 在布局里不存在`);
  }
  for (const m of src.matchAll(/R\.layout\.(\w+)/g)) {
    if (!fs.existsSync(path.join(resDir, 'layout', m[1] + '.xml'))) err(`${path.basename(f)}: R.layout.${m[1]} 不存在`);
  }
}
ok(`布局 id: ${[...layoutIds].join(', ')} —— Java 引用全部对得上`);

/* ---------- 4. Java 括号平衡 + 常见笔误 ---------- */
console.log('\n[4] Java 结构检查');
// 单遍状态机剥离注释与字面量（顺序很重要：先处理字符串，否则 URL 里的 // 会吃掉整行）
function stripJava(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i], d = src[i + 1];
    if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '/' && d === '*') { i += 2; while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue; }
    if (c === '"' || c === "'") {
      const q = c; i++;
      while (i < n && src[i] !== q) { if (src[i] === '\\') i++; i++; }
      i++; out += q + q; continue;
    }
    out += c; i++;
  }
  return out;
}

for (const f of javaFiles) {
  const src = fs.readFileSync(f, 'utf8');
  const stripped = stripJava(src);
  const pairs = [['{', '}'], ['(', ')'], ['[', ']']];
  for (const [a, b] of pairs) {
    const na = (stripped.match(new RegExp('\\' + a, 'g')) || []).length;
    const nb = (stripped.match(new RegExp('\\' + b, 'g')) || []).length;
    if (na !== nb) err(`${path.basename(f)}: ${a}${b} 数量不等 (${na} vs ${nb})`);
  }
  if (!/package\s+com\.dsh\.launcher;/.test(src)) err(`${path.basename(f)}: package 声明不对`);
  const cls = path.basename(f, '.java');
  if (!new RegExp(`(class|interface)\\s+${cls}\\b`).test(src)) err(`${path.basename(f)}: 缺少同名 public 类`);
  ok(`${path.basename(f)} 括号平衡、结构正常`);
}

/* ---------- 5. AndroidManifest 关键点 ---------- */
console.log('\n[5] AndroidManifest 关键项');
const mf = fs.readFileSync(path.join(ROOT, 'app/src/main/AndroidManifest.xml'), 'utf8');
const musts = [
  ['com.termux.permission.RUN_COMMAND 声明', /<uses-permission[^>]*com\.termux\.permission\.RUN_COMMAND/],
  ['queries 里包含 com.termux', /<queries>[\s\S]*?com\.termux[\s\S]*?<\/queries>/],
  ['ShizukuProvider 已注册', /rikka\.shizuku\.ShizukuProvider/],
  ['ShizukuProvider authority 用 applicationId 变量', /authorities="\$\{applicationId\}\.shizuku"/],
  ['主 Activity 已声明', /android:name="\.MainActivity"/],
  ['没有 package= 属性（用 namespace）', /^(?![\s\S]*<manifest[^>]*\spackage=)[\s\S]*$/],
];
for (const [name, re] of musts) (re.test(mf) ? ok(name) : err('缺少: ' + name));

/* ---------- 6. assets 脚本 ---------- */
console.log('\n[6] 内置脚本');
const assetPath = path.join(ROOT, 'app/src/main/assets/dsh-launch.sh');
if (!fs.existsSync(assetPath)) err('assets/dsh-launch.sh 不存在');
else {
  const sh = fs.readFileSync(assetPath, 'utf8');
  ['DSH_STATE=', 'DSH_URL=', 'DSH_OPENED=1', 'start-dsh.sh'].forEach(k => {
    sh.includes(k) ? ok(`脚本包含 ${k}`) : err(`脚本缺少 ${k}`);
  });
  if (!/^#!/.test(sh)) warn('脚本没有 shebang（我们用 sh -c 执行，不强制，但建议保留）');
  const java = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dsh/launcher/MainActivity.java'), 'utf8');
  if (!java.includes('readAsset("dsh-launch.sh")')) err('Java 里没有 readAsset("dsh-launch.sh")，名字对不上');
  else ok('Java 读取的 asset 名与文件名一致');
}

/* ---------- 7. Gradle / workflow ---------- */
console.log('\n[7] 构建配置');
const gradleApp = fs.readFileSync(path.join(ROOT, 'app/build.gradle.kts'), 'utf8');
[/namespace\s*=\s*"com\.dsh\.launcher"/, /applicationId\s*=\s*"com\.dsh\.launcher"/, /compileSdk\s*=\s*34/, /minSdk\s*=\s*26/]
  .forEach((re, i) => re.test(gradleApp) ? ok(`app/build.gradle.kts 配置项 ${i + 1} 正确`) : err(`app/build.gradle.kts 缺少配置项 ${i + 1}`));
const wf = path.join(ROOT, '.github/workflows/build.yml');
if (fs.existsSync(wf)) {
  const w = fs.readFileSync(wf, 'utf8');
  [/assembleDebug/, /actions\/checkout@v4/, /setup-java@v4/, /gradle\/actions\/setup-gradle@v4/, /upload-artifact@v4/]
    .forEach((re, i) => re.test(w) ? ok(`workflow 步骤 ${i + 1} 存在`) : err(`workflow 缺少步骤 ${i + 1}`));
  if (/^\s*-\s+name:/m.test(w)) ok('workflow YAML 结构看起来正常');
} else err('缺少 .github/workflows/build.yml');

console.log(`\n${'='.repeat(50)}`);
console.log(`检查通过 ${checks} 项 | 警告 ${warnings} | 错误 ${errors}`);
process.exit(errors ? 1 : 0);
