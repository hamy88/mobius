// Mobius 扩展 momo-mobile 的后端 handler。
//
// 聚合推送（极光推送 JPush）的服务端链路：
//   客户端登录后调 /api/ext({action:'register_device', token, platform}) → 本 handler 把
//   设备令牌(RegistrationID)持久化到 ext_data_dir/devices.json；客户端登出/关推送调
//   unregister_device 解绑。需要给用户发推送时调 notify_user → 本 handler 查该用户绑定的
//   令牌，调用 JPush 服务端 REST(https://api.jpush.cn/v3/push) 下发。
//
// 约束（见 .mobius/skills/mobius-extension/SKILL.md）：stateless、单次 ≤30s、只写 ext_data_dir、
// 不回显 stack、用 logger.*。AppKey/Master Secret 只在服务端用，绝不进客户端——从 env 读取，
// 回退到 ext_data_dir/jpush_config.json。

const path = require('path');
const fs = require('fs/promises');

const EXTENSION_NAME = 'momo-mobile';
const DEVICES_FILE = 'devices.json'; // 相对 ext_data_dir
const CONFIG_FILE = 'jpush_config.json'; // 相对 ext_data_dir，可选的密钥回退

// JPush 服务端 REST。默认官方地址；测试时可用 MOMO_JPUSH_API 重定向到本地 capture server。
function jpushApiBase() {
  return (process.env.MOMO_JPUSH_API || 'https://api.jpush.cn').replace(/\/+$/, '');
}

// 读取 AppKey / Master Secret：优先 env，回退 ext_data_dir/jpush_config.json。
async function readJpushCredentials(ext_data_dir) {
  const envKey = process.env.MOMO_JPUSH_APPKEY;
  const envSecret = process.env.MOMO_JPUSH_MASTER_SECRET;
  if (envKey && envSecret) return { appkey: envKey, masterSecret: envSecret };
  try {
    const raw = await fs.readFile(path.join(ext_data_dir, CONFIG_FILE), 'utf8');
    const cfg = JSON.parse(raw);
    if (cfg.appkey && cfg.master_secret) {
      return { appkey: String(cfg.appkey), masterSecret: String(cfg.master_secret) };
    }
  } catch {
    // 配置文件不存在或不合法——回退到 env 单项（push 时若缺 secret 会返回明确错误）。
  }
  return { appkey: envKey || '', masterSecret: envSecret || '' };
}

// ===== 华为 HMS 直推 (JPush 4.0.5 华为插件接不住 HMS 6.x 令牌, 故 App 被杀时由华为 Push API 直推) =====
// 调 JPush REST 给一组 JPush RegistrationID 下发(自有通道, App 在线时送达)。
async function pushViaJpush({ jpushTokens, title, body, deepLink, ext_data_dir, log }) {
  const { appkey, masterSecret } = await readJpushCredentials(ext_data_dir);
  if (!appkey || !masterSecret) {
    return { ok: false, error: 'appkey/master_secret missing' };
  }
  const payload = {
    platform: 'all',
    audience: { registration_id: jpushTokens },
    notification: {
      alert: body,
      android: { title, extras: deepLink ? { deepLink } : {} },
      ios: { alert: body, sound: 'default', extras: deepLink ? { deepLink } : {} },
    },
    options: { time_to_live: 604800 },
  };
  const auth = Buffer.from(`${appkey}:${masterSecret}`).toString('base64');
  try {
    const resp = await fetch(`${jpushApiBase()}/v3/push`, {
      method: 'POST',
      headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (resp.status < 200 || resp.status >= 300) {
      const respBody = await resp.text();
      log.warn(`jpush rejected status=${resp.status} body=${respBody.slice(0, 300)}`);
      return { ok: false, error: `jpush rejected (${resp.status})` };
    }
    return { ok: true, pushed: jpushTokens.length };
  } catch (e) {
    log.error(`jpush push request failed: ${e && e.message}`);
    return { ok: false, error: 'jpush request failed' };
  }
}

function huaweiOauthBase() {
  return (process.env.MOMO_HUAWEI_OAUTH || 'https://oauth-login.cloud.huawei.com').replace(/\/+$/, '');
}
function huaweiPushBase() {
  return (process.env.MOMO_HUAWEI_PUSH || 'https://push-api.cloud.huawei.com').replace(/\/+$/, '');
}

// 读取华为 AppID / App Secret: 优先 env, 回退 ext_data_dir/huawei_config.json 或 jpush_config.json。
async function readHuaweiCredentials(ext_data_dir) {
  const envAppId = process.env.MOMO_HUAWEI_APPID;
  const envSecret = process.env.MOMO_HUAWEI_APP_SECRET;
  if (envAppId && envSecret) return { appId: envAppId, appSecret: envSecret };
  for (const f of ['huawei_config.json', 'jpush_config.json']) {
    try {
      const raw = await fs.readFile(path.join(ext_data_dir, f), 'utf8');
      const cfg = JSON.parse(raw);
      if (cfg.huawei_app_id && cfg.huawei_app_secret) {
        return { appId: String(cfg.huawei_app_id), appSecret: String(cfg.huawei_app_secret) };
      }
    } catch {
      // continue
    }
  }
  return { appId: envAppId || '', appSecret: envSecret || '' };
}

// 华为 OAuth2 access token 缓存(handler 每次 stateless 新建, 缓存仅在同次调用内多次推送时复用)。
let _huaweiAccessToken = null;
let _huaweiTokenExpire = 0;

async function getHuaweiAccessToken(appId, appSecret, log) {
  const now = Date.now();
  if (_huaweiAccessToken && _huaweiTokenExpire > now + 60000) return _huaweiAccessToken;
  const resp = await fetch(`${huaweiOauthBase()}/oauth2/v3/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'client_credentials',
      client_id: appId,
      client_secret: appSecret,
    }).toString(),
  });
  const data = await resp.json().catch(() => ({}));
  if (!data.access_token) {
    log.warn(`huawei oauth failed status=${resp.status} body=${JSON.stringify(data).slice(0, 300)}`);
    throw new Error(`huawei oauth failed (${resp.status})`);
  }
  _huaweiAccessToken = data.access_token;
  _huaweiTokenExpire = now + (data.expires_in ? data.expires_in * 1000 : 3600000);
  return _huaweiAccessToken;
}

// 调华为 Push API 直推给一组华为令牌。
async function pushViaHuawei({ huaweiTokens, title, body, deepLink, ext_data_dir, log }) {
  const { appId, appSecret } = await readHuaweiCredentials(ext_data_dir);
  if (!appId || !appSecret) {
    return { ok: false, error: 'huawei not configured (appid/app_secret missing)' };
  }
  let accessToken;
  try {
    accessToken = await getHuaweiAccessToken(appId, appSecret, log);
  } catch (e) {
    return { ok: false, error: e.message };
  }
  // 华为 click_action: type=1 必须带 intent(华为校验); 用打开本应用的 intent。
  // 若有 deepLink, 作为 intent extra 注入, 客户端 MainActivity 启动时读 deepLink 拉起对应页。
  let intent = `intent://com.mobius.momo#Intent;scheme=momopush;launchFlags=0x10000000;package=com.mobius.momo;end`;
  if (deepLink) {
    intent = `intent://com.mobius.momo#Intent;scheme=momopush;launchFlags=0x10000000;package=com.mobius.momo;S.deepLink=${encodeURIComponent(deepLink)};end`;
  }
  const message = {
    notification: { title, body },
    android: {
      urgency: 'HIGH',
      category: 'WORK',
      notification: {
        title,
        body,
        foreground_show: true, // App 前台时也由华为自动展示(否则只投递给 onMessageReceived 不弹)
        click_action: { type: 1, intent },
      },
    },
    token: huaweiTokens,
  };
  if (deepLink) {
    // data 字段(JSON 字符串) → 客户端 onMessageReceived 经 dataOfMap 读 deepLink
    message.data = JSON.stringify({ deepLink });
  }
  const resp = await fetch(`${huaweiPushBase()}/v1/${appId}/messages:send`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, validate_only: false }),
  });
  const respBody = await resp.text();
  if (resp.status < 200 || resp.status >= 300) {
    log.warn(`huawei push rejected status=${resp.status} body=${respBody.slice(0, 300)}`);
    return { ok: false, error: `huawei rejected (${resp.status})` };
  }
  // 华为返回 { code, msg, requestId }; code=='80000000' = 成功
  let parsed = {};
  try { parsed = JSON.parse(respBody); } catch {}
  if (parsed.code && String(parsed.code) !== '80000000') {
    log.warn(`huawei push code=${parsed.code} msg=${parsed.msg}`);
    return { ok: false, error: `huawei code ${parsed.code}: ${parsed.msg || ''}` };
  }
  return { ok: true, pushed: huaweiTokens.length };
}

// devices.json 结构：{ "<username>": [ { token, platform, ts } ] }
async function readDevices(ext_data_dir) {
  try {
    const raw = await fs.readFile(path.join(ext_data_dir, DEVICES_FILE), 'utf8');
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

async function writeDevices(ext_data_dir, data) {
  // 原子写：先写临时文件再 rename，避免并发/中断损坏。
  const target = path.join(ext_data_dir, DEVICES_FILE);
  const tmp = `${target}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(data), 'utf8');
  await fs.rename(tmp, target);
}

function isNonEmptyString(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= 1024;
}

module.exports = async function momoMobileHandler({
  username,
  display_name,
  extension_name,
  ext_main_payload,
  ext_data_dir,
  logger,
}) {
  const log = logger || { info() {}, warn() {}, error() {} };
  const action = ext_main_payload && ext_main_payload.action;
  const user = typeof username === 'string' && username.length > 0 ? username : null;

  // ---------- whoami：原探活动作，保留 ----------
  if (action === 'whoami') {
    return { ok: true, username, display_name: display_name || username || '', extension_name };
  }

  // 以下设备相关动作都需要可信 username（JWT 注入）
  if (!user) return { ok: false, error: 'auth required' };
  if (!ext_data_dir) return { ok: false, error: 'ext_data_dir unavailable' };

  // ---------- register_device：绑定设备令牌 ----------
  if (action === 'register_device') {
    const token = ext_main_payload && ext_main_payload.token;
    const platform = (ext_main_payload && ext_main_payload.platform) || 'jpush';
    if (!isNonEmptyString(token)) return { ok: false, error: 'invalid token' };
    if (!isNonEmptyString(platform)) return { ok: false, error: 'invalid platform' };

    const data = await readDevices(ext_data_dir);
    const list = Array.isArray(data[user]) ? data[user].slice() : [];
    const idx = list.findIndex((d) => d && d.token === token);
    const entry = { token, platform, ts: Date.now() };
    if (idx >= 0) list[idx] = entry;
    else list.push(entry);
    data[user] = list;
    await writeDevices(ext_data_dir, data);
    log.info(`register_device: user=${user} platform=${platform} devices=${list.length}`);
    return { ok: true, registered: list.length };
  }

  // ---------- unregister_device：解绑设备令牌 ----------
  if (action === 'unregister_device') {
    const token = ext_main_payload && ext_main_payload.token;
    if (!isNonEmptyString(token)) return { ok: false, error: 'invalid token' };
    const data = await readDevices(ext_data_dir);
    const list = Array.isArray(data[user]) ? data[user] : [];
    const next = list.filter((d) => d && d.token !== token);
    data[user] = next;
    await writeDevices(ext_data_dir, data);
    log.info(`unregister_device: user=${user} remaining=${next.length}`);
    return { ok: true, registered: next.length };
  }

  // ---------- list_devices：查看自己绑定的设备（调试用） ----------
  if (action === 'list_devices') {
    const data = await readDevices(ext_data_dir);
    const list = Array.isArray(data[user]) ? data[user] : [];
    return { ok: true, devices: list.map((d) => ({ platform: d.platform, ts: d.ts })) };
  }

  // ---------- notify_user：向「调用者本人」的设备发推送 ----------
  // 安全：只能推给 JWT 身份对应的用户，不能指定他人（否则任意用户可互相打扰）。
  // 真实生产触发：Mobius 消息管线在产生新消息/回复时，以目标用户身份调用本 handler 的
  // notify_user（见 docs/PUSH-INTEGRATION.md「触发」一节，属主项目联合修改）。
  if (action === 'notify_user') {
    const title = (ext_main_payload && ext_main_payload.title) || 'Mobius';
    const body = ext_main_payload && ext_main_payload.body;
    const deepLink = ext_main_payload && ext_main_payload.deepLink;
    if (!isNonEmptyString(body)) return { ok: false, error: 'invalid body' };

    const data = await readDevices(ext_data_dir);
    const list = Array.isArray(data[user]) ? data[user] : [];
    if (list.length === 0) return { ok: true, pushed: 0, reason: 'no registered device' };

    // 按平台分组下发: jpush(App 在线时自有通道) + huawei(App 被杀时华为直推)。
    const jpushTokens = list.filter((d) => (d.platform || 'jpush') === 'jpush').map((d) => d.token);
    const huaweiTokens = list.filter((d) => d.platform === 'huawei').map((d) => d.token);

    let pushed = 0;
    const errors = [];

    if (jpushTokens.length) {
      const r = await pushViaJpush({ jpushTokens, title, body, deepLink, ext_data_dir, log });
      if (r.ok) pushed += r.pushed; else errors.push('jpush: ' + r.error);
    }
    if (huaweiTokens.length) {
      const r = await pushViaHuawei({ huaweiTokens, title, body, deepLink, ext_data_dir, log });
      if (r.ok) pushed += r.pushed; else errors.push('huawei: ' + r.error);
    }

    log.info(`notify_user: user=${user} jpush=${jpushTokens.length} huawei=${huaweiTokens.length} pushed=${pushed}`);
    if (pushed === 0 && errors.length) return { ok: false, error: errors.join('; ') };
    return { ok: true, pushed, jpush: jpushTokens.length, huawei: huaweiTokens.length };
  }

  return { ok: false, error: 'unknown action' };
};
