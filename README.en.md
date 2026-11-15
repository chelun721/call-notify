# CallNotify

**中文** · [English](README.en.md) · [Русский](README.ru.md)

Turn one HTTP request into a **real incoming call** on your phone: native dialer UI, system ringtone, entry in the call log — and when you answer, the message is read out by TTS.

Built for situations where a push notification is too easy to ignore: failed builds, finished training runs, monitoring alerts, on-call wake-ups, cron jobs that never ran.

- **Server**: a single Go binary (with an embedded debug console); a Node.js version is included for easy hacking
- **Client**: Android (Kotlin + Compose). It registers a `CAPABILITY_CALL_PROVIDER` phone account, so the **system dialer** shows the call — no custom call UI
- **Trigger**: anything that can send HTTP; one `curl` is enough

## How it works

```
your script / CI / monitoring
      │  POST /api/v1/notify   X-API-Key: cn_xxx
      ▼
  CallNotify server  ── WebSocket ──►  Android app
      ▲                                  ├─ Telecom raises the call (system UI + ringtone)
      └──────── status acks ─────────────└─ on answer: TTS reads the message → auto hang up
```

## Layout

```
call-notify/
├── server-go/            Go server (recommended: single binary, zero deps)
│   ├── main.go           HTTP API, storage, rate limiting, retention policy
│   ├── ws.go             WebSocket: handshake, heartbeat, offline replay
│   ├── diag.go           self-check endpoint /api/v1/diag
│   ├── public/index.html debug console (embedded into the binary)
│   └── go.mod
├── server/               Node.js server (easier to modify)
│   ├── server.js
│   └── tools/{notify.js, mock-device.js, e2e-test.js}
├── android/              Android client (Kotlin + Compose)
│   └── app/src/main/java/com/callnotify/app/
│       ├── telecom/      phone account, connection, incoming call, audio route
│       ├── voice/        TTS playback
│       ├── service/      foreground service + WebSocket + watchdog
│       ├── net/          protocol and reconnect logic
│       ├── keepalive/    alarm / JobScheduler based keep-alive
│       ├── autoconfig/   accessibility-driven auto-granting
│       ├── core/         prefs, device API, first-run setup
│       └── ui/           screens
├── tools/                helper scripts (Windows)
└── build-apk.ps1         one-command APK build
```

## Quick start

### 1. Run the server

```bash
# Windows
callnotify-server-windows-amd64.exe -port 8787
# Linux
chmod +x callnotify-server-linux-amd64 && ./callnotify-server-linux-amd64 -port 8787
```

It prints the reachable addresses and serves a **debug console** in the browser — no code needed to send a test notification.

Node version: `cd server && npm install && node server.js`

> Both implementations speak the **same protocol** and use the same `data.json` layout, so they are interchangeable.

### 2. Build / install the app

```bash
cd android
./gradlew assembleRelease       # app-release.apk (or -unsigned.apk without a keystore)
./gradlew assembleDebug         # app-debug.apk, package id gets a .debug suffix
```

On Windows: `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`
(it mirrors the project to an ASCII path, because the Kotlin compiler rejects non-ASCII paths.)

### 3. Configure the phone

Open the app → enter `192.168.1.10:8787` → tap **Register device** → copy the **API key**.

The "Readiness" card has four rows; tap the button on whichever is not green:

| Item | Why |
| --- | --- |
| Notification permission | foreground service notification |
| Ignore battery optimizations | otherwise the process gets frozen when the screen is off |
| Phone account enabled | required for the incoming call to appear |
| Autostart / background | needed on many Chinese OEM ROMs |

### 4. Send a notification

```bash
curl -X POST http://127.0.0.1:8787/api/v1/notify \
  -H "Content-Type: application/json" \
  -H "X-API-Key: cn_your_key" \
  -d '{"callerName":"Build bot","callerNumber":"13800138000","text":"Build failed, check the log","speakTimes":2}'
```

## Server options

Flags and environment variables are equivalent:

| Flag | Env | Default | Meaning |
| --- | --- | --- | --- |
| `-port` | `PORT` | `8787` | listen port |
| `-host` | `HOST` | `0.0.0.0` | listen address |
| `-data` | `DATA_FILE` | `data.json` next to the binary | device storage |
| `-offline-retention` | `OFFLINE_RETENTION` | `0` (never delete) | delete a device after being offline this long. **>0 makes stale devices get 401 and re-register with a new key** |
| `-queue-retention` | `QUEUE_RETENTION` | `10m` | how long undelivered notifications are kept for replay |
| `-notify-retention` | `NOTIFY_RETENTION` | `30m` | how long finished notifications stay queryable |
| `-queue-max` | `QUEUE_MAX` | `20` | offline queue size per device |

## HTTP API

Auth: `X-API-Key: cn_xxx` or `Authorization: Bearer cn_xxx`.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/notify` | **main endpoint**, key is enough |
| `GET` | `/api/v1/notify/{id}` | notification status |
| `GET` | `/api/v1/me` | device info for the current key |
| `POST` | `/api/v1/devices` | register a device, returns `deviceId` + `apiKey` |
| `GET` | `/api/v1/devices/{deviceId}` | device details |
| `POST` | `/api/v1/devices/{deviceId}/key` | rotate the key |
| `POST` | `/api/v1/devices/{deviceId}/notify` | send by device id (compat) |
| `GET` | `/health` | health check |
| `GET` | `/api/v1/diag` | devices, last 20 notifications, hints |

### Request body

| Field | Type | Required | Default | Range |
| --- | --- | --- | --- | --- |
| `text` | string | no\* | `""` | ≤2000 chars, read by TTS |
| `callerName` | string | no\* | `Unknown` | ≤40 chars |
| `callerNumber` | string | no | `10086` | ≤32 chars |
| `delaySeconds` | int | no | `0` | 0–3600 |
| `ringSeconds` | int | no | `60` | 10–300 |
| `speakTimes` | int | no | `1` | 1–5 |

\* at least one of `text` / `callerName`, otherwise `400`.

### Status flow

```
queued → sent → delivered → ringing → answered → completed
                          ├→ rejected / missed
                          ├→ failed (note explains why)
                          └→ expired (queue timeout / evicted)
```

### Errors

`202` accepted · `400` bad json · `401` bad key · `404` not found · `429` rate limited (30/min/device).

Request bodies may be UTF-8; if the bytes are not valid UTF-8 they are decoded as **GBK** (Windows curl default).

## Client settings

| Setting | Default | Notes |
| --- | --- | --- |
| Server address | empty | saved after first registration; changing it triggers re-registration |
| Speak times | 1 | 1–5 |
| Ring timeout | 45s | 10–300s |
| Speaker on answer | on | routing is delegated to Telecom, matching the system call UI toggle |
| Auto hang up | on | otherwise hang up manually |
| Aggressive keep-alive | on | `setAlarmClock` heartbeat (shows an alarm icon in the status bar) |

### Zero-touch first run

- **Accessibility auto-config**: clicks "Allow" on system dialogs and flips the phone-account / autostart switches;
- `tools/setup-adb.ps1`: one command over USB grants permissions, whitelists battery and enables the accessibility service;
- **Deep link import**: `callnotify://setup?server=...&deviceId=...&key=...` for bulk provisioning.

## Keep-alive and reconnect

There is no "never dies" on Android, only "comes back fast":

| Layer | Interval | Purpose |
| --- | --- | --- |
| Foreground service (`specialUse`) | resident | survives the 6-hour FGS limits |
| App-level heartbeat | 10s send / 20s timeout | detects half-dead sockets |
| Reconnect backoff | from 0.3s, cap 15s | fast recovery on network flaps |
| `onTaskRemoved` | 0.3 / 1.5 / 5s | restart after being swiped away |
| AlarmClock heartbeat | 5 min | permission-free, survives Doze |
| persisted Job | 15 min | still there after process kill and reboot |

Logs distinguish `connection lost` (network), `connection stalled` (heartbeat timeout) and `process frozen` (ROM freezer).

## Compatibility

| Case | Notes |
| --- | --- |
| Phone account entry | AOSP: Settings → Calls → Calling accounts. OnePlus/OPPO hide it; the app tries `com.android.server.telecom/.settings.EnableAccountPreferenceActivity` and otherwise offers a copyable adb command |
| Caller name | MIUI etc. resolve the name from contacts and ignore `setCallerDisplayName`; use a number that exists in contacts |
| TTS engine | Android 11+ needs `<queries>` for `TTS_SERVICE` (already declared) |
| iOS | No equivalent API. CallKit + VoIP push is the only path (paid developer account + push certificate) |

## Development

```bash
cd server-go && go build ./...                # Go 1.23+
cd server && npm install && node server.js    # Node 16+
cd android && ./gradlew assembleRelease
cd server && node tools/e2e-test.js http://127.0.0.1:8787   # 18 protocol assertions
```

`versionCode` increments on every change, `versionName` follows semver; artifacts are named `CallNotify-<versionName>-<buildType>.apk`.

## Security

- Deploy behind HTTPS; the app upgrades `https://` to `wss://` automatically
- `X-API-Key` is a password: never commit it, never post it
- The server never persists message bodies; `data.json` holds device names and keys only

## Known limitations

- Depends on the Telecom phone-account mechanism; when a ROM refuses `addNewIncomingCall` the app can only report why
- Chinese OEM background freezers need manual whitelisting — no code can bypass that
- No deduplication: calling twice rings twice
- Single-process in-memory state: notification history is lost on restart (device keys persist)

## Intended use

For notifying **yourself or your team**. Do not impersonate people or organizations, do not use it for harassment, debt collection or fraud. You are responsible for how you use it.

## Credits

Inspired by [Phony](https://github.com/DDOneApps/Phony) (GPL-3.0) for the Telecom integration approach. No code from Phony is used in this project.

## License

GPL-3.0 — see [LICENSE](LICENSE). Copyright (C) 2026 chlrun.
