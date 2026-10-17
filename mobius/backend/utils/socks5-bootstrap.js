'use strict';
/**
 * socks5-bootstrap.js
 *
 * 系统级 SOCKS5 出站代理 (用户授权, 范围: LLM 模型调用 + GitHub API)
 *
 * 设计:
 *   - 通过覆盖 undici global dispatcher, 在建连时按 host 决定走 SOCKS5 还是直连
 *   - 范围 (SOCKS5): LLM (openai / anthropic / 豆包 / GLM / DeepSeek) + GitHub
 *   - 范围 (直连): 内网 / localhost / shunot.com / 5 站长平台 / wuyou / 微信 / 飞书 等
 *
 * 装包 (已完成, 2026-10-16):
 *   npm install --save socks-proxy-agent@7  (CJS, 给 https.get 用)
 *   npm install --save socks@2              (SocksClient, 给 undici 用)
 *   npm install --save undici@6             (setGlobalDispatcher + Agent.connect)
 *
 * 环境变量:
 *   MOBIUS_LLM_SOCKS5_ENABLED=0   关闭 (默认 1, ON)
 *   MOBIUS_LLM_SOCKS5_URL         代理地址 (默认 socks5://111.36.208.22:10880)
 *   MOBIUS_LLM_SOCKS5_DEBUG=1     打开调试日志
 */

const net = require('node:net');
const tls = require('node:tls');
const url = require('node:url');

const SOCKS5_ENABLED = String(process.env.MOBIUS_LLM_SOCKS5_ENABLED || '1') !== '0';
const SOCKS5_URL = process.env.MOBIUS_LLM_SOCKS5_URL || 'socks5://111.36.208.22:10880';
const DEBUG = String(process.env.MOBIUS_LLM_SOCKS5_DEBUG || '0') === '1';

function log(msg) {
  process.stderr.write(`[socks5-bootstrap] ${msg}\n`);
}
function debug(msg) {
  if (DEBUG) log(`[debug] ${msg}`);
}

// 解析 SOCKS5 URL
function parseSocksUrl(socksUrl) {
  const u = url.parse(socksUrl);
  if (u.protocol !== 'socks5:' && u.protocol !== 'socks:') {
    throw new Error(`[socks5-bootstrap] 仅支持 socks5:// 协议, 当前: ${u.protocol}`);
  }
  return {
    host: u.hostname,
    port: u.port ? parseInt(u.port, 10) : 1080,
    userId: u.auth ? decodeURIComponent(u.auth.split(':')[0]) : null,
    password: u.auth ? decodeURIComponent(u.auth.split(':')[1] || '') : null,
  };
}

// 判断某个 host 是否走直连 (返回 true = 直连, false = 走 SOCKS5)
function shouldBypass(host) {
  if (!host) return true;
  const lower = host.toLowerCase();

  // localhost / 127.0.0.1 / ::1
  if (lower === 'localhost' || lower === '127.0.0.1' || lower === '::1' || lower === '[::1]') return true;

  // 私网 IP 段
  if (net.isIP(lower)) {
    if (lower === '127.0.0.1' || lower === '::1') return true;
    if (lower.startsWith('127.')) return true;
    if (lower.startsWith('10.')) return true;
    if (lower.startsWith('192.168.')) return true;
    if (lower.startsWith('169.254.')) return true;
    if (lower.startsWith('172.')) {
      const second = parseInt(lower.split('.')[1], 10);
      if (second >= 16 && second <= 31) return true;
    }
    if (lower.startsWith('fc') || lower.startsWith('fd')) return true; // IPv6 ULA
    if (lower.startsWith('fe80')) return true; // IPv6 link-local
  }

  // shunot.com 内部站点
  if (lower === 'shunot.com' || lower === 'www.shunot.com' || lower.endsWith('.shunot.com')) return true;

  // === 走 SOCKS5 的 (返回 false) ===

  // LLM 模型
  if (
    lower.endsWith('.openai.com') || lower === 'openai.com' ||
    lower.endsWith('.anthropic.com') || lower === 'anthropic.com' ||
    lower.endsWith('.deepseek.com') || lower === 'deepseek.com' ||
    lower.endsWith('.bigmodel.cn') || lower === 'bigmodel.cn' || // 智谱 GLM
    lower.endsWith('.volces.com') || lower === 'volces.com' || // 火山 (豆包/字节)
    lower.endsWith('.volcengine.com') || lower === 'volcengine.com' ||
    lower.endsWith('.bytedance.com') || lower === 'bytedance.com' ||
    lower.endsWith('.moonshot.cn') || lower === 'moonshot.cn' || // Kimi
    lower.endsWith('.dashscope.aliyuncs.com') || // 阿里通义
    lower.endsWith('.baidubce.com') // 百度智能云 (千帆)
  ) return false;

  // GitHub
  if (
    lower === 'github.com' || lower.endsWith('.github.com') ||
    lower === 'api.github.com' ||
    lower === 'raw.githubusercontent.com' ||
    lower === 'objects.githubusercontent.com' ||
    lower.endsWith('.githubusercontent.com') ||
    lower === 'ghcr.io'
  ) return false;

  // === 其他走直连 (白名单) ===
  // 5 站长平台
  if (lower.endsWith('.baidu.com') || lower === 'baidu.com') return true;
  if (lower.endsWith('.bing.com') || lower === 'bing.com') return true;
  if (lower.endsWith('.google.com') || lower === 'google.com') return true;
  if (lower.endsWith('.googleapis.com') || lower.endsWith('.gstatic.com')) return true;
  if (lower.endsWith('.sm.cn') || lower === 'sm.cn') return true;
  if (lower.endsWith('.toutiao.com') || lower === 'toutiao.com') return true;

  // wuyou / ipzan
  if (lower.endsWith('.ipzan.com') || lower === 'ipzan.com') return true;

  // 微信 / 飞书 webhook
  if (lower.endsWith('.weixin.qq.com') || lower.endsWith('.qq.com')) return true;
  if (lower.endsWith('.feishu.cn') || lower === 'feishu.cn') return true;
  if (lower.endsWith('.larksuite.com')) return true;

  // 其它未列出的: 保守走 SOCKS5 (用户范围: LLM + GitHub; 其它保持原有行为)
  return false;
}

let _socksAgent = null;
let _undiciDispatcherInstalled = false;
let _proxyConfig = null;

function createSocksProxyAgent() {
  // socks-proxy-agent@7 CJS
  const mod = require('socks-proxy-agent');
  const SocksProxyAgent = mod.SocksProxyAgent || mod.default;
  if (!SocksProxyAgent) {
    throw new Error('[socks5-bootstrap] socks-proxy-agent 找不到 SocksProxyAgent 导出');
  }
  return new SocksProxyAgent(SOCKS5_URL);
}

async function installUndiciGlobalDispatcher() {
  const undici = require('undici');
  const { SocksClient } = require('socks');
  const proxy = parseSocksUrl(SOCKS5_URL);

  const dispatcher = new undici.Agent({
    connect: (opts, callback) => {
      const host = opts.hostname || opts.host;
      const defaultPort = opts.protocol === 'https:' ? 443 : 80;
      const portNum = opts.port && opts.port !== '' ? parseInt(opts.port, 10) : defaultPort;

      const bypass = shouldBypass(host);
      debug(`connect ${host}:${portNum} ${opts.protocol} → ${bypass ? 'DIRECT' : 'SOCKS5'}`);

      if (bypass) {
        // 直连
        if (opts.protocol === 'https:') {
          const s = tls.connect({ host, port: portNum, servername: opts.servername || host });
          s.once('secureConnect', () => callback(null, s));
          s.once('error', (e) => callback(e));
        } else {
          const s = net.connect({ host, port: portNum });
          s.once('connect', () => callback(null, s));
          s.once('error', (e) => callback(e));
        }
        return;
      }

      // SOCKS5
      SocksClient.createConnection({
        proxy: {
          host: proxy.host,
          port: proxy.port,
          type: 5,
          userId: proxy.userId || undefined,
          password: proxy.password || undefined,
        },
        command: 'connect',
        destination: { host, port: portNum },
        timeout: 15000,
      }).then(({ socket }) => {
        if (opts.protocol === 'https:') {
          const tlsSocket = tls.connect({ socket, servername: opts.servername || host });
          tlsSocket.once('secureConnect', () => callback(null, tlsSocket));
          tlsSocket.once('error', (e) => callback(e));
        } else {
          callback(null, socket);
        }
      }).catch((err) => callback(err));
    },
  });

  undici.setGlobalDispatcher(dispatcher);
  _undiciDispatcherInstalled = true;
  _proxyConfig = proxy;
}

function bootstrap() {
  if (!SOCKS5_ENABLED) {
    log(`已禁用 (MOBIUS_LLM_SOCKS5_ENABLED=0), 所有出站走直连`);
    return { enabled: false };
  }

  log(`启用 SOCKS5 出站代理: ${SOCKS5_URL} (范围: LLM + GitHub)`);

  try {
    _socksAgent = createSocksProxyAgent();
    log(`socks-proxy-agent 实例已创建 (用于 https.get / https.request)`);
  } catch (e) {
    log(`⚠️ socks-proxy-agent 创建失败: ${e.message}`);
  }

  installUndiciGlobalDispatcher()
    .then(() => log(`undici global dispatcher 已安装 (影响所有 fetch())`))
    .catch((e) => {
      log(`⚠️ undici dispatcher 安装失败: ${e.message}`);
    });

  return {
    enabled: true,
    url: SOCKS5_URL,
    getAgent() { return _socksAgent; },
    isDispatcherInstalled() { return _undiciDispatcherInstalled; },
    shouldBypass,  // 暴露, 给 sync-desktop-builds.js 之类的外部模块复用白名单
  };
}

const _state = SOCKS5_ENABLED ? bootstrap() : { enabled: false };

async function waitReady(timeoutMs = 5000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (!_state.enabled || _state.isDispatcherInstalled()) return _state;
    await new Promise((r) => setTimeout(r, 50));
  }
  return _state;
}

module.exports = {
  ..._state,
  waitReady,
  shouldBypass,  // 复用
  // 给那些想显式走 SOCKS5 的 fetch (即使 host 在白名单里)
  socksFetch: async function socksFetch(input, init = {}) {
    if (!_state.enabled) return fetch(input, init);
    const undici = require('undici');
    return undici.fetch(input, { ...init, dispatcher: undici.getGlobalDispatcher() });
  },
};
