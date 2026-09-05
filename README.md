# NotifyBridge

一个基于 **LSPosed / Xposed** 的 Android 通知桥模块：Hook 第三方 App 的**数据写入层**，捕获并转发通知/消息（含被静音、不弹通知的会话）。

> ⚠️ **免责声明**：本模块仅用于个人/合规场景下的通知聚合与自动化。请遵守当地法律、目标 App 服务条款及隐私政策。本项目不用于规避任何安全机制。

## 特性

- 纯 Java 实现，无 Gradle 依赖（直接 aapt2 + javac + d8 + apksigner 构建）
- Xposed 模块（`assets/xposed_init` → `com.notifybridge.app.NotifyHooker`）
- 底部 4-Tab 界面（🏠 首页 / 📋 日志 / 🤖 AI / 👤 我的）
- 通知捕获 → 本地日志分页 / 统计 / AI 分析 / WebDAV 上传
- **微信（com.tencent.mm）WCDB 写入层 Hook**：覆盖 10 个 SQLiteDatabase 写方法签名（见下方说明）

## 微信 WCDB Hook 说明（重要）

微信 8.x 的消息写库**不走标准 `android.database.sqlite.SQLiteDatabase`**，而是用自研 **WCDB**（`com.tencent.wcdb.database.SQLiteDatabase`）。本模块通过反射确认目标版本（如 8.0.77）的真实写方法签名并逐一 Hook，覆盖 10 个方法：

```
execSQL(String)
execSQL(String, Object[])
execSQL(String, Object[], CancellationSignal)
insert(String, String, ContentValues)
insertOrThrow(String, String, ContentValues)
insertWithOnConflict(String, String, ContentValues, int)
replace(String, String, ContentValues)
replaceOrThrow(String, String, ContentValues)
update(String, ContentValues, String, String[])
updateWithOnConflict(String, ContentValues, String, String[], int)
```

> 调试经验：给定标签的写方法 `probe = 0` 不代表 Hook 点错误 —— 已用反射 SIG 自检证明签名匹配。若 hook 全部挂载但零捕获，说明目标版本可能走更底层的 `SQLiteStatement`/编译语句路径，需继续向下切点。

## 版本演进 & 当前状态

通知桥经过多轮迭代，完整版本时间线：

| 版本 | 阶段 | 关键内容 | 结果 |
|------|------|----------|------|
| v6 及以前 | 数据层 | 标准 `SQLiteDatabase` Hook + 通知捕获链路 | 可用，但微信 8.x 捕获不到 |
| **v7** | UI | 底部 4-Tab 界面精美化 | ✅ 完成 |
| **v8** | WCDB | 切到 `com.tencent.wcdb.database.SQLiteDatabase`；确认注入通过（32× [NotifyX][INIT]）+ hook 挂上，0 捕获 | 确认 WCDB 是正路，但签名/覆盖不足 |
| **v9** | 探针诊断 | `wcdbProbe` 探针（按表名去重）；定案设备微信 = **8.0.77 build 3160**；probe=0 = 测试窗口没真写库 | 定位"probe=0 ≠ hook 错" |
| **v10** | 签名自检 | 反射 SIG 自检，**一次定案 8.0.77 的 10 个 WCDB 写方法真实签名**；补 insertOrThrow/replace 宽覆盖 | 证明之前 hook 签名全部匹配 |
| **v11** | 全覆盖 | 补 replaceOrThrow/updateWithOnConflict/execSQL，**全覆盖 10 写方法** + execSQL 携带 SQL 探针（[NotifyX][SQL] 表名::完整SQL） | 最新日志确认 10 方法全挂载 + SIG 对齐 |
| **v12（待定）** | 底层切点 | 10 方法全挂载仍 probe=0 → 怀疑微信 8.0.77 写消息不走 ContentValues/execSQL 高层，而走底层 `SQLiteStatement` | **方向已锁定，待实机确认** |

**当前判定标准（v11 及以后）**：成功签名 = `[NotifyX][SQL]` 或 probe 任一出现（证明微信真的写了库、能拿到表名/字段）。两者都 0 → 换更底层 `SQLiteStatement` 切点。静音/免打扰会话同样落库，因此本项目走**数据层 Hook** 是捕获静音会话的唯一路径（通知层抓不到静音会话）。

## 构建

依赖 Android SDK（platform 35 + build-tools 35.0.0）。参考 `build_fixed.sh.example`（已脱敏）：

```bash
# 1. 准备固定签名 keystore（保证覆盖安装时签名一致，否则通知使用权授权会失效）
keytool -genkeypair -v -keystore /path/to/your/notifybridge.jks \
    -alias notifybridge -keyalg RSA -keysize 2048 -validity 10000
# 2. 编辑 build_fixed.sh.example 填入 KS 路径与 KS_PASS
# 3. 构建
bash build_fixed.sh.example
```

产物输出到 `build/NotifyBridge.apk`（v1/v2/v3 签名）。dist 目录含已构建的参考 APK。

## 产物

`dist/` 下放置发布版 APK（带递增版本序号，如 `NotifyBridge-v11-…`）。

## 版权与安全

- 本仓库已做**脱敏**：不含任何真实服务器地址、账号、密钥、token 或签名 keystore 密码。
- 你自己的构建脚本/keystore 请通过 `.gitignore` 排除，切勿提交。
