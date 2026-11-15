'use strict';
/**
 * 端到端自检：注册设备 → WS 连接 → 下发通知 → 校验回执与状态流转
 * 用法： node tools/e2e-test.js [SERVER]
 * 不依赖真机，纯协议层验证。
 */
const WS = require('ws');

const SERVER = process.argv[2] || process.env.SERVER || 'http://127.0.0.1:8787';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0;
let fail = 0;
function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log('  ✓', name, extra); }
  else { fail++; console.log('  ✗', name, extra); }
}

async function api(path, opts = {}) {
  const res = await fetch(SERVER + path, opts);
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
}

async function main() {
  console.log('服务端:', SERVER, '\n');

  // 1. 注册设备
  const reg = await api('/api/v1/devices', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'e2e-' + Date.now() }),
  });
  ok('注册设备 201', reg.status === 201, JSON.stringify(reg.data).slice(0, 80));
  const { deviceId, apiKey } = reg.data;
  ok('返回 deviceId/apiKey', !!deviceId && !!apiKey);

  // 2. 密钥鉴权
  const bad = await api('/api/v1/notify', { method: 'POST', headers: { 'X-API-Key': 'cn_wrong' }, body: '{}' });
  ok('错误密钥 401', bad.status === 401);

  // 3. 未连 WS 时下发 → 应排队
  const offline = await api('/api/v1/notify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    body: JSON.stringify({ callerName: '离线测试', text: '这条应该排队' }),
  });
  ok('离线下发 202 + queued', offline.status === 202 && offline.data.status === 'queued', offline.data.status);
  const offlineId = offline.data.id;

  // 4. 中文正文往返（UTF-8）
  ok('中文入队状态可查', (await api('/api/v1/notify/' + offlineId, { headers: { 'X-API-Key': apiKey } })).data.payload.text === '这条应该排队');

  // 5. WS 连接 + 收到补发
  const ws = new WS(SERVER.replace(/^http/, 'ws') + `/ws?deviceId=${encodeURIComponent(deviceId)}&apiKey=${encodeURIComponent(apiKey)}`);
  const received = [];
  const ready = await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('WS 握手超时')), 5000);
    ws.on('message', (raw) => {
      const m = JSON.parse(raw.toString());
      received.push(m);
      if (m.type === 'ready') { clearTimeout(t); resolve(m); }
      if (m.type === 'notify') {
        const ack = (status, note) => ws.send(JSON.stringify({ type: 'ack', id: m.id, status, note }));
        setTimeout(() => ack('delivered'), 100);
        setTimeout(() => ack('ringing'), 300);
        setTimeout(() => ack('answered'), 600);
        setTimeout(() => ack('completed'), 900);
      }
    });
    ws.on('error', reject);
  });
  ok('WS 收到 ready', ready.type === 'ready' && ready.deviceId === deviceId);

  // 6. 排队通知应被补发
  await sleep(800);
  const flushed = received.find((m) => m.type === 'notify' && m.id === offlineId);
  ok('重连补发离线通知', !!flushed);
  ok('补发中文正文正确', flushed && flushed.text === '这条应该排队');

  // 7. 在线下发 → 立即推送
  const online = await api('/api/v1/notify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    body: JSON.stringify({ callerName: '张经理', callerNumber: '13800138000', text: '服务器 CPU 超过 90%', ringSeconds: 45, speakTimes: 2 }),
  });
  ok('在线下发 202 + sent', online.status === 202 && online.data.status === 'sent', online.data.status);
  await sleep(1500);

  const st = await api('/api/v1/notify/' + online.data.id, { headers: { 'X-API-Key': apiKey } });
  ok('状态流转到 completed', st.data.status === 'completed', st.data.status);
  ok('payload 字段完整', st.data.payload.speakTimes === 2 && st.data.payload.ringSeconds === 45);

  // 8. 设备在线状态
  const me = await api('/api/v1/me', { headers: { 'X-API-Key': apiKey } });
  ok('/api/v1/me 在线', me.data.online === true);

  // 9. 参数校验
  const empty = await api('/api/v1/notify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    body: JSON.stringify({ text: '' }),
  });
  ok('空内容 400', empty.status === 400);

  // 10. 越界参数被夹紧
  const clampRes = await api('/api/v1/notify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    body: JSON.stringify({ text: 'x', delaySeconds: 99999, speakTimes: 99, ringSeconds: 1 }),
  });
  const p = (await api('/api/v1/notify/' + clampRes.data.id, { headers: { 'X-API-Key': apiKey } })).data.payload;
  ok('参数越界被夹紧', p.delaySeconds === 3600 && p.speakTimes === 5 && p.ringSeconds === 10, JSON.stringify(p));

  // 11. GBK 请求体（Windows 命令行下 curl 默认发 GBK，最常见的乱码来源）
  const gbkBody = Buffer.concat([
    Buffer.from('{"callerName":"GBK","text":"', 'utf8'),
    Buffer.from([0xb2, 0xe2, 0xca, 0xd4]), // “测试”的 GBK 编码
    Buffer.from('"}', 'utf8'),
  ]);
  const gbkRes = await api('/api/v1/notify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
    body: gbkBody,
  });
  const gbkText = (await api('/api/v1/notify/' + gbkRes.data.id, { headers: { 'X-API-Key': apiKey } })).data.payload.text;
  ok('GBK 请求体自动识别', gbkText === '测试', JSON.stringify(gbkText));

  // 12. 密钥轮换：旧密钥立刻失效
  const rotated = await api('/api/v1/devices/' + deviceId + '/key', {
    method: 'POST',
    headers: { 'X-API-Key': apiKey },
  });
  ok('轮换密钥 200', rotated.status === 200 && rotated.data.apiKey !== apiKey);
  const oldKeyRes = await api('/api/v1/me', { headers: { 'X-API-Key': apiKey } });
  ok('旧密钥立即 401', oldKeyRes.status === 401);
  const newKey = rotated.data.apiKey;

  // 13. 离线队列溢出：只保留最近 20 条，被挤掉的要标成 expired 而不是永远 queued
  ws.close();
  await sleep(300);
  let firstId = null;
  for (let i = 0; i < 21; i++) {
    const r = await api('/api/v1/notify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': newKey },
      body: JSON.stringify({ text: 'queue-' + i }),
    });
    if (i === 0) firstId = r.data.id;
  }
  const firstStatus = (await api('/api/v1/notify/' + firstId, { headers: { 'X-API-Key': newKey } })).data;
  ok('队列溢出项标记 expired', firstStatus.status === 'expired', firstStatus.status + ' / ' + firstStatus.note);

  ws.close();
  console.log(`\n通过 ${pass} 项，失败 ${fail} 项`);
  process.exit(fail ? 1 : 0);
}

main().catch((e) => { console.error('测试异常:', e.message); process.exit(1); });
