# 来电通知 · CallNotify

**中文** · [English](README.en.md) · [Русский](README.ru.md)

把一条 HTTP 请求变成手机上的一通**系统来电**：原生来电界面、系统铃声、进系统通话记录，接听后用 TTS 把内容念出来。

适合「光靠推送容易被忽略」的场景：构建失败、训练结束、监控告警、值班叫醒、定时任务没按时跑。

- **服务端**：Go 单文件可执行程序（自带控制台页面），也有 Node.js 版本便于改代码
- **客户端**：Android（Kotlin + Compose），通过 `TelecomManager` 注册通话账户，由系统拨号器接管来电界面
- **通知端**：任意能发 HTTP 的语言，一条 `curl` 即可

## 它为什么能被"接听"

App 注册一个 `CAPABILITY_CALL_PROVIDER` 的通话账户，再用 `addNewIncomingCall()` 请求来电。系统 Telecom 框架把它当真实来电处理：**系统电话应用展示界面、播铃声、写通话记录**。App 自己不画任何来电界面。

```
你的脚本 / 业务系统
      │  POST /api/v1/notify   X-API-Key: cn_xxx
      ▼
  CallNotify 服务端  ── WebSocket 长连接 ──►  Android App
      ▲                                        ├─ Telecom 发起来电（系统界面 + 铃声）
      └──────── 状态回执 ack ───────────────────└─ 接听后 TTS 朗读 → 自动挂断
```

## 目录结构

```
call-notify/
├── server-go/            服务端（Go，推荐：单文件可执行、零依赖）
│   ├── main.go           HTTP API、状态存储、限流、保留策略
│   ├── ws.go             WebSocket：握手、心跳、离线补发
│   ├── diag.go           自检面板 /api/v1/diag
│   ├── public/index.html 调试控制台（编译进二进制）
│   ├── console_windows.go / console_other.go  Windows 控制台 UTF-8
│   └── go.mod
├── server/               服务端（Node.js 版，便于快速改逻辑）
│   ├── server.js
│   ├── public/index.html
│   └── tools/{notify.js, mock-device.js, e2e-test.js}
├── android/              客户端（Kotlin + Compose）
│   └── app/src/main/java/com/callnotify/app/
│       ├── telecom/      通话账户、连接、来电、音频路由
│       ├── voice/        TTS 朗读
│       ├── service/      前台服务 + WebSocket 长连接 + 看门狗
│       ├── net/          协议与重连
│       ├── keepalive/    闹钟 / JobScheduler 保活
│       ├── autoconfig/   无障碍自动点授权
│       ├── core/         配置、设备 API、首次配置引导
│       └── ui/           界面
├── tools/                辅助脚本（Windows）
│   ├── notify.ps1        命令行下发
│   ├── api-check.ps1     在线 API 自检（5 项）
│   ├── setup-adb.ps1     USB 零点击装机
└── build-apk.ps1         一键编译 APK
```

## 快速开始

### 1. 启动服务端

**方式 A：Go 版（推荐）**

```bash
# Windows
callnotify-server-windows-amd64.exe -port 8787
# Linux
chmod +x callnotify-server-linux-amd64 && ./callnotify-server-linux-amd64 -port 8787
```

启动后终端会打印可用地址，浏览器打开就是**调试控制台**，可以不用写代码直接发通知。

**方式 B：Node 版**

```bash
cd server && npm install && node server.js
```

> 两个版本**协议完全一致**，`data.json` 结构也一致，可以互相替换。

### 2. 编译 / 安装 App

`dist/` 里若有现成 APK 可直接安装；否则自行编译：

```bash
cd android
./gradlew assembleRelease
# 有签名配置时：android/app/build/outputs/apk/release/app-release.apk
# 没有签名配置时：outputs/apk/release/app-release-unsigned.apk（需自行 apksigner 签名）
# 调试包：./gradlew assembleDebug → app-debug.apk（包名带 .debug 后缀，可与正式包共存）
```

签名配置是可选的：在 `android/keystore.properties` 写入 `storeFile/storePassword/keyAlias/keyPassword`，
并把 jks 放到 `android/keystore/`。**该文件与 jks 已在 `.gitignore` 中，不会被提交。**

Windows 上一键脚本：`powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`
（会自动把工程映射到英文路径，规避 Kotlin 编译器对非 ASCII 路径的限制。）

### 3. 手机端配置

打开 App → 填服务器地址（`192.168.1.10:8787`，不用写 `http://`）→ 点「注册设备」→ 得到 **API Key**。

首页「就绪检查」需要四项变绿，缺哪项点哪项的按钮：

| 项 | 为什么需要 |
| --- | --- |
| 通知权限 | 前台服务通知 |
| 忽略电池优化 | 否则息屏会被系统冻结 |
| 通话账户已启用 | 来电能否弹出的关键 |
| 自启动 / 后台运行 | 国产 ROM 需要手动放行 |

### 4. 下发通知

```bash
curl -X POST http://127.0.0.1:8787/api/v1/notify \
  -H "Content-Type: application/json" \
  -H "X-API-Key: cn_你的密钥" \
  -d '{"callerName":"张经理","callerNumber":"13800138000","text":"构建失败，请查看日志","speakTimes":2}'
```

或用自带的命令行工具：

```powershell
.\tools\notify.ps1 -Server http://192.168.1.10:8787 -Key cn_你的密钥 -Name 张经理 -Text "构建失败" -Wait
```

## 服务端参数

命令行参数与环境变量等价（**环境变量优先用于容器部署**）：

| 参数 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `-port` | `PORT` | `8787` | 监听端口 |
| `-host` | `HOST` | `0.0.0.0` | 监听地址 |
| `-data` | `DATA_FILE` | 同目录 `data.json` | 设备密钥存储路径 |
| `-offline-retention` | `OFFLINE_RETENTION` | `0`（永不删除） | 设备离线多久后删除身份证录。**>0 会让超期设备重连时被 401，从而换新密钥** |
| `-queue-retention` | `QUEUE_RETENTION` | `10m` | 未送达通知保留多久，手机重连后补发 |
| `-notify-retention` | `NOTIFY_RETENTION` | `30m` | 终态通知保留多久（供查状态） |
| `-queue-max` | `QUEUE_MAX` | `20` | 单设备离线队列上限 |

```bash
# 例：清理 30 天没连过的设备，其余保留
./callnotify-server-linux-amd64 -offline-retention 720h -queue-retention 30m
```

## HTTP API

鉴权：`X-API-Key: cn_xxx`，也支持 `Authorization: Bearer cn_xxx`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/notify` | **主接口**，下发一条来电（只需密钥） |
| `GET` | `/api/v1/notify/{id}` | 查这条通知的状态 |
| `GET` | `/api/v1/me` | 用密钥自查设备信息 |
| `POST` | `/api/v1/devices` | 注册新设备，返回 `deviceId` + `apiKey` |
| `GET` | `/api/v1/devices/{deviceId}` | 设备详情 |
| `POST` | `/api/v1/devices/{deviceId}/key` | 轮换密钥（旧 key 立即失效） |
| `POST` | `/api/v1/devices/{deviceId}/notify` | 按设备下发（兼容写法） |
| `GET` | `/health` | 健康检查：设备数、在线数、运行时长 |
| `GET` | `/api/v1/diag` | 自检面板：设备在线状态 + 最近 20 条通知 + 诊断建议 |

### 下发字段

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `text` | string | 否\* | `""` | ≤2000 字，接听后朗读 |
| `callerName` | string | 否\* | `通知来电` | ≤40 字，来电显示名 |
| `callerNumber` | string | 否 | `18888888888` | ≤32 字 |
| `delaySeconds` | int | 否 | `0` | 0–3600，延迟响铃 |
| `ringSeconds` | int | 否 | `60` | 10–300，无人接听自动挂断 |
| `speakTimes` | int | 否 | `1` | 1–5，朗读次数 |

\* `text` 与 `callerName` 至少一个，否则 `400`。超出范围的数字会被夹紧到边界。

响应 `202`：

```json
{"id":"ntf_5c1d87d329c6165c","status":"sent","deviceOnline":true,"deviceId":"dev_06fe9cda"}
```

`deviceOnline:false` 表示手机当前离线，通知已入队（默认最多 20 条、10 分钟内有效），重连后补发。

### 状态流转

```
queued（离线排队）→ sent（已推送）→ delivered（手机收到）→ ringing（响铃）
  → answered（已接听）→ completed（播放完成）
  ├→ rejected（拒接） ├→ missed（无人接听/未接即挂）
  └→ failed（失败，note 带原因）   └→ expired（排队超时或队列被挤掉）
```

### 错误码

| 码 | 含义 |
| --- | --- |
| `202` | 已受理 |
| `400` | JSON 非法 / 必填字段缺失 |
| `401` | 密钥不正确 |
| `404` | 通知或设备不存在 |
| `429` | 超过频率限制（默认 30 次/分钟/设备） |

> 请求体支持 UTF-8；不是合法 UTF-8 时按 **GBK** 再解一次（Windows 命令行 curl 默认发 GBK）。

## 客户端设置项

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 服务器地址 | 空 | 首次填写后保存；换地址会重新注册 |
| 朗读次数 | 1 | 1–5 |
| 无人接听挂断 | 45s | 10–300s |
| 接听走扬声器 | 开 | 关掉则用听筒（路由完全交给 Telecom，与系统通话界面的免提开关一致） |
| 朗读完自动挂断 | 开 | 关掉需手动挂断 |
| 激进保活 | 开 | 用 `setAlarmClock` 做心跳（状态栏会显示闹钟图标），纯手机环境下最可靠 |

### 首次配置自动化

- 无障碍服务「自动配置」：自动点系统权限弹窗的「允许」、打开通话账户与自启动开关；
- `tools/setup-adb.ps1`：USB 连接后一条命令完成权限、电池白名单、无障碍写入（**零点击**）；
- 深链接导入：`callnotify://setup?server=...&deviceId=...&key=...`，适合批量分发。

## 保活与重连

安卓没有"永不掉线"，只有"掉了尽快回来"：

| 层 | 间隔 | 作用 |
| --- | --- | --- |
| 前台服务（`specialUse`） | 常驻 | 系统认可的长驻载体，避免 6 小时上限 |
| 应用层心跳 | 10s 发 / 20s 判死 | 识别"连着但收不到数据"的假死连接 |
| 退避重连 | 0.3s 起，上限 15s | 抖动与切网时快速恢复 |
| `onTaskRemoved` | 0.3 / 1.5 / 5s 三次 | 从最近任务划掉后自动拉起 |
| AlarmClock 心跳 | 5 分钟 | 免权限、Doze 也能唤醒 |
| persisted Job | 15 分钟 | 进程被杀、手机重启后仍然存在 |

日志里能区分三种断线：`连接断开`（网络）、`连接假死`（心跳超时）、`进程被系统冻结`（ROM 冻结，定时器停摆）。

## 兼容性

| 场景 | 说明 |
| --- | --- |
| 通话账户入口 | AOSP/类原生：`设置 → 通话 → 通话账户`；MIUI 直接可见；**一加/OPPO 有隐藏入口**，App 会自动尝试 `com.android.server.telecom/.settings.EnableAccountPreferenceActivity`，失败时提供可复制的 adb 命令 |
| 来电显示名 | MIUI 等按号码查通讯录，会忽略 `setCallerDisplayName`。想显示名字就把号码设成通讯录里已有的联系人 |
| 语音引擎 | Android 11+ 需在 manifest 声明 `<queries>` 才能看见 TTS 引擎，已处理 |
| iOS | 无等价 API。唯一路径是 CallKit + VoIP Push（需付费开发者账号与推送证书） |

## 开发

```bash
# 服务端（Go 1.23+）
cd server-go && go build ./...

# 服务端（Node 16+）
cd server && npm install && node server.js

# 客户端
cd android && ./gradlew assembleRelease

# 协议自检（18 项断言，不需要真机）
cd server && node tools/e2e-test.js http://127.0.0.1:8787
```

版本号规则：`versionCode` 每次 +1，`versionName` 语义化递增；APK 产物按 `CallNotify-<versionName>-<buildType>.apk` 命名。

## 安全建议

- 公网部署请套 HTTPS：App 里填 `https://` 会自动走 `wss://`；
- `X-API-Key` 等同于密码，别提交到仓库、别发群；
- 换密钥走 `POST /api/v1/devices/{deviceId}/key`（App 内也有入口，需二次确认）；
- 服务端不落盘通知正文，只在内存保留一段时间；`data.json` 只有设备名和密钥。

## 已知限制

- 依赖系统通话账户机制，`addNewIncomingCall` 被 ROM 拒绝时只能提示原因；
- 国产 ROM 的后台冻结需要用户手动加白，代码无法绕过；
- 无去重/幂等，重复调用会产生多次来电；
- 默认单进程内存状态，重启后通知历史丢失（设备密钥保留）。

## 使用边界

本项目用于**给自己或团队发提醒**。请勿用于冒充他人、骚扰、催收或诈骗——冒充公检法、客服等身份属于违法行为，责任由使用者承担。

## 致谢

Telecom 集成思路参考 [Phony](https://github.com/DDOneApps/Phony)（GPL-3.0），本项目未使用其代码。

## License

GPL-3.0，见 [LICENSE](LICENSE)。Copyright (C) 2026 chlrun。
