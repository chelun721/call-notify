'use strict';

/**
 * 远程来电通知 · 通信服务端
 * ------------------------------------------------------------
 * 职责：只做两端之间的通信中转，不保存通知正文以外的业务数据。
 *
 *   通知端 (任意程序 / 脚本 / 服务器)
 *        │  POST /api/v1/notify                （X-API-Key 鉴权）
 *        │  POST /api/v1/devices/{id}/notify   （指定设备，兼容旧写法）
 *        ▼
 *   [ 本服务 ] ──WebSocket 长连接──► 被通知端 (Android App)
 *                                      └─ 模拟系统来电 + TTS 朗读
 *
 * 启动： node server.js            （默认端口 8787）
 *       PORT=9000 node server.js
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

// ---------------------------------------------------------------- 配置

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_FILE = path.join(__dirname, 'data.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

const MAX_QUEUE_PER_DEVICE = 20;         // 离线期间最多缓存多少条通知
const NOTIFY_TTL_MS = 30 * 60 * 1000;    // 通知状态保留时长
const RATE_LIMIT_PER_MIN = 30;           // 单设备每分钟最多下发条数
const DEVICE_CREATE_PER_HOUR = 10;       // 单 IP 每小时最多注册设备数

// ---------------------------------------------------------------- 存储

let store = { devices: {}, notifications: {} };

/** apiKey -> device，避免每次请求都对全部设备做线性扫描 */
const deviceByKey = new Map();

function reindexDevices() {
  deviceByKey.clear();
  for (const dev of Object.values(store.devices)) deviceByKey.set(dev.apiKey, dev);
}

function loadStore() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    store.devices = parsed.devices || {};
  } catch (e) {
    if (e.code !== 'ENOENT') console.warn('[store] 读取 data.json 失败，使用空数据：', e.message);
  }
  // 进程重启后所有连接都不存在了
  for (const d of Object.values(store.devices)) d.online = false;
  reindexDevices();
}

let saveTimer = null;
function saveStore() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      // 只落盘设备密钥；通知正文仅在内存中保留 30 分钟，不写入磁盘
      fs.writeFileSync(DATA_FILE, JSON.stringify({ devices: store.devices }, null, 2));
    } catch (e) {
      console.error('[store] 写入 data.json 失败：', e.message);
    }
  }, 200);
}

function cleanupNotifications() {
  const now = Date.now();
  for (const [id, n] of Object.entries(store.notifications)) {
    if (now - n.createdAt > NOTIFY_TTL_MS) delete store.notifications[id];
  }
}
setInterval(cleanupNotifications, 5 * 60 * 1000).unref();

// ---------------------------------------------------------------- 工具

const newId = (prefix) => prefix + crypto.randomBytes(8).toString('hex');
const newKey = () => 'cn_' + crypto.randomBytes(24).toString('hex');
const nowISO = () => new Date().toISOString();

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

function send(res, code, obj) {
  // 204/304 不能带响应体
  if (code === 204 || code === 304) {
    res.writeHead(code, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type, X-API-Key, Authorization',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    });
    return res.end();
  }
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-API-Key, Authorization',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  res.end(body);
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      const raw = decodeBody(Buffer.concat(chunks)).trim();
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error('invalid json'));
      }
    });
    req.on('error', reject);
  });
}

/**
 * 解码请求体。优先按 UTF-8 解析；失败则回退 GBK。
 * 原因：Windows 下用 cmd/PowerShell/curl 直接拼中文时，实际发出的是 GBK 字节，
 * 不兼容的话中文会整段变成乱码，而这是中文用户最常见的踩坑点。
 */
function decodeBody(buf) {
  if (!buf.length) return '';
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf);
  } catch {
    try {
      return new TextDecoder('gbk').decode(buf);
    } catch {
      return buf.toString('utf8');
    }
  }
}

function apiKeyOf(req) {
  const h = req.headers['x-api-key'];
  if (h) return String(h).trim();
  const auth = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(auth);
  return m ? m[1].trim() : '';
}

function authDevice(req, deviceId) {
  const dev = store.devices[deviceId];
  if (!dev) return { error: [404, '设备不存在'] };
  const key = apiKeyOf(req);
  if (!key || key !== dev.apiKey) return { error: [401, '密钥不正确'] };
  return { dev };
}

function publicDevice(dev) {
  return {
    deviceId: dev.id,
    name: dev.name,
    online: !!dev.online,
    lastSeenAt: dev.lastSeenAt || null,
    createdAt: dev.createdAt,
  };
}

// ---------------------------------------------------------------- 推送

/** deviceId -> WebSocket */
const sockets = new Map();
/** deviceId -> [通知id] 离线队列 */
const offlineQueue = new Map();
/** deviceId -> [时间戳] 限流 */
const rateBuckets = new Map();

function rateLimited(deviceId) {
  const now = Date.now();
  const arr = (rateBuckets.get(deviceId) || []).filter((t) => now - t < 60_000);
  arr.push(now);
  rateBuckets.set(deviceId, arr);
  return arr.length > RATE_LIMIT_PER_MIN;
}

/** 注册接口本身不敏感（只能拿到一个空通道），这里只做频率限制防刷 */
const createBuckets = new Map();
function deviceCreateLimited(ip) {
  const now = Date.now();
  const arr = (createBuckets.get(ip) || []).filter((t) => now - t < 3600_000);
  arr.push(now);
  createBuckets.set(ip, arr);
  return arr.length > DEVICE_CREATE_PER_HOUR;
}

function setStatus(notification, status, note) {
  notification.status = status;
  notification.updatedAt = nowISO();
  if (note) notification.note = note;
  saveStore();
}

/**
 * 下发一条通知。在线 → 立即推送；离线 → 入队，等设备重连后补发。
 * @returns {{ notification: object, delivered: boolean }}
 */
function dispatch(deviceId, payload) {
  const notification = {
    id: newId('ntf_'),
    deviceId,
    status: 'created',
    payload,
    createdAt: Date.now(),
    updatedAt: nowISO(),
  };
  store.notifications[notification.id] = notification;

  const ws = sockets.get(deviceId);
  if (ws && ws.readyState === 1) {
    ws.send(JSON.stringify({ type: 'notify', ...payload, id: notification.id }));
    setStatus(notification, 'sent');
    return { notification, delivered: true };
  }

  const q = offlineQueue.get(deviceId) || [];
  q.push(notification.id);
  while (q.length > MAX_QUEUE_PER_DEVICE) {
    const dropped = q.shift();
    const dn = store.notifications[dropped];
    // 被挤出队列的通知以前会永远停在 queued，通知端查状态会一直等到超时
    if (dn && dn.status === 'queued') {
      dn.status = 'expired';
      dn.updatedAt = nowISO();
      dn.note = '设备离线太久，队列已满，本条被丢弃';
    }
  }
  offlineQueue.set(deviceId, q);
  setStatus(notification, 'queued', '设备离线，已排队等待重连');
  return { notification, delivered: false };
}

function flushQueue(deviceId, ws) {
  const ids = offlineQueue.get(deviceId) || [];
  if (!ids.length) return;
  offlineQueue.delete(deviceId);
  for (const id of ids) {
    const n = store.notifications[id];
    if (!n || n.status !== 'queued') continue;
    // 太旧的通知不再补发，避免设备一上线就炸出一堆来电
    if (Date.now() - n.createdAt > 10 * 60 * 1000) {
      setStatus(n, 'expired', '排队超时未送达');
      continue;
    }
    ws.send(JSON.stringify({ type: 'notify', ...n.payload, id: n.id }));
    setStatus(n, 'sent', '离线补发');
  }
  log(`[queue] 向 ${deviceId} 补发 ${ids.length} 条离线通知`);
}

// ---------------------------------------------------------------- HTTP

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function serveStatic(req, res, pathname) {
  let rel = pathname === '/' ? '/index.html' : pathname;
  const filePath = path.join(PUBLIC_DIR, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
  if (!filePath.startsWith(PUBLIC_DIR)) return send(res, 403, { error: 'forbidden' });
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('404 Not Found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  if (req.method === 'OPTIONS') return send(res, 204, {});

  // ---- 健康检查
  if (pathname === '/health' || pathname === '/api/health') {
    return send(res, 200, {
      ok: true,
      devices: Object.keys(store.devices).length,
      online: [...sockets.keys()].length,
      uptimeSec: Math.round(process.uptime()),
    });
  }

  // ---- 创建设备（被通知端 App 首次启动时调用）
  if (pathname === '/api/v1/devices' && req.method === 'POST') {
    const ip = req.socket.remoteAddress || 'unknown';
    if (deviceCreateLimited(ip)) {
      return send(res, 429, { error: '注册过于频繁，请稍后再试' });
    }
    let body;
    try {
      body = await readBody(req);
    } catch (e) {
      return send(res, 400, { error: e.message });
    }
    const dev = {
      id: newId('dev_'),
      name: String(body.name || '我的手机').slice(0, 40),
      apiKey: newKey(),
      createdAt: nowISO(),
      lastSeenAt: null,
      online: false,
    };
    store.devices[dev.id] = dev;
    deviceByKey.set(dev.apiKey, dev);
    saveStore();
    log(`[device] 新设备 ${dev.id} (${dev.name})`);
    return send(res, 201, { ...publicDevice(dev), apiKey: dev.apiKey });
  }

  // ---- 用密钥自查设备信息
  if (pathname === '/api/v1/me' && req.method === 'GET') {
    const key = apiKeyOf(req);
    const dev = deviceByKey.get(key);
    if (!dev) return send(res, 401, { error: '密钥不正确' });
    return send(res, 200, publicDevice(dev));
  }

  // ---- 设备详情
  let m = /^\/api\/v1\/devices\/([^/]+)$/.exec(pathname);
  if (m && req.method === 'GET') {
    const { dev, error } = authDevice(req, m[1]);
    if (error) return send(res, error[0], { error: error[1] });
    return send(res, 200, publicDevice(dev));
  }

  // ---- 轮换密钥
  m = /^\/api\/v1\/devices\/([^/]+)\/key$/.exec(pathname);
  if (m && req.method === 'POST') {
    const { dev, error } = authDevice(req, m[1]);
    if (error) return send(res, error[0], { error: error[1] });
    dev.apiKey = newKey();
    reindexDevices();
    saveStore();
    const ws = sockets.get(dev.id);
    if (ws) ws.close(4001, 'key rotated');
    log(`[device] ${dev.id} 密钥已轮换`);
    return send(res, 200, { deviceId: dev.id, apiKey: dev.apiKey });
  }

  // ---- 核心：下发一条来电通知（推荐：只需密钥，不需要 deviceId）
  if (pathname === '/api/v1/notify' && req.method === 'POST') {
    const key = apiKeyOf(req);
    const dev = deviceByKey.get(key);
    if (!dev) return send(res, 401, { error: '密钥不正确' });
    return handleNotify(req, res, dev);
  }

  // ---- 兼容：按 deviceId 下发
  m = /^\/api\/v1\/devices\/([^/]+)\/notify$/.exec(pathname);
  if (m && req.method === 'POST') {
    const { dev, error } = authDevice(req, m[1]);
    if (error) return send(res, error[0], { error: error[1] });
    return handleNotify(req, res, dev);
  }

  // ---- 查询通知状态（按密钥，无需 deviceId）
  m = /^\/api\/v1\/notify\/([^/]+)$/.exec(pathname);
  if (m && req.method === 'GET') {
    const key = apiKeyOf(req);
    const dev = deviceByKey.get(key);
    if (!dev) return send(res, 401, { error: '密钥不正确' });
    const n = store.notifications[m[1]];
    if (!n || n.deviceId !== dev.id) return send(res, 404, { error: '通知不存在' });
    return send(res, 200, {
      id: n.id,
      status: n.status,
      note: n.note || null,
      createdAt: new Date(n.createdAt).toISOString(),
      updatedAt: n.updatedAt,
      payload: n.payload,
    });
  }

  // ---- 查询通知状态
  m = /^\/api\/v1\/devices\/([^/]+)\/notifications\/([^/]+)$/.exec(pathname);
  if (m && req.method === 'GET') {
    const { error } = authDevice(req, m[1]);
    if (error) return send(res, error[0], { error: error[1] });
    const n = store.notifications[m[2]];
    if (!n || n.deviceId !== m[1]) return send(res, 404, { error: '通知不存在' });
    return send(res, 200, {
      id: n.id,
      status: n.status,
      note: n.note || null,
      createdAt: new Date(n.createdAt).toISOString(),
      updatedAt: n.updatedAt,
      payload: n.payload,
    });
  }

  // ---- 静态资源（调试控制台）
  if (req.method === 'GET') return serveStatic(req, res, pathname);

  return send(res, 404, { error: 'not found' });
});

function clampInt(v, min, max, def) {
  const n = Number(v);
  if (!Number.isFinite(n)) return def;
  return Math.min(max, Math.max(min, Math.round(n)));
}

/** 解析并下发一条通知（两条路由共用） */
async function handleNotify(req, res, dev) {
  let body;
  try {
    body = await readBody(req);
  } catch (e) {
    return send(res, 400, { error: e.message });
  }

  const callerName = String(body.callerName || body.name || '未知来电').slice(0, 40);
  const callerNumber = String(body.callerNumber || body.number || '10086').slice(0, 32);
  const text = String(body.text || body.content || '').slice(0, 2000);
  if (!text && !body.callerName) {
    return send(res, 400, { error: '至少需要 callerName 或 text' });
  }
  if (rateLimited(dev.id)) {
    return send(res, 429, { error: `超过频率限制（${RATE_LIMIT_PER_MIN} 次/分钟）` });
  }

  const payload = {
    callerName,
    callerNumber,
    text,
    delaySeconds: clampInt(body.delaySeconds, 0, 3600, 0),
    ringSeconds: clampInt(body.ringSeconds, 10, 300, 60),
    speakTimes: clampInt(body.speakTimes, 1, 5, 1),
    priority: body.priority === 'low' ? 'low' : 'high',
    receivedAt: nowISO(),
  };

  const { notification, delivered } = dispatch(dev.id, payload);
  log(`[notify] → ${dev.id} ${callerName}${text ? ' | ' + text.slice(0, 30) : ''} (${notification.status})`);
  return send(res, 202, {
    id: notification.id,
    status: notification.status,
    deviceOnline: delivered,
    deviceId: dev.id,
  });
}

// ---------------------------------------------------------------- WebSocket

const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

server.on('upgrade', (req, socket, head) => {
  let url;
  try {
    url = new URL(req.url, 'http://localhost');
  } catch {
    return socket.destroy();
  }
  if (url.pathname !== '/ws') return socket.destroy();

  const deviceId = url.searchParams.get('deviceId') || '';
  const apiKey = url.searchParams.get('apiKey') || '';
  const dev = store.devices[deviceId];
  if (!dev || dev.apiKey !== apiKey) {
    log(`[ws] 拒绝连接 deviceId=${deviceId}`);
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return socket.destroy();
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.deviceId = deviceId;
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  const deviceId = ws.deviceId;
  const dev = store.devices[deviceId];

  // 同一设备重复连接：踢掉旧连接
  const old = sockets.get(deviceId);
  if (old && old !== ws) {
    try { old.close(4000, 'replaced by new connection'); } catch {}
  }
  sockets.set(deviceId, ws);
  dev.online = true;
  dev.lastSeenAt = nowISO();
  saveStore();
  log(`[ws] ${deviceId} 已连接（在线 ${sockets.size}）`);

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.send(JSON.stringify({
    type: 'ready',
    deviceId,
    serverTime: nowISO(),
    deviceName: dev.name,
  }));

  flushQueue(deviceId, ws);

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (msg.type === 'ping') {
      return ws.send(JSON.stringify({ type: 'pong', t: Date.now() }));
    }
    if (msg.type === 'ack' || msg.type === 'status') {
      const n = store.notifications[msg.id];
      if (n && n.deviceId === deviceId) {
        const allowed = ['delivered', 'ringing', 'answered', 'completed', 'rejected', 'missed', 'failed', 'ignored'];
        if (allowed.includes(msg.status)) {
          setStatus(n, msg.status, msg.note);
          log(`[ack] ${deviceId} ${msg.id} → ${msg.status}${msg.note ? ' (' + msg.note + ')' : ''}`);
        }
      }
      dev.lastSeenAt = nowISO();
      return;
    }
    if (msg.type === 'hello') {
      dev.lastSeenAt = nowISO();
      saveStore();
    }
  });

  ws.on('close', () => {
    if (sockets.get(deviceId) === ws) {
      sockets.delete(deviceId);
      dev.online = false;
      dev.lastSeenAt = nowISO();
      saveStore();
      log(`[ws] ${deviceId} 已断开（在线 ${sockets.size}）`);
    }
  });

  ws.on('error', (e) => log(`[ws] ${deviceId} 错误: ${e.message}`));
});

// 心跳：30 秒没有 pong 就断开，让客户端重连
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      try { ws.terminate(); } catch {}
      continue;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, 30_000);
wss.on('close', () => clearInterval(heartbeat));

// ---------------------------------------------------------------- 启动

loadStore();
server.listen(PORT, HOST, () => {
  const lan = Object.values(require('os').networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);

  console.log('');
  console.log('  远程来电通知 · 服务端已启动');
  console.log('  ─────────────────────────────────────────');
  console.log(`  本机访问   http://127.0.0.1:${PORT}`);
  lan.forEach((ip) => console.log(`  局域网访问 http://${ip}:${PORT}`));
  console.log(`  WebSocket  ws://<上面任一地址>:${PORT}/ws`);
  console.log(`  已注册设备 ${Object.keys(store.devices).length} 台`);
  console.log('');
});

process.on('SIGINT', () => {
  console.log('\n正在关闭…');
  for (const ws of wss.clients) { try { ws.close(1001, 'server shutdown'); } catch {} }
  try { fs.writeFileSync(DATA_FILE, JSON.stringify({ devices: store.devices }, null, 2)); } catch {}
  process.exit(0);
});
