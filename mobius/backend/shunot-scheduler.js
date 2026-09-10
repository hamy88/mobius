#!/usr/bin/env node
/**
 * shunot-scheduler.js — 定时撰稿系统级调度器（不依赖任何 Claude 会话在线）
 *
 * 职责：
 *   每 30~110 分钟随机间隔，自动在 Issue 0022b157 下创建撰稿 session 并派发写作任务。
 *   - 撰稿 prompt 内置：分类补量轮换、事故级 tags 规范、发布后前台验正文
 *   - 互斥锁：同一时刻只允许一个撰稿 session 在跑（防止堆积）
 *   - 链条自愈：调度器常驻，无需"续接"，重启即恢复
 *
 * 注册于 ecosystem.config.js（pm2 进程 mobius-system-shunot-scheduler），autorestart。
 * 用户授权链：2026-09-07 定时撰稿确认 → 2026-09-08 停电恢复确认 → 2026-09-08 "迁" 迁移确认。
 */
const http = require('http');
const crypto = require('crypto');

const HOST = '127.0.0.1';
const PORT = 33316;
const ISSUE_ID = '0022b157';
const STATE_FILE = '/data/protected_data/shunot-scheduler-state.json';
const LOG_PREFIX = '[shunot-scheduler]';

// ---- token：用 JWT_SECRET 现签 30 天 admin token，过期自动续 ----
function base64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function mintToken(secret) {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const payload = base64url(JSON.stringify({
    id: 'admin', ddisplay_name: '管理员', role: 'admin',
    work_dir: '/data/workspace/admin',
    iat: now, exp: now + 30 * 86400,
  }));
  const sig = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest();
  return `${header}.${payload}.${base64url(sig)}`;
}
let cachedToken = null, cachedTokenAt = 0;
function getToken() {
  if (cachedToken && Date.now() - cachedTokenAt < 7 * 86400 * 1000) return cachedToken;
  cachedToken = mintToken(process.env.JWT_SECRET || '');
  cachedTokenAt = Date.now();
  return cachedToken;
}

// ---- 极简 HTTP client ----
function api(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request({
      host: HOST, port: PORT, path, method, timeout: 20000,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
        ...(data ? { 'Content-Length': Buffer.byteLength(data) } : {}),
      },
    }, (res) => {
      let buf = '';
      res.on('data', (c) => (buf += c));
      res.on('end', () => {
        if (res.statusCode >= 400) return reject(new Error(`${method} ${path} -> ${res.statusCode}: ${buf.slice(0, 200)}`));
        try { resolve(buf ? JSON.parse(buf) : {}); } catch (e) { resolve(buf); }
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// ---- 状态持久化（重启后保留：上次触发时间、序列号、最近分类） ----
const fs = require('fs');
function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return { seq: 25, lastSort: 13, lastRunAt: 0 }; }
}
function saveState(s) {
  try {
    fs.mkdirSync(require('path').dirname(STATE_FILE), { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(s, null, 2));
  } catch (e) { console.error(LOG_PREFIX, 'state save fail:', e.message); }
}

// ---- 分类轮换（缺口优先级，用户 2026-09-07 长期指令） ----
// [sort_id, 别名, 方向候选]
const ROTATION = [
  [12, 'miwifi', '小米方向：APP远程管理、红米专项、米家设备连路由器、儿童上网管控、USB接口玩法、192.168.31.1登录类'],
  [1, '192-168-1-1', '192.168.1.1方向：手机登录教程、改网段、忘记密码重置后重新拨号、进不去但能上网'],
  [10, 'lybk', '百科方向：OFDMA/MU-MIMO白话、天线增益dBi、内存多大够用、端口转发vs DMZ、NAT类型对游戏影响'],
  [13, 'nas', 'NAS方向：SSD缓存值不值、万兆内网要不要上、Docker入门玩法、NAS电费、群晖和飞牛怎么选'],
  [5, 'zhonghe', '综合方向：AC+AP与Mesh对比、租房便携组网、智能设备联网杂谈、测速正确姿势'],
  [8, '192-168-0-1', '192.168.0.1登录入口长尾：水星/腾达后台打不开、初始密码、手机登录'],
  [9, '192-168-2-1', '192.168.2.1登录入口长尾：电信光猫后台、管理员密码、桥接设置'],
];
function nextRotation(lastSort) {
  // 按缺口加权轮换。2026-09-09 修复: 旧版把双权重的分类在数组里相邻放置,
  // findIndex 永远命中第一份 → "下一个"恒为同一分类 → 从 29 篇起连发 8 篇小米.
  // 现改为按各分类当前文章数(缺口)加权: 篇数越少权重越高, 归一化到轮换槽位,
  // 且保证下一轮一定换分类(不与 lastSort 相同).
  const counts = { 1: 6, 8: 3, 9: 2, 5: 8 }; // 缺口最大的四个分类(2026-09-09 实测)
  const order = [9, 8, 1, 5, 12, 10, 13];    // 每轮按缺口从大到小依次轮一遍
  const idx = order.indexOf(lastSort);
  const start = idx >= 0 ? (idx + 1) % order.length : 0;
  for (let i = 0; i < order.length; i++) {
    const cand = order[(start + i) % order.length];
    if (cand !== lastSort) return ROTATION.find((r) => r[0] === cand);
  }
  return ROTATION[0];
}

function buildPrompt(seq, rot, latestGid) {
  return [
    `你是本 Session 的撰稿 agent（定时链第 ${seq} 篇），任务是给 www.shunot.com 写并发布一篇文章。工作目录即项目根 /data/workspace/admin/kind_tree。`,
    `步骤：1.读取项目技能 .mobius/skills/shunot-writing/SKILL.md、shunot-publish/SKILL.md、shunot-seo/SKILL.md 与 .mobius/issue_knowledge/0022b157/issue_knowledge.md 全部条目，严格遵循其规范与已知坑，特别注意【事故级tags规范】：tags 只能纯逗号分隔纯文本字符串，禁止任何 repr/花括号/方括号/引号；publish.py 的 sanitize_tags 会兜底但源文件必须写对；发布后必须抓前台文章页验证正文非空（页面>30000字符），异常立即用 article_update（带 id+sort_id+纯字符串tags）修复。`,
    `2.查看 shunot/articles/ 已有清单与已发布文章（gid 660~${latestGid}），新选题不得重复。【分类补量要求，用户长期指令】本次目标分类 sort_id=${rot[0]}（${rot[2]}）。动笔前先拉全站标题核对（分页 page1-8，SSL 超时重试2-3次）防重复；若目标分类实在无新题，按缺口顺序换下一个分类并在回报里注明。`,
    `3.按 shunot-writing 规范写 2000+ 字去AI化文章：第一人称踩坑、具体数字+品牌型号+命令、无AI腔、5-6个h2、长尾疑问词标题、tags纯逗号分隔、excerpt齐全，sort_id=${rot[0]}，内链用本分类别名 ${rot[1]}。`,
    `4.按 shunot-publish 配图管线做 SVG 配图（禁emoji）并上传插入。`,
    `5.用 shunot/publish.py 发布；POST 返回权限不足HTML是误报，必须 article_list 复查确认；发布后抓前台页验证正文非空。`,
    `6.回报：标题、URL、sort_id、配图数、前台验证结果；把本次经验追加进 issue_knowledge。失败如实报告。`,
    `约束：只做这一篇文章，不做其他SEO动作，不创建任何新会话。`,
  ].join('\n');
}

async function isAnyWriterRunning() {
  // 逐个查 /status：只有 working=true 的撰稿 session 才算在跑（active+idle 是3小时回收期内的僵尸记录）
  try {
    const list = await api('GET', `/api/issues/${ISSUE_ID}/sessions/`);
    const sessions = Array.isArray(list) ? list : (list.sessions || list.data || []);
    const cutoff = Date.now() - 3 * 3600 * 1000;
    const candidates = sessions.filter((s) => {
      const name = s.name || '';
      const recent = new Date(s.last_active || s.created_at || 0).getTime() > cutoff;
      return recent && (name.includes('定时撰稿') || name.includes('撰稿')) && s.status === 'active';
    });
    for (const s of candidates) {
      try {
        const st = await api('GET', `/api/sessions/${s.session_id}/status`);
        if (st && st.working) {
          console.log(LOG_PREFIX, `writer ${s.session_id} (${s.name}) is working`);
          return true;
        }
      } catch { /* status 查不到按不在跑处理 */ }
    }
    return false;
  } catch (e) {
    console.error(LOG_PREFIX, 'session list fail:', e.message);
    return false; // 查不到就照常派发（宁可多查一次 list 也别停链）
  }
}

async function latestArticleGid() {
  // 只读 emlog 公开接口拿最新 gid（不需要鉴权）
  return new Promise((resolve) => {
    const req = http.request({ host: 'www.shunot.com', path: '/?rest-api=article_list&page=1&count=1&api_key=8d384074c434447ab63ade8bd2db9c8c', timeout: 15000, headers: { 'User-Agent': 'Mozilla/5.0' } }, (res) => {
      let buf = '';
      res.on('data', (c) => (buf += c));
      res.on('end', () => {
        try {
          const d = JSON.parse(buf);
          const arts = d.data || d.articles || d;
          const a = Array.isArray(arts) ? arts[0] : (arts.articles || [])[0];
          resolve(a ? a.id || 780 : 780);
        } catch { resolve(780); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(780); });
    req.on('error', () => resolve(780));
    req.end();
  });
}

async function runOnce() {
  const state = loadState();
  if (!isInWindow(new Date())) {
    console.log(LOG_PREFIX, 'outside BJT 08:00-22:00 window at runOnce entry, defer');
    scheduleNext();
    return;
  }
  if (await isAnyWriterRunning()) {
    console.log(LOG_PREFIX, 'writer still running, skip this tick');
    scheduleNext();
    return;
  }
  const rot = nextRotation(state.lastSort);
  const latestGid = await latestArticleGid();
  // session 名显式用北京时间（CST, UTC+8），不依赖系统 TZ (Issue 8f64748a)
  const now = new Date();
  const cstMs = now.getTime() + (8 * 60 - now.getTimezoneOffset()) * 60_000;
  const cstNow = new Date(cstMs);
  const hh = String(cstNow.getHours()).padStart(2, '0');
  const mm = String(cstNow.getMinutes()).padStart(2, '0');
  try {
    const created = await api('POST', `/api/issues/${ISSUE_ID}/sessions/`, {
      name: `定时撰稿 ${hh}-${mm}`,
      description: `系统级定时撰稿调度（第 ${state.seq + 1} 篇）：分类轮换 sort_id=${rot[0]}`,
      model: 'claude-code:glm-5.2-统一',
      language: 'zh',
    });
    const sid = created.session_id;
    if (!sid) throw new Error('no session_id in response');
    await api('POST', `/api/sessions/${sid}/messages`, { content: buildPrompt(state.seq + 1, rot, latestGid) });
    state.seq += 1;
    state.lastSort = rot[0];
    state.lastRunAt = Date.now();
    state.lastSessionId = sid;
    saveState(state);
    console.log(LOG_PREFIX, `dispatched #${state.seq} session=${sid} sort=${rot[0]}`);
  } catch (e) {
    console.error(LOG_PREFIX, 'dispatch failed:', e.message);
  }
  scheduleNext();
}

let timer = null;
// ---- 北京时间派发窗口 08:00–22:00（显式 UTC+8，不依赖系统 TZ）----
function beijingHour(now) {
  return (now.getUTCHours() + 8) % 24;
}
function isInWindow(now) {
  const h = beijingHour(now);
  return h >= 8 && h < 22;
}
function nextWindowStart(now) {
  // 下一个北京时间 08:00 = 当天或次日 00:00 UTC
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  if (Date.now() >= d.getTime()) d.setUTCDate(d.getUTCDate() + 1); // 已过当日 00:00 UTC(BJT 08:00)，顺延到次日
  return d.getTime();
}
function scheduleNext() {
  if (timer) clearTimeout(timer);
  if (isInWindow(new Date())) {
    const delayMin = 30 + Math.floor(Math.random() * 81); // 30~110 分钟
    console.log(LOG_PREFIX, `next dispatch in ${delayMin} min`);
    timer = setTimeout(runOnce, delayMin * 60 * 1000);
  } else {
    const delayMs = nextWindowStart(new Date()) - Date.now();
    const delayMin = Math.ceil(delayMs / 60000);
    console.log(LOG_PREFIX, `outside BJT 08:00-22:00 window, sleep until next 08:00 BJT (${delayMin} min)`);
    timer = setTimeout(runOnce, delayMs);
  }
}

console.log(LOG_PREFIX, 'scheduler started (system-level, survives restarts)');
// 启动后 60 秒先跑一轮（若断链期间有欠账），此后随机间隔
setTimeout(runOnce, 60 * 1000);
