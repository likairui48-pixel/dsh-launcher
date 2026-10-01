# DSH 启动器

一个极简的 Android App，用来一键拉起 Termux 里的 DeepSeek Harness 并直接进入网页。

## 它解决什么问题

DeepSeek Harness 的 Web UI 默认需要带 `token` 参数访问。而那个 token **每次 DSH 进程重启都会变**，
只能从 Termux 的日志里读到。所以手工流程永远是：

1. 打开 Termux
2. 跑启动脚本
3. 从一堆日志里 grep 出 token
4. 拼成 URL 丢进浏览器

这个 App 把 1~4 全部合并成一次点击。

## 三个功能

| # | 功能 | 实现方式 |
|---|------|----------|
| 1 | 拉起 Termux 的 `.sh` 脚本 | Termux `RUN_COMMAND` intent（`app-shell` 运行器，后台执行不弹终端） |
| 2 | 检查 Shizuku 是否在运行 | Shizuku 官方客户端 SDK 的 `Shizuku.pingBinder()` |
| 3 | 直接打开浏览器 | 脚本把带 token 的 URL 打到 stdout，App 通过 `PendingIntent` 收回来，再交给浏览器 |

## 工作流程

```
[点击按钮]
   │
   ├─ 检查 Termux 是否安装
   ├─ 检查/申请 com.termux.permission.RUN_COMMAND
   │
   ├─ startService → RUN_COMMAND
   │      command : /data/data/com.termux/files/usr/bin/sh
   │      args    : -c "<内置脚本内容>"
   │      workdir : /data/data/com.termux/files/home
   │      runner  : app-shell
   │      + PendingIntent 用于接收结果
   │
   ▼
[Termux 执行脚本]
   │  1. 探测 http://127.0.0.1:3080/ 是否活着
   │  2. 活着 → 用；没活着 → 调 ~/start-dsh.sh 冷启动
   │  3. 从 ~/dsh-web.log 里 grep 出最新的 token
   │  4. stdout 输出 DSH_URL=http://127.0.0.1:3080/?token=xxxx
   │
   ▼
[App 收到广播]
   │  Bundle result = intent.getBundleExtra("result")
   │  stdout = result.getString("stdout")
   │  正则提取 DSH_URL=…
   │
   ▼
[ACTION_VIEW 打开浏览器]  ← 你永远看不到 token
```

## 前置条件

1. **Termux** 已安装
2. Termux 里开启外部应用调用：
   ```bash
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```
3. Termux 里已有 `~/start-dsh.sh`（DSH 的启动脚本）
4. （可选）装了 Shizuku 就能看到它的运行状态；没装也不影响使用

## 构建

仓库自带 GitHub Actions 工作流，push 到 `main` 就会：

- `gradle assembleDebug` 编译
- 把 APK 传到 workflow artifact
- 同时创建一个 GitHub Release，APK 挂在 Release 里可以直接下载（无需登录）

也可以本地构建：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/dsh-launcher-debug.apk
```

## 安装

1. 在手机浏览器打开 Release 页面，下载 `dsh-launcher-debug.apk`
2. 点击安装（首次需要允许「安装未知应用」）
3. 打开 App，点「启动 DSH 并打开网页」
4. 第一次会弹权限框，点「允许」

## 代码结构

```
app/src/main/
├── AndroidManifest.xml              # 权限 + ShizukuProvider
├── assets/dsh-launch.sh             # 内置 shell 脚本（App 读出来丢给 Termux 执行）
├── java/com/dsh/launcher/
│   └── MainActivity.java            # 全部逻辑，单 Activity
└── res/
    ├── layout/activity_main.xml     # 一屏三块：Shizuku 状态 / 大按钮 / 日志
    ├── values/strings.xml
    └── mipmap-anydpi-v26/           # 自适应图标
```

## 技术备注

- **为什么用 `PendingIntent` 而不是共享文件拿结果**：Android 的分区存储不允许普通 App 直接写
  Termux 私有目录，而 Termux 的 `RUN_COMMAND` 原生支持把 `stdout/stderr/exitCode` 通过
  `PendingIntent` 回传，不需要任何文件交换。
- **为什么 `PendingIntent` 必须是 `FLAG_MUTABLE`**：Termux 是用
  `pendingIntent.send(context, code, fillInIntent)` 把结果塞进 extras 的，
  `FLAG_IMMUTABLE` 会导致这些 extras 被丢弃。
- **`minSdk 26`**：自适应图标（`mipmap-anydpi-v26`）从这个版本开始支持，可以不带任何 PNG 资源。
- **核心超时 45 秒**：超时未拿到回传就退回打开 `http://127.0.0.1:3080/`，不会卡死。
