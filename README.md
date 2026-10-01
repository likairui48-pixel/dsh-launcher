# DSH 启动器

把 Termux 里的 **DeepSeek Harness** 一键拉起并在手机上管理工作区文件的 Android App。

- 启动：一次点击完成「拉起 Termux → 拿带 token 的地址 → 打开网页」
- 文件：在 App 里直接浏览 / 查看 / 编辑 / 重命名 / 删除工作区文件，也能从手机导入文件
- 打通：任何位置都能「用其他文件管理器打开」（MT管理器 / 系统「文件」/ 质感文件…）

---

## 它解决什么问题

DeepSeek Harness 的 Web UI 需要带 `token` 访问，而 token **每次 DSH 进程重启都会变**，
只能从 Termux 日志里 grep。手工流程是：打开 Termux → 跑脚本 → 翻日志找 token → 拼 URL → 丢进浏览器。

另一个痛点是：工作区文件在 `/data/data/com.termux/files/home/...`，这是 **Termux 的私有目录**，
别的 App（包括文件管理器）按 Android 的沙箱规则根本读不到，改个文件只能回到终端敲命令。

这个 App 把这两件事一起解决了。

## 功能

| # | 功能 | 实现方式 |
|---|------|----------|
| 1 | 拉起 Termux 启动脚本 | Termux `RUN_COMMAND` intent（`app-shell` 运行器，后台执行不弹终端） |
| 2 | 检测 Shizuku 运行状态 | Shizuku 官方客户端 SDK 的 `Shizuku.pingBinder()` |
| 3 | 直接打开带 token 的网页 | 脚本把 URL 打到 stdout，App 用 `PendingIntent` 收回来交给浏览器 |
| 4 | 工作区文件管理 | App 把操作打包成 base64 payload，交给 Termux 里的 `dsh-fs.sh` 执行，结果走 stdout 回传 |
| 5 | 文本内容查看 / 编辑 | 分块读（每次 192KB）→ 编辑 → 分块回写 |
| 6 | 从手机导入文件 | 系统文件选择器（SAF）→ App 读字节 → 分块写进 Termux |
| 7 | 用其他文件管理器打开所在位置 | 共享存储路径用 `content://` 文档 URI 直接定位；Termux 私有路径先镜像到共享存储再打开 |

## 工作流程

```
[点击启动]
   │
   ├─ 检查 Termux 是否安装 / 是否有 RUN_COMMAND 权限
   ├─ startService → RUN_COMMAND
   │      command : /data/data/com.termux/files/usr/bin/sh
   │      args    : -c "<内置脚本>"  （脚本首行会 echo 请求号，用于结果归属判定）
   │      workdir : /data/data/com.termux/files/home
   │      runner  : app-shell
   │
   ▼
[Termux 执行脚本]  →  stdout 输出 DSH_STATE= / DSH_URL= / DSH_LAN_URL=
   ▼
[App 收到广播] → 解析 URL → ACTION_VIEW 交给浏览器
```

文件管理走的是同一条通道，换一个脚本：

```
[App 侧]  操作 + 路径  →  base64 payload
   ▼  RUN_COMMAND（串行队列，避免并发串台）
[Termux]  dsh-fs.sh 解码 payload → 执行 → 结果 base64 单行回传
   ▼
[App 侧]  解析 → 渲染列表 / 编辑器
```

### 为什么 Termux 私有目录必须「镜像」才能给别人打开

`/data/data/com.termux/files/home` 属于 Termux 的 UID。普通 App 既没有权限、SELinux 也不放行，
所以「用 MT管理器打开这个目录」在无 root 的情况下物理上做不到。App 的做法是：

1. 用 `cp -R` 把目标镜像到 `/sdcard/Download/DSH-Workspace/<工作区相对路径>`（Termux 有存储权限，能写）
2. 用 `content://com.android.externalstorage.documents/document/primary%3ADownload%2F...` 打开镜像位置
3. 需要的话可以反向「从共享存储同步回来」，在 MT管理器里改完再同步进工作区

共享存储里的路径就是另一种情况了——直接定位，不做任何拷贝。

## 界面

Material 风格的自绘设计系统（**零第三方 UI 依赖**，只有 Shizuku SDK 一个依赖）：

- 底部三页：启动 / 文件 / 设置
- 深色模式跟随系统（`values-night` 整套设计令牌）
- 图标全部用 `GlyphView` 以 Canvas 几何图元现画，不引入任何图标库
- 卡片、胶囊标签、底部动作面板、悬浮按钮都是形状 drawable + 自绘

## 构建

### GitHub Actions（推荐）

push 到 `main` 就会编译、跑静态自检、上传 artifact，并创建一个 Release（APK 可直接下载）。

### 本机 Termux 直编（不需要 Gradle / Android Studio）

仓库自带 `tools/build-local.sh`，用 `aapt2 + javac + d8 + apksigner` 直接出包：

```bash
pkg install openjdk-17 aapt2 d8 apksigner    # 一次性
bash tools/build-local.sh                    # 产物：build-local/dsh-launcher-2.0.apk
```

需要的两个 jar 放在 `~/android-toolchain/sdk/`：

| 文件 | 来源 |
|------|------|
| `android.jar` | Android SDK Platform 34（如 `platform-34-ext7_r03.zip`） |
| `shizuku-api.jar` | `dev.rikka.shizuku:api:13.1.5` 的 aar 里的 `classes.jar` |

### 本机推送（github.com:443 不可达时）

本机实测 `github.com:443` 直连超时、`api.github.com` 正常；而 `~/.gitconfig` 里往往有
`url.*.insteadOf` 把 github.com 重写到第三方镜像，**git 直推会把 token 交给镜像方**。

`tools/push-via-api.py` 绕开 git remote，只用 Git Data API 上传 blob / 建 tree / 建 commit / 更新 ref：

```bash
printf '%s' '<你的 PAT>' > ~/.dsh-gh-token && chmod 600 ~/.dsh-gh-token
python3 tools/push-via-api.py
```

它会照抄本地的 author / committer / date / message，因此**远端 commit 与本地 SHA 完全一致**，历史不会分叉。
（日期要写成 UTC：GitHub 会把带偏移的时间归一化成 UTC，用 `+0000` 才能对上哈希。）

## 自检工具

```bash
node tools/static-check.js .      # 资源引用 / style 继承链 / R.* / manifest / 脚本协议（CI 也会跑）
bash tools/run-logic-test.sh      # 纯逻辑单测：路径换算、体积格式化、导出目标计算（44 项）
bash tools/build-local.sh         # 真编译，任何一个资源或 Java 错误都会在这里暴露
```

`static-check.js` 里专门检查 **style 的隐式点号继承** —— `Dsh.Btn.Primary` 会隐式继承 `Dsh.Btn`，
如果父样式不存在，aapt2 会直接报一堆 `resource style/xxx not found`，这个检查能提前 5 分钟定位。

## 签名说明（重要）

`tools/debug.keystore` 是仓库内固化的调试密钥，**CI 和本机构建共用同一把**，
这样每次构建的签名一致，可以直接覆盖安装。

> 如果你之前装的是旧版本（CI 每次现生成随机 debug 密钥签的），第一次需要先卸载旧版再装新版；
> 之后所有更新都能直接覆盖安装。

## 前置条件

1. **Termux** 已安装，并开启外部应用调用：
   ```bash
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```
2. Termux 里已有 `~/start-dsh.sh`
3. `termux-setup-storage` 执行过（导出 / 镜像功能需要写共享存储）
4. （可选）装了 Shizuku 就能看到它的运行状态；没装不影响使用

## 代码结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── dsh-launch.sh                 # 启动 DSH + 回传 URL
│   └── dsh-fs.sh                     # 文件桥：probe/list/read/write/mkdir/touch/
│                                     #   rename/delete/copy/copyout/copyin/search
├── java/com/dsh/launcher/
│   ├── DSHApp.java                   # Application：注册结果广播、放开 file:// 限制
│   ├── TermuxBridge.java             # RUN_COMMAND 客户端：串行队列 / 请求号 / 超时
│   ├── FsClient.java                 # 文件操作 -> payload -> 回传解析
│   ├── FsEntry.java                  # 文件条目模型 + 图标映射
│   ├── MainActivity.java             # 三页容器 + 自绘底部导航
│   ├── LaunchPage.java               # 启动页
│   ├── FilesPage.java                # 文件页
│   ├── SettingsPage.java             # 设置页
│   ├── EditorActivity.java           # 文本查看 / 编辑
│   ├── FileActions.java / OpenWith.java  # 外部文件管理器 / 镜像桥
│   ├── GlyphView.java                # 自绘线稿图标
│   ├── Sheet.java / Busy.java / Util.java / Prefs.java
└── res/                              # values(-night) / drawable / layout / mipmap
```

## 技术备注

- **PendingIntent 必须 `FLAG_MUTABLE`**：Termux 用 `pendingIntent.send(context, code, fillInIntent)`
  塞结果，`FLAG_IMMUTABLE` 会让 extras 被丢掉。
- **结果归属不赌框架行为**：每个请求的编号会由脚本 `echo` 到 stdout 第一行，
  App 只校验第一行，避免文件内容里恰好出现同样的字样造成误判。
- **串行执行**：`PendingIntent` 靠 requestCode 区分，并发请求结果会串台，所以桥层用一个队列排队。
- **单次传输上限 192KB**：Binder 事务总量约 1MB，分块传输把大文件读写切成多次往返。
- **`minSdk 26`**：自适应图标从这版开始支持，可以不带任何 PNG 资源。
- **零 AndroidX**：全部用 framework 控件 + 自绘，APK 只有 120KB，也避开了依赖版本地狱。
