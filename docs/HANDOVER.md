# NotifyBridge 项目交接文档

## 1. 项目定位

NotifyBridge 是一款运行在 Android（含小米 MIUI / HyperOS）上的 **通知监听 & AI 纪要效率工具**，核心能力如下：

- 基于 **LSPosed / Xposed**，Hook 微信 WCDB 数据库的 10 个写方法，捕获被静音或电脑登录时不推送的微信消息。
- 通过 **NotificationListenerService** 捕获普通系统通知（微信、钉钉、短信、待办、紧急等）。
- 将消息按天写入本地 **Markdown 日志**（`dailynoteYYYYMMDD.md`，7 天滚动）。
- 调用 **AI 接口**生成"今日智能摘要 / 今日待办事项 / AI 智能建议 / 通知计数"。
- 将本地日志通过 **WebDAV 一致性比对**后上传到远程知识库目录。
- 使用**深色毛玻璃（Glassmorphism）**风格界面，底部 4 Tab：首页 / 日志 / AI / 我的。

> 项目远端：`https://github.com/summmer121/NotifyBridge.git`
> 当前分支：`main`

## 2. 当前版本与演进时间线

当前版本 **v12.2**（`versionCode 9`，包名 `com.notifybridge.app`）。

| 版本 | 关键变更 |
| --- | --- |
| v6 | 基础通知监听 + 本地日志 |
| v8 | 接入微信 WCDB Hook 捕获静音消息 |
| v9 | 小米后台回收防护（KeepAliveService） |
| v11 | 按天 md 日志 7 天滚动 + WebDAV 一致性上传 + AI-only 摘要 |
| v11.x | 修复 WebDAV 中文路径编码，移除额外上传子目录 |
| v12 | 移除页面顶部固定品牌栏，"AI 今日简报"改为简洁标题 |
| v12.1 | 存储管理面板列出每日 md 日志并支持预览 |
| v12.2 | 深色毛玻璃 Glassmorphism 全面重构界面 |

**判定标准（v12.x）**：界面为深色毛玻璃 4 Tab 布局，首页为信息总览 + 数据看板。

## 3. 架构与数据流

```
微信 WCDB Hook / 系统通知监听
        │
        ▼
日志记录（内存队列 + 持久化）
        │
        ▼
DailyLog 按天写入：dailynoteYYYYMMDD.md（7 天滚动删除）
        │
        ├──► AI 接口生成今日纪要（仅 AI，本地摘要已关闭）
        │
        └──► WebDAV 一致性比对上传（差异文件才上传）
```

## 4. 源码地图

源码位于 `app/src/main/java/com/notifybridge/app/`。核心模块职责：

| 文件 / 目录 | 职责 |
| --- | --- |
| `MainActivity.java` | 入口，4 Tab 导航（首页 / 日志 / AI / 我的）|
| `ui/` | 深色毛玻璃界面相关 Activity / Fragment / Adapter |
| `core/` 或 `config/` | 配置项、常量、设置存取（`Config`）|
| `service/NotifyService.java` | NotificationListenerService，捕获系统通知 |
| `xposed/` | Xposed 模块，Hook 微信 WCDB 写方法 |
| `log/`（或 `DailyLog.java`）| 按天 md 日志写入、7 天滚动、删除逻辑 |
| `ai/` | AI 接口调用，生成摘要 / 待办 / 建议 / 计数 |
| `webdav/` | WebDAV 一致性比对与上传 |
| `service/KeepAliveService.java` | 前台服务，防止小米后台回收导致广播丢失 |

> 具体文件名以仓库实际为准；本表给出职责分组的检索入口。

## 5. 构建 / 安装 / 部署

### 5.1 构建

- 构建脚本：`build-fixed.ps1`。
- 必须在 subst 虚拟盘 `X:` 下执行（脚本依赖 `X:` 路径）。
- **构建红线**：`libs/xposed-api.jar` 只能作为 javac / d8 的 classpath，**绝不能打进 `classes.dex`**，否则运行时崩溃。

```powershell
subst X: E:\临时路径\codex\NotifyBridge-fix
cd X:\NotifyBridge-fix
.\build-fixed.ps1
```

### 5.2 安装（ADB 推送）

```powershell
$adb = "E:\临时路径\codex\build-env\sdk\platform-tools\adb.exe"
& $adb -s a0ed2e41 install -r <apk文件路径>
& $adb -s a0ed2e41 logcat -s NotifyBridge:D
```

## 6. 关键配置约定

### 6.1 WebDAV

用户实际使用的目标目录（**已在代码中固定，不再拼接子目录**）：

```
http://www.summer121.top:8000/知识库/东方有线/东方RAG/10-周报记录/dailyNote/
```

- 地址含中文，**必须做百分号编码**，否则 HTTP 409。
- 上传逻辑：比对本地全部 7 条 md 与云端已有文件，**不一致才上传，一致跳过**。
- 账号 / 密码在【我的 → 同步设置】中配置，不写入源码。

### 6.2 AI 纪要

- **本地摘要功能已关闭**，只支持 AI 接口生成。
- 连接模式支持【直连 / 中转】。
- 配置项在【我的 → AI 设置】：Base URL、API Key、模型名、中转 URL、分析提示词。

### 6.3 每日 md 日志

- 命名：`dailynote + 8 位日期`，如 `dailynote20260906.md`。
- 存放：本地，7 天滚动删除。
- 存储管理（【我的 → 存储管理】）支持查看现有 md 文件、预览、刷新、清空日志。

### 6.4 微信消息格式化

- 单聊：`【个人】名字：内容`
- 群聊：`时间戳 +【群】群名 - 个人名字：发言文字`
- 图片等二进制内容：简单记录"收到图片"。
- 日志需去掉 HTML / XML / imgaskey 等格式代码，只保留文字、标点、符号。

## 7. 已知限制与注意点

- **小米 MIUI / HyperOS 后台回收**：会触发 WakePathChecker / Greezer Denial，导致 Xposed 广播丢失。**必须开启自启动 + 前台 KeepAliveService**。
- **无法直接读取 `/data/data`**（Android 沙箱 / root 限制），微信消息只能通过 WCDB Hook 获取。
- **`UploadReceiver` 当前 `exported=false`**，外部不可直接触发上传。
- **GitHub push 曾受阻**：需要有效的认证 token 才能推送到远端。
- **构建必须保 Xposed API 出 dex 红线**（见 5.1）。
- 微信消息只在开机后有广播时才记录；若手机长时间睡眠 / 被杀，可能缺失深夜记录（用户反馈早 8 点后开始有日志）。

## 8. 交接确认清单

接手时请逐项核对：

- [ ] 能在 subst `X:` 下成功 `build-fixed.ps1` 构建。
- [ ] 能在小米手机上安装并开启 LSPosed 模块作用域（微信）。
- [ ] 开启自启动 + 通知监听授权。
- [ ] 微信被静音 / 电脑登录时，消息仍能进日志。
- [ ] 按天生成 `dailynoteYYYYMMDD.md`，7 天滚动删除正常。
- [ ] WebDAV 上传到固定目录成功，中文路径无 409。
- [ ] AI 接口能生成"今日智能摘要 / 待办 / 建议 / 计数"。
- [ ] 存储管理可预览、刷新、清空每日 md。

## 9. 安全与合规提醒

- 涉及的 GitHub token、WebDAV 账号密码、AI API Key **不要写进源码或提交到仓库**。
- 项目的 Hook 能力仅限合法授权场景（如自用微信捕获被静音消息），交接时应明确使用边界。
- 日志中可能含个人信息，上传到云端前应谨慎并遵守相关隐私合规要求。

