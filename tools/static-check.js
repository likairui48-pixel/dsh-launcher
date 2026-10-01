#!/usr/bin/env node
/* ============================================================================
 *  静态自检（CI 门禁）
 *    1. XML 良构性
 *    2. 资源定义收集 + 引用解析（@string/@color/@drawable/@layout/@style/@dimen）
 *    3. style 继承链完整性（隐式点号父样式必须真实存在，aapt2 会因此直接报错）
 *    4. Java 里的 R.* 引用全部对得上
 *    5. Java 结构（括号 / package / 同名类）
 *    6. AndroidManifest 关键项
 *    7. assets 脚本协议
 *    8. Gradle / workflow 配置
 *  用法： node tools/static-check.js .
 * ========================================================================== */
const fs = require('fs');
const path = require('path');

const ROOT = process.argv[2] || '.';
let errors = 0, warnings = 0, checks = 0;
const err = (m) => { errors++; console.log('  ✗ ' + m); };
const warn = (m) => { warnings++; console.log('  ! ' + m); };
const ok = (m) => { checks++; console.log('  ✓ ' + m); };

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['.git', 'build', '.gradle', 'build-local', 'node_modules'].includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

const files = walk(ROOT);
const resDir = path.join(ROOT, 'app/src/main/res');
console.log(`\n扫描到 ${files.length} 个文件\n`);

/* ---------- 1. XML 良构 ---------- */
console.log('[1] XML 良构性');
function checkXml(file) {
  const src = fs.readFileSync(file, 'utf8');
  const stack = [];
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
      /* 声明 / 注释 / CDATA */
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

/* ---------- 2. 资源定义与引用 ---------- */
console.log('\n[2] 资源定义与引用');
const defined = {
  string: new Set(), color: new Set(), dimen: new Set(), bool: new Set(),
  integer: new Set(), attr: new Set(), style: new Set(), styleable: new Set(),
  drawable: new Set(), layout: new Set(), mipmap: new Set(), anim: new Set(),
};
for (const f of walk(resDir)) {
  if (!f.endsWith('.xml')) continue;
  const rel = path.relative(resDir, f);
  const dir = rel.split(path.sep)[0];
  const base = path.basename(f, '.xml');
  if (dir.startsWith('values')) {
    const src = fs.readFileSync(f, 'utf8');
    for (const m of src.matchAll(/<(string|color|dimen|bool|integer|attr)\s+name="([^"]+)"/g)) {
      if (defined[m[1]]) defined[m[1]].add(m[2]);
    }
    for (const m of src.matchAll(/<style\s+name="([^"]+)"/g)) {
      defined.style.add(m[1]);
      defined.style.add(m[1].replace(/\./g, '_'));
    }
    for (const m of src.matchAll(/<declare-styleable\s+name="([^"]+)">([\s\S]*?)<\/declare-styleable>/g)) {
      defined.styleable.add(m[1]);
      for (const a of m[2].matchAll(/<attr\s+name="([^"]+)"/g)) {
        defined.styleable.add(`${m[1]}_${a[1]}`);
      }
    }
  } else {
    const t = dir.split('-')[0];
    if (defined[t]) defined[t].add(base);
  }
}
for (const k of Object.keys(defined)) {
  const list = [...defined[k]];
  console.log(`      ${k}(${list.length}): ${list.slice(0, 8).join(', ')}${list.length > 8 ? ' …' : ''}`);
}

const refProblems = [];
const manifestPath = path.join(ROOT, 'app/src/main/AndroidManifest.xml');
for (const f of [...xmlFiles, manifestPath]) {
  if (!fs.existsSync(f)) continue;
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/@(?:android:)?(string|color|dimen|drawable|mipmap|layout|style|bool|integer|anim)\/([\w.]+)/g)) {
    if (m[0].startsWith('@android:')) continue;
    if (!defined[m[1]] || !defined[m[1]].has(m[2])) {
      refProblems.push(`${path.relative(ROOT, f)} → @${m[1]}/${m[2]}`);
    }
  }
}
if (refProblems.length) refProblems.forEach(p => err('引用不存在: ' + p));
else ok('XML / Manifest 里的资源引用全部能解析');

/* ---------- 3. style 继承链 ---------- */
console.log('\n[3] style 继承链');
const styleProblems = [];
for (const f of walk(path.join(resDir, 'values')).concat(
    fs.existsSync(path.join(resDir, 'values-night')) ? walk(path.join(resDir, 'values-night')) : [])) {
  if (!f.endsWith('.xml')) continue;
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/<style\s+name="([^"]+)"([^>]*)>/g)) {
    const name = m[1];
    const attrs = m[2];
    const pm = attrs.match(/parent="([^"]+)"/);
    if (pm) {
      const parent = pm[1];
      if (parent.startsWith('@android:') || parent.startsWith('@')) continue;
      if (!defined.style.has(parent)) styleProblems.push(`${name} 的 parent="${parent}" 不存在`);
      continue;
    }
    const dot = name.lastIndexOf('.');
    if (dot > 0) {
      const implicit = name.slice(0, dot);
      if (!defined.style.has(implicit)) {
        styleProblems.push(`${name} 隐式继承 "${implicit}"，但该样式不存在（aapt2 会直接失败）`);
      }
    }
  }
}
if (styleProblems.length) styleProblems.forEach(p => err('style 继承: ' + p));
else ok('所有 style 的显式 / 隐式父样式都存在');

/* ---------- 4. Java 里的 R.* ---------- */
console.log('\n[4] Java 中的 R.* 引用');
const mfSrc = fs.readFileSync(path.join(ROOT, 'app/src/main/AndroidManifest.xml'), 'utf8');
const layoutIds = new Set();
for (const f of walk(path.join(resDir, 'layout'))) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/android:id="@\+id\/(\w+)"/g)) layoutIds.add(m[1]);
}
const javaFiles = files.filter(f => f.endsWith('.java'));
let rRefs = 0;
for (const f of javaFiles) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/R\.(\w+)\.(\w+)/g)) {
    const type = m[1], name = m[2];
    rRefs++;
    if (type === 'id') {
      if (!layoutIds.has(name)) err(`${path.basename(f)}: R.id.${name} 在任何布局里都不存在`);
    } else if (defined[type]) {
      if (!defined[type].has(name)) err(`${path.basename(f)}: R.${type}.${name} 未定义`);
    }
  }
}
ok(`${javaFiles.length} 个 Java 文件、${rRefs} 处 R.* 引用全部有效（布局 id ${layoutIds.size} 个）`);

// 4b. findViewById 的 id 必须属于该类真正加载过的布局，否则运行期就是 NPE
const layoutIdMap = {};
for (const f of walk(path.join(resDir, 'layout'))) {
  const name = path.basename(f, '.xml');
  const src = fs.readFileSync(f, 'utf8');
  layoutIdMap[name] = new Set([...src.matchAll(/android:id="@\+id\/(\w+)"/g)].map(m => m[1]));
}
let scopeChecked = 0;
for (const f of javaFiles) {
  const src = fs.readFileSync(f, 'utf8');
  const layouts = [...new Set([...src.matchAll(/R\.layout\.(\w+)/g)].map(m => m[1]))];
  if (layouts.length === 0) continue;
  const allowed = new Set();
  for (const l of layouts) {
    for (const id of (layoutIdMap[l] || [])) allowed.add(id);
  }
  for (const m of src.matchAll(/R\.id\.(\w+)/g)) {
    scopeChecked++;
    if (!allowed.has(m[1])) {
      err(`${path.basename(f)}: R.id.${m[1]} 不在它加载的布局里（${layouts.join(', ')}）→ 运行期会 NPE`);
    }
  }
}
ok(`${scopeChecked} 处 R.id 引用的作用域全部落在对应布局内`);

/* ---------- 4c. 布局里的自定义 View 类必须真实存在 ---------- */
// aapt2 与 javac 都不校验自定义 View 的类名，写错了要到运行期 inflate 才炸
// （ClassNotFoundException -> InflateException -> 一打开就闪退）。
const javaFqns = new Map();   // 全限定类名 -> 源文件，按完整包路径比对
for (const f of javaFiles) {
  const src = fs.readFileSync(f, 'utf8');
  const pkg = (src.match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
  const cls = path.basename(f, '.java');
  if (pkg) javaFqns.set(pkg + '.' + cls, path.relative(ROOT, f));
}
const javaClasses = new Set(javaFiles.map(f => path.basename(f, '.java')));
const customViews = new Map();
for (const f of walk(path.join(resDir, 'layout'))) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/<([a-zA-Z_][\w.]*\.[A-Za-z_]\w*)\s/g)) {
    const tag = m[1];
    if (['android.view', 'android.widget', 'android.webkit', 'android.app'].some(p => tag.startsWith(p))) continue;
    if (!customViews.has(tag)) customViews.set(tag, []);
    customViews.get(tag).push(path.basename(f));
  }
}
let viewChecked = 0;
for (const [tag, where] of customViews) {
  viewChecked++;
  if (tag.startsWith('com.dsh.launcher.')) {
    if (!javaFqns.has(tag)) {
      err(`布局引用了不存在的自定义 View：<${tag}>（${where.join(', ')}）→ 运行期 InflateException 闪退`);
    }
  } else if (!/^(androidx|android|com\.google\.android|rikka)\./.test(tag)) {
    warn(`布局引用了无法本地校验的第三方 View：<${tag}>（${where.join(', ')}）`);
  }
}
ok(`${viewChecked} 种自定义 View 引用已校验（${[...customViews.keys()].join(', ')}）`);

/* ---------- 4d. Manifest 里声明的类必须真实存在 ---------- */
const classRefs = [];
for (const m of mfSrc.matchAll(/android:name="\.([A-Za-z_]\w*)"/g)) classRefs.push(m[1]);
for (const m of mfSrc.matchAll(/android:name="(com\.dsh\.launcher\.[A-Za-z_]\w*)"/g)) classRefs.push(m[1].split('.').pop());
let clsChecked = 0;
for (const cls of classRefs) {
  clsChecked++;
  const fqn = 'com.dsh.launcher.' + cls;
  if (!javaFqns.has(fqn)) {
    err(`AndroidManifest 声明的类不存在：${fqn} → 启动即崩`);
  }
}
ok(`${clsChecked} 个 Manifest 类声明（Activity / Application / Provider）全部有对应源码`);

/* ---------- 5. Java 结构 ---------- */
console.log('\n[5] Java 结构检查');
function stripJava(src) {
  let out = '', i = 0;
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
  for (const [a, b] of [['{', '}'], ['(', ')'], ['[', ']']]) {
    const na = (stripped.match(new RegExp('\\' + a, 'g')) || []).length;
    const nb = (stripped.match(new RegExp('\\' + b, 'g')) || []).length;
    if (na !== nb) err(`${path.basename(f)}: ${a}${b} 数量不等 (${na} vs ${nb})`);
  }
  if (!/package\s+com\.dsh\.launcher;/.test(src)) err(`${path.basename(f)}: package 声明不对`);
  const cls = path.basename(f, '.java');
  if (!new RegExp(`(class|interface|enum)\\s+${cls}\\b`).test(src)) {
    err(`${path.basename(f)}: 缺少同名类型声明`);
  }
}
ok(`${javaFiles.length} 个 Java 文件括号平衡、结构正常`);

/* ---------- 6. Manifest ---------- */
console.log('\n[6] AndroidManifest 关键项');
const mf = fs.readFileSync(manifestPath, 'utf8');
const musts = [
  ['RUN_COMMAND 权限声明', /<uses-permission[^>]*com\.termux\.permission\.RUN_COMMAND/],
  ['queries 里包含 com.termux', /<queries>[\s\S]*?com\.termux[\s\S]*?<\/queries>/],
  ['ShizukuProvider 已注册', /rikka\.shizuku\.ShizukuProvider/],
  ['ShizukuProvider authority 用 applicationId 变量', /authorities="\$\{applicationId\}\.shizuku"/],
  ['Application 类已声明', /android:name="\.DSHApp"/],
  ['主 Activity 已声明', /android:name="\.MainActivity"/],
  ['编辑器 Activity 已声明', /android:name="\.EditorActivity"/],
  ['使用了设计主题', /android:theme="@style\/AppTheme"/],
  ['没有 package= 属性（用 namespace）', /^(?![\s\S]*<manifest[^>]*\spackage=)[\s\S]*$/],
];
for (const [name, re] of musts) (re.test(mf) ? ok(name) : err('缺少: ' + name));

/* ---------- 7. assets 脚本 ---------- */
console.log('\n[7] 内置脚本');
const launch = path.join(ROOT, 'app/src/main/assets/dsh-launch.sh');
const fsScript = path.join(ROOT, 'app/src/main/assets/dsh-fs.sh');
if (!fs.existsSync(launch)) err('assets/dsh-launch.sh 不存在');
else {
  const sh = fs.readFileSync(launch, 'utf8');
  ['DSH_STATE=', 'DSH_URL=', 'DSH_OPENED=1', 'start-dsh.sh'].forEach(k => {
    sh.includes(k) ? ok(`dsh-launch.sh 包含 ${k}`) : err(`dsh-launch.sh 缺少 ${k}`);
  });
  const java = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dsh/launcher/LaunchPage.java'), 'utf8');
  java.includes('readAsset(act, "dsh-launch.sh")') || java.includes('"dsh-launch.sh"')
    ? ok('Java 读取的启动脚本名一致') : err('LaunchPage 没有读取 dsh-launch.sh');
}
if (!fs.existsSync(fsScript)) err('assets/dsh-fs.sh 不存在');
else {
  const sh = fs.readFileSync(fsScript, 'utf8');
  const ops = ['probe', 'list', 'read', 'write', 'mkdir', 'touch', 'rename', 'delete', 'copy', 'copyout', 'copyin', 'search'];
  const missing = ops.filter(o => !new RegExp(`^\\s{2}${o}\\)`, 'm').test(sh));
  missing.length ? err('dsh-fs.sh 缺少操作分支: ' + missing.join(', '))
                 : ok(`dsh-fs.sh 12 个操作分支齐全`);
  ['RC=0', 'B64=', 'is_protected', 'is_writable_area'].forEach(k => {
    sh.includes(k) ? ok(`dsh-fs.sh 包含 ${k}`) : err(`dsh-fs.sh 缺少 ${k}`);
  });
  const fc = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/dsh/launcher/FsClient.java'), 'utf8');
  fc.includes('"dsh-fs.sh"') ? ok('FsClient 读取的脚本名与 assets 一致') : err('FsClient 脚本名对不上');
}

/* ---------- 8. Gradle / workflow ---------- */
console.log('\n[8] 构建配置');
const gradleApp = fs.readFileSync(path.join(ROOT, 'app/build.gradle.kts'), 'utf8');
[
  [/namespace\s*=\s*"com\.dsh\.launcher"/, 'namespace'],
  [/applicationId\s*=\s*"com\.dsh\.launcher"/, 'applicationId'],
  [/compileSdk\s*=\s*34/, 'compileSdk 34'],
  [/minSdk\s*=\s*26/, 'minSdk 26'],
  [/signingConfigs\s*\{/, '固定签名配置'],
  [/storeFile\s*=\s*rootProject\.file\("tools\/debug\.keystore"\)/, '使用仓库内 debug.keystore'],
].forEach(([re, name]) => re.test(gradleApp) ? ok(`app/build.gradle.kts: ${name}`) : err(`app/build.gradle.kts 缺少: ${name}`));
fs.existsSync(path.join(ROOT, 'tools/debug.keystore'))
  ? ok('tools/debug.keystore 存在（保证每次构建签名一致，可覆盖安装）')
  : err('缺少 tools/debug.keystore');

const wf = path.join(ROOT, '.github/workflows/build.yml');
if (fs.existsSync(wf)) {
  const w = fs.readFileSync(wf, 'utf8');
  [
    [/assembleDebug/, 'assembleDebug'],
    [/actions\/checkout@v4/, 'checkout'],
    [/setup-java@v4/, 'setup-java'],
    [/gradle\/actions\/setup-gradle@v4/, 'setup-gradle'],
    [/upload-artifact@v4/, 'upload-artifact'],
    [/static-check\.js/, '静态自检步骤'],
  ].forEach(([re, name]) => re.test(w) ? ok(`workflow: ${name}`) : err(`workflow 缺少: ${name}`));
} else err('缺少 .github/workflows/build.yml');

console.log(`\n${'='.repeat(52)}`);
console.log(`检查通过 ${checks} 项 | 警告 ${warnings} | 错误 ${errors}`);
process.exit(errors ? 1 : 0);
