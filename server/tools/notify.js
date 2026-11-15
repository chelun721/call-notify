#!/usr/bin/env node
'use strict';

/**
 * 命令行下发一条来电通知（适合脚本、CI、构建完成后提醒）
 *
 *   node tools/notify.js --key cn_xxx --name 张经理 --text "构建完成"
 *   node tools/notify.js --key cn_xxx --text "磁盘快满了" --delay 10
 *
 * 参数：
 *   --key     设备密钥（必填）
 *   --name    来电显示名称，默认「系统通知」
 *   --number  来电号码，默认 10086
 *   --text    接听后朗读的内容
 *   --delay   延迟多少秒后响铃，默认 0
 *   --ring    无人接听挂断秒数，默认 60
 *   --times   朗读次数，默认 1
 *
 * 环境变量 SERVER 可指定服务端地址，默认 http://127.0.0.1:8787
 */

const SERVER = process.env.SERVER || 'http://127.0.0.1:8787';

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      out[key] = next;
      i++;
    } else {
      out[key] = 'true';
    }
  }
  return out;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!args.key) {
    console.error('缺少 --key，用法示例：');
    console.error('  node tools/notify.js --key cn_xxx --name 张经理 --text "服务器 CPU 超过 90%"');
    process.exit(1);
  }

  const body = {
    callerName: args.name || '系统通知',
    callerNumber: args.number || '10086',
    text: args.text || '',
    delaySeconds: Number(args.delay || 0),
    ringSeconds: Number(args.ring || 60),
    speakTimes: Number(args.times || 1),
  };

  try {
    const res = await fetch(`${SERVER}/api/v1/notify`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-API-Key': args.key,
      },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) {
      console.error('发送失败：', data.error || res.status);
      process.exit(1);
    }
    console.log(`已下发 ${data.id}`);
    console.log(`状态：${data.status}${data.deviceOnline ? '（设备在线）' : '（设备离线，已排队）'}`);
  } catch (e) {
    console.error('无法连接服务端：', e.message);
    process.exit(1);
  }
}

main();
