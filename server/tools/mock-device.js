'use strict';

/**
 * 模拟被通知端（调试用，不需要真手机）
 *
 *   node tools/mock-device.js                     # 自动注册设备并等待通知
 *   node tools/mock-device.js <apiKey>            # 用已有密钥连接
 *   SERVER=http://192.168.1.10:8787 node tools/mock-device.js
 *
 * 连上后按提示在另一个终端执行：
 *   curl -X POST http://127.0.0.1:8787/api/v1/notify \
 *        -H "X-API-Key: <打印出来的密钥>" \
 *        -H "Content-Type: application/json" \
 *        -d '{"callerName":"张经理","text":"服务器 CPU 超过 90%"}'
 */

const WebSocket = require('ws');

const SERVER = process.env.SERVER || 'http://127.0.0.1:8787';
const argKey = process.argv[2];

async function api(path, opts = {}) {
  const res = await fetch(SERVER + path, opts);
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${JSON.stringify(data)}`);
  return data;
}

async function main() {
  let apiKey = argKey;
  let deviceId = '';

  if (!apiKey) {
    const dev = await api('/api/v1/devices', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '模拟设备-' + Math.random().toString(36).slice(2, 6) }),
    });
    apiKey = dev.apiKey;
    deviceId = dev.deviceId;
    console.log(`✓ 已注册模拟设备 ${deviceId}`);
    console.log(`  密钥: ${apiKey}\n`);
  } else {
    const me = await api('/api/v1/me', { headers: { 'X-API-Key': apiKey } });
    deviceId = me.deviceId;
    console.log(`· 使用已有密钥连接（${deviceId}）…\n`);
  }

  const wsUrl = SERVER.replace(/^http/, 'ws') + `/ws?deviceId=${encodeURIComponent(deviceId)}&apiKey=${encodeURIComponent(apiKey)}`;
  const ws = new WebSocket(wsUrl);

  ws.on('open', () => console.log('✓ WebSocket 已连接，等待通知…\n'));

  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === 'ready') {
      console.log(`  [ready] deviceId=${msg.deviceId}`);
      console.log('\n把上面打印的密钥填进调试控制台，或直接执行 curl：\n');
      console.log(`  curl -X POST ${SERVER}/api/v1/notify \\`);
      console.log(`    -H "X-API-Key: ${apiKey}" \\`);
      console.log(`    -H "Content-Type: application/json" \\`);
      console.log(`    -d '{"callerName":"张经理","callerNumber":"13800138000","text":"服务器 CPU 超过 90%，请立即处理"}'`);
      console.log('');
      return;
    }
    if (msg.type === 'notify') {
      console.log('\n📞 收到通知', msg.id);
      console.log(`   来电: ${msg.callerName} <${msg.callerNumber}>`);
      console.log(`   内容: ${msg.text}`);
      console.log(`   延迟: ${msg.delaySeconds}s  响铃: ${msg.ringSeconds}s  朗读: ${msg.speakTimes} 次`);
      // 模拟手机端的上报
      const ack = (status, note) => ws.send(JSON.stringify({ type: 'ack', id: msg.id, status, note }));
      setTimeout(() => { ack('delivered'); console.log('   → ack delivered'); }, 300);
      setTimeout(() => { ack('ringing'); console.log('   → ack ringing'); }, 1200);
      setTimeout(() => { ack('answered'); console.log('   → ack answered'); }, 4000);
      setTimeout(() => { ack('completed'); console.log('   → ack completed\n'); }, 6000);
      return;
    }
    console.log('  [msg]', msg);
  });

  ws.on('close', (code) => console.log(`✗ 连接关闭 (${code})`));
  ws.on('error', (e) => console.error('✗ 错误:', e.message));

  process.on('SIGINT', () => { ws.close(); process.exit(0); });
}

main().catch((e) => { console.error('启动失败:', e.message); process.exit(1); });
