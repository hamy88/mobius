/**
 * secret-guard.ts — 消息内隐秘数据自动加密 (密文占位符, 不落明文)
 *
 * 用户向系统发消息 (会话 / 群聊) 时, 文本里可能夹带密码、API key、token 等
 * 隐秘数据。此前它们以明文写进 messages_v2 / jsonl 与群消息表, 任何能读到
 * 库或文件的人都能直接看到。本模块在消息入口处自动识别并加密:
 *
 *   1. detectAndEncrypt(text) → 把命中的敏感片段替换为占位符
 *        <MOBIUS-ENC:v1:base64(iv:tag:cipher)> (AES-256-GCM)
 *   2. decryptText(text)     → 把占位符还原为明文 (管理员/本人查看用)
 *   3. maskEncryptedForDisplay(text) → 前端展示用: 占位符 → 🔒已加密(尾4位)
 *
 * 密钥派生: 优先环境变量 MOBIUS_SECRET_GUARD_KEY (hex/任意串),
 * 否则读 <MOBIUS_DATA_PATH>/secret-guard.key (首次自动生成 0600),
 * 都不可用时退化为进程内随机密钥 (重启后无法解密, 仅保证不落明文)。
 *
 * 设计原则: fail-open — 检测/加密失败不阻塞消息发送, 原文照发;
 * 但加密成功则保证 DB / jsonl / 下发 prompt 中不再含该明文。
 */

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';

const VERSION = 'v1';
// 占位符形态: <MOBIUS-ENC:v1:xxxxx> — 无空格, base64url 载荷, 可正则全局匹配
const ENC_RE = /<MOBIUS-ENC:v1:([A-Za-z0-9_-]+)>/g;
const ENC_PREFIX = `<MOBIUS-ENC:${VERSION}:`;

// ── 敏感模式识别 ────────────────────────────────────────────────────────────
// 命中任一模式 → 抽出"值"部分加密。模式刻意保守: 宁可漏检 (用户可自行改写),
// 不可误伤普通文本 (把整句话加密掉会破坏 agent 理解)。

type SecretPattern = {
  name: string;
  // 返回 [整段匹配, 要加密的值] ；null = 不命中
  match: (text: string) => Array<[string, string]> | null;
};

/** key=value / key: value 形态, key 是敏感词 */
const KEY_VALUE_PATTERN: SecretPattern = {
  name: 'key-value',
  match: makeKeyValueMatcher(),
};

function makeKeyValueMatcher() {
  // 敏感 key 词表 (不区分大小写); value 至少 4 字符才算敏感, 避免把 "密码:无" 加密
  const SENSITIVE_KEYS = [
    'password', 'passwd', 'pwd', 'pass',
    '密码', '口令', '旧密码', '新密码',
    'secret', 'secret_key', 'secretkey', 'client_secret',
    'api_key', 'apikey', 'api-key', 'access_key',
    'token', 'access_token', 'auth_token', 'private_token', 'refresh_token',
    '密钥', '令牌', '凭证',
  ];
  const keyAlt = SENSITIVE_KEYS.map(escapeRe).join('|');
  // 形态: key + 分隔(: = ： ＝ 为 是) + 值。key 前无字符种类限制
  // (前面可以是中文如"数据库密码:", /(?<![\w-])/ 仅排除把 password123 整个当 key)。
  // 值: 非空白起头的连续段 (允许 -_./+= 等密钥常见字符), 引号包裹时到闭引号
  const re = new RegExp(
    '(?<![\\w-])((?:' + keyAlt + ')\\s*[:\uff1a=\uff1d\u662f]\\s*)('
    + '`[^`]{4,}`|"[^"]{4,}"|\'[^\']{4,}\'|(?!\\x00S)[^\\s,;\uff0c\uff1b\\]\\)\uff09\u3011}<]{4,})',
    'gim',
  );
  return (text: string) => {
    const out: Array<[string, string]> = [];
    let m: RegExpExecArray | null;
    re.lastIndex = 0;
    while ((m = re.exec(text)) !== null) {
      // m[0]=含前导分隔的整段, m[1]=key+分隔, m[2]=值
      out.push([m[2], m[2]]); // 只加密"值"本身
    }
    return out.length ? out : null;
  };
}

/** 高熵固定形态密钥 (与 key 无关, 直接按形状识别) */
const SHAPED_SECRET_PATTERNS: SecretPattern[] = [
  {
    // OpenAI sk-...
    name: 'openai-key',
    match: collect(/(?:^|[^\w-])(sk-[A-Za-z0-9_-]{16,})/g, 1),
  },
  {
    // 命令行内嵌密码: sshpass -p 'xxx' / docker login -p xxx / mysql -pXXX / curl -u user:pass
    // agent 生成的 Bash 命令常带这些形态, 是工具卡明文泄露的主通道。
    name: 'cli-password',
    match: (text: string) => {
      const out: Array<[string, string]> = [];
      // sshpass -p <值> / -p<值> (引号或裸值); docker/curl -u; mysql -p紧贴
      const patterns: Array<RegExp> = [
        // sshpass -p 'val' | "val" | val (前导分隔含左括号, 覆盖 "(sshpass -p xxx" 回显形态)
        /(?:^|[\s;&|(])(sshpass\s+(?:-p\s*|--pw\s+|env\s+SSHPASS=))('([^'\s]{4,})'|"([^"\s]{4,})"|([^\s)]{4,}))/gm,
        // mysql/mysqldump -pVAL (紧贴无空格; -p 后直接跟密码, 前面可带"选项 值"对)
        /(?:^|\s)(mysql|mysqldump)(?:\s+(?:-[a-zA-Z][^\s]*|"[^"]*"|\S+))*?\s+-p([^\s'"]{4,})/gm,
        // docker login -p val / --password val / --password-stdin 之外的明文
        /(?:^|[\s;&|])(docker\s+login\s+(?:[^\n]*?\s+)?(?:-p|--password)[ =])('([^'\s]{4,})'|"([^"\s]{4,})"|([^\s]{4,}))/gm,
        // curl -u user:password / --user user:password (只取 :password 段)
        /(?:^|[\s;&|])(curl\s+[^\n]*?(?:-u|--user)\s+)([^\s:@/]{1,64}):([^\s@/]{4,})/gm,
        // psql postgresql://user:pass@host
        /(:\/\/)([^\s:@/]{1,64}):([^\s@/]{4,})@/gm,
      ];
      for (const re of patterns) {
        re.lastIndex = 0;
        let m: RegExpExecArray | null;
        while ((m = re.exec(text)) !== null) {
          // 取"值"捕获组 (最后一个非 undefined 组)
          let value = '';
          for (let g = m.length - 1; g >= 1; g -= 1) {
            if (m[g] != null) { value = m[g]; break; }
          }
          if (value && value.trim().length >= 4 && !value.startsWith('$')) {
            out.push([value, value.trim()]);
          }
        }
      }
      return out.length ? out : null;
    },
  },
  {
    // AWS AccessKeyId
    name: 'aws-key',
    match: collect(/(?:^|[^\w-])(AKIA[0-9A-Z]{16})/g, 1),
  },
  {
    // GitHub PAT ghp_/gho_...
    name: 'github-pat',
    match: collect(/(?:^|[^\w-])(gh[pousr]_[A-Za-z0-9]{30,})/g, 1),
  },
  {
    // 谷歌 AIza...
    name: 'google-key',
    match: collect(/(?:^|[^\w-])(AIza[0-9A-Za-z_-]{30,})/g, 1),
  },
  {
    // Bearer 头里的 token
    name: 'bearer',
    match: collect(/(?:^|\s)(Bearer\s+)([A-Za-z0-9._+/=-]{16,})/gi, 2),
  },
  {
    // jwt (三段 base64url, 头部解出 {"alg")
    name: 'jwt',
    match: (text) => {
      const re = /(?:^|[^\w.-])(eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,})/g;
      const out: Array<[string, string]> = [];
      let m: RegExpExecArray | null;
      while ((m = re.exec(text)) !== null) {
        try {
          const header = JSON.parse(Buffer.from(m[1].split('.')[0], 'base64url').toString('utf8'));
          if (header && typeof header.alg === 'string') out.push([m[1], m[1]]);
        } catch { /* 非 jwt, 跳过 */ }
      }
      return out.length ? out : null;
    },
  },
  {
    // 私钥块 (PEM)
    name: 'pem',
    match: collect(/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, 0),
  },
];

function collect(re: RegExp, group: number) {
  return (text: string) => {
    const out: Array<[string, string]> = [];
    let m: RegExpExecArray | null;
    re.lastIndex = 0;
    while ((m = re.exec(text)) !== null) {
      const value = m[group];
      if (value && value.trim().length >= 8) out.push([value, value.trim()]);
    }
    return out.length ? out : null;
  };
}

function escapeRe(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ── 密钥管理 ────────────────────────────────────────────────────────────────

let cachedKey: Buffer | null = null;
let cachedKeyOnce = false;

function keyFilePath(): string {
  const dataPath = process.env.MOBIUS_DATA_PATH || '/data';
  return path.join(dataPath, 'secret-guard.key');
}

function loadKey(): Buffer {
  if (cachedKeyOnce) return cachedKey as Buffer;
  cachedKeyOnce = true;
  try {
    const envKey = process.env.MOBIUS_SECRET_GUARD_KEY;
    if (envKey && envKey.trim()) {
      cachedKey = deriveKey(envKey.trim());
      return cachedKey;
    }
    const file = keyFilePath();
    if (fs.existsSync(file)) {
      cachedKey = deriveKey(fs.readFileSync(file, 'utf8').trim());
      return cachedKey;
    }
    const generated = crypto.randomBytes(32).toString('hex');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, generated + '\n', { mode: 0o600 });
    cachedKey = deriveKey(generated);
    return cachedKey;
  } catch {
    // 读不到也写不了 → 进程内随机 (重启后旧密文不可解, 但不落明文的目标仍达成)
    cachedKey = crypto.randomBytes(32);
    return cachedKey;
  }
}

function deriveKey(raw: string): Buffer {
  const trimmed = raw.trim();
  // 64 位 hex 直接作密钥
  if (/^[0-9a-fA-F]{64}$/.test(trimmed)) return Buffer.from(trimmed, 'hex');
  return crypto.createHash('sha256').update(trimmed).digest();
}

// ── 加解密 ──────────────────────────────────────────────────────────────────

function encryptValue(plain: string): string {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', loadKey(), iv);
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  const payload = Buffer.concat([iv, tag, enc]).toString('base64url');
  return `${ENC_PREFIX}${payload}>`;
}

function decryptValue(payloadB64: string): string | null {
  try {
    const raw = Buffer.from(payloadB64, 'base64url');
    if (raw.length < 12 + 16) return null;
    const iv = raw.subarray(0, 12);
    const tag = raw.subarray(12, 28);
    const enc = raw.subarray(28);
    const decipher = crypto.createDecipheriv('aes-256-gcm', loadKey(), iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(enc), decipher.final()]).toString('utf8');
  } catch {
    return null; // 密钥变更/密文损坏 → 不抛错, 展示占位符即可
  }
}

// ── 对外接口 ────────────────────────────────────────────────────────────────

export type EncryptResult = {
  text: string;              // 替换后的文本 (含占位符)
  encryptedCount: number;    // 加密了几个片段
  detectedNames: string[];   // 命中的模式名 (审计/调试)
};

/**
 * 识别并加密文本中的敏感片段。
 * 同一段文本重复调用是幂等的: 已是占位符的不会再匹配 (密文形态不命中任何模式)。
 */
export function detectAndEncrypt(text: string): EncryptResult {
  let input = String(text ?? '');
  if (!input) return { text: input, encryptedCount: 0, detectedNames: [] };

  // 先摘出已有占位符 (防二次加密), 用等长不可命中占位符替换, 结束后还原
  const stashed: string[] = [];
  input = input.replace(ENC_RE, (m) => {
    stashed.push(m);
    // 记号 "\x00S<n>\x00": 值模式已有 (?!\x00S) 负向预查, 双保险不会命中
    return `\x00S${stashed.length - 1}\x00`;
  });

  const hits: Array<{ at: number; len: number; plain: string }> = [];
  const names: string[] = [];

  const patterns = [KEY_VALUE_PATTERN, ...SHAPED_SECRET_PATTERNS];
  for (const p of patterns) {
    try {
      const found = p.match(input);
      if (!found) continue;
      for (const [whole, value] of found) {
        if (!value) continue;
        // 找到值在原文中的确切位置 (跳过已命中区间)
        let from = 0;
        while (from <= input.length) {
          const at = input.indexOf(whole, from);
          if (at < 0) break;
          const end = at + whole.length;
          if (!hits.some((h) => at < h.at + h.len && end > h.at)) {
            hits.push({ at, len: whole.length, plain: value });
            names.push(p.name);
            break;
          }
          from = at + 1;
        }
      }
    } catch { /* 单个模式异常不影响其余 */ }
  }

  if (hits.length === 0) {
    // 无命中: 还原占位符原样返回
    const restored = stashBack(input, stashed);
    return { text: restored, encryptedCount: 0, detectedNames: [] };
  }

  hits.sort((a, b) => a.at - b.at);
  let out = '';
  let cursor = 0;
  for (const h of hits) {
    if (h.at < cursor) continue; // 重叠保护
    out += input.slice(cursor, h.at);
    out += encryptValue(h.plain);
    cursor = h.at + h.len;
  }
  out += input.slice(cursor);
  return { text: stashBack(out, stashed), encryptedCount: hits.length, detectedNames: names };
}

function stashBack(text: string, stashed: string[]): string {
  if (stashed.length === 0) return text;
  return text.replace(/\x00S(\d+)\x00/g, (m, i: string) => stashed[Number(i)] ?? m);
}

/** 是否包含本模块的加密占位符 */
export function hasEncryptedPlaceholder(text: unknown): boolean {
  return typeof text === 'string' && text.includes(ENC_PREFIX);
}

/** 把文本中的占位符全部还原为明文 (查看原文用; 解不开的保留占位符) */
export function decryptText(text: string): string {
  return String(text ?? '').replace(ENC_RE, (_m, payload: string) => decryptValue(payload) ?? _m);
}

/** 展示态: 占位符 → 🔒••••尾4位 (不含明文, 供前端/推送等低敏场景) */
export function maskEncryptedForDisplay(text: string): string {
  return String(text ?? '').replace(ENC_RE, (_m, payload: string) => {
    const plain = decryptValue(payload);
    if (plain == null) return '🔒<已加密>';
    const tail = plain.length > 4 ? plain.slice(-4) : '';
    return `🔒已加密:${tail ? `••••${tail}` : '••••'}`;
  });
}

/** 测试用: 重置密钥缓存 */
export function _resetKeyCacheForTest() {
  cachedKey = null;
  cachedKeyOnce = false;
}

// ── agent 输出 (jsonl entry) 的出口消毒 ────────────────────────────────────
// agent 生成的 Bash 命令 / 工具参数 / 结果回显里可能带明文密码 (如 sshpass -p 'xxx')。
// 这些内容在发给前端展示前, 递归扫描 entry 的字符串字段, 把命中的敏感片段加密成
// 占位符 — 前端统一渲染为 🔒。深度与数组长度有上限, 防恶意超大 entry 拖垮请求。

const SANITIZE_MAX_DEPTH = 8;
const SANITIZE_MAX_ITEMS = 400;

/**
 * 递归消毒 jsonl entry (或任意 JSON 值): 字符串字段中的敏感片段 → 加密占位符。
 * 原地修改并返回同一对象 (调用方持有的引用同步更新)。
 */
export function sanitizeEntrySecrets(entry: any, depth = 0): any {
  if (entry == null || depth > SANITIZE_MAX_DEPTH) return entry;
  if (typeof entry === 'string') {
    if (!entry || entry.length > 200_000) return entry; // 超长串跳过 (性能保护)
    const r = detectAndEncrypt(entry);
    return r.encryptedCount > 0 ? r.text : entry;
  }
  if (Array.isArray(entry)) {
    const n = Math.min(entry.length, SANITIZE_MAX_ITEMS);
    for (let i = 0; i < n; i += 1) entry[i] = sanitizeEntrySecrets(entry[i], depth + 1);
    return entry;
  }
  if (typeof entry === 'object') {
    const keys = Object.keys(entry);
    const n = Math.min(keys.length, SANITIZE_MAX_ITEMS);
    for (let i = 0; i < n; i += 1) {
      const k = keys[i];
      try { entry[k] = sanitizeEntrySecrets(entry[k], depth + 1); } catch { /* 单字段失败不影响其余 */ }
    }
    return entry;
  }
  return entry;
}
