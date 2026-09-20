// =====================================================================
// mobile-ota.ts — Mobius Mobile OTA manifest endpoint.
//
// 路径: GET /api/mobile/ota/manifest.json
// 返回: OtaManifest (commonMain OtaManifest 序列化 schema) + changelog_items
//
// 数据源: mobius/mobile-builds/manifest.json (sync-desktop-builds.js 维护的本地 APK 清单).
//   现状 schema:
//     { "version": "0.4.1", "platform": "android", "generatedAt": "...",
//       "builds": [ { "platform": "android", "arch": "arm64|v7a", "format": "apk",
//                     "file": "...", "size": ..., "version": "...",
//                     "versionCode": ..., "sha256": "...", "source": "..." }, ... ] }
//   含多版本 builds (0.4.0/0.4.1/0.3.1/0.2.0); 这里取 version 字段最大的版本对应 builds[].
//
// changelog_items 来源: 同步读 mobius/mobile/CHANGELOG.md, 用 markdown 解析器提取对应版本段
// (## [X.Y.Z] - YYYY-MM-DD)的 ### 分类 + 列表项, 输出与客户端 ChangelogItem 一致:
//   { type: "Feature" | "Fix" | "Breaking", text: string }.
//   解析失败 → 返回空数组, 不阻塞主流程.
//
// OtaManifest schema (客户端):
//   { "version": "x.y.z", "version_code": N, "channel": "stable",
//     "released_at": "ISO8601", "min_supported_version": null,
//     "hard_block_bypassable": true,
//     "android": { "package_name": "com.mobius.momo", "min_sdk": 24,
//                  "target_sdk": 34, "signature_scheme": ["v2","v3"],
//                  "abi_filters": ["arm64-v8a","armeabi-v7a"] },
//     "delta_enabled": false,
//     "release_notes_url": "https://github.com/hamy88/mobius/releases/tag/mobile-v<x.y.z>",
//     "changelog_items": [ { "type": "...", "text": "..." }, ... ],
//     "builds": [ { "platform":"android", "abi":"arm64-v8a",
//                   "version":"x.y.z", "version_code":N,
//                   "url":"/mobile-builds/<file>", "size": ..., "sha256": "..." }, ... ] }
//
// CORS / Cache-Control: 公开 endpoint (客户端 Android App 跨域调用), no-cache 保证即拿即用.
//
// 写入 mount 位置: server.js 在 /api/assistant 之后挂 app.use('/api/mobile/ota', mobileOtaRoutes).
// =====================================================================
import express from 'express';
import path from 'path';
import fs from 'fs';

const router = express.Router();

// mobius/mobile-builds/ 在仓库根, 服务进程 cwd 通常为仓库根.
// 用 __dirname (routes/) 向上两级 = mobius/, 再 ../mobile-builds; 也兼容 cwd=仓库根的兜底.
const MOBILE_BUILDS_DIR = path.join(__dirname, '..', '..', 'mobile-builds');
const MOBILE_BUILDS_MANIFEST = path.join(MOBILE_BUILDS_DIR, 'manifest.json');
// CHANGELOG.md 路径: mobius/mobile/CHANGELOG.md. 仅只读; 解析失败时返回空 changelog_items.
const MOBILE_CHANGELOG_PATH = path.join(__dirname, '..', '..', 'mobile', 'CHANGELOG.md');

interface MobileBuildsManifest {
  version?: string;
  platform?: string;
  generatedAt?: string;
  builds: Array<{
    platform?: string;
    arch?: string;
    format?: string;
    file?: string;
    size?: number;
    version?: string;
    versionCode?: number;
    sha256?: string;
    source?: string;
  }>;
}

// 简单 SemVer 比对: 返回 a>b?1 : a<b?-1 : 0. 不解析 prerelease/build metadata (本项目版本号 0.4.0/0.4.1 已足够).
function compareSemver(a: string, b: string): number {
  const pa = a.split('.').map((n) => parseInt(n, 10) || 0);
  const pb = b.split('.').map((n) => parseInt(n, 10) || 0);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const na = pa[i] ?? 0;
    const nb = pb[i] ?? 0;
    if (na !== nb) return na > nb ? 1 : -1;
  }
  return 0;
}

// arch → ABI 名映射 (manifest.json 用 arm64/v7a 短名, OtaManifest 用 Android 标准 ABI 名).
function archToAbi(arch: string): string | null {
  switch (arch) {
    case 'arm64':
      return 'arm64-v8a';
    case 'arm':
    case 'v7a':
    case 'armeabi-v7a':
      return 'armeabi-v7a';
    case 'x86':
      return 'x86';
    case 'x86_64':
      return 'x86_64';
    default:
      return null;
  }
}

function readMobileBuildsManifest(): MobileBuildsManifest | null {
  try {
    const raw = fs.readFileSync(MOBILE_BUILDS_MANIFEST, 'utf8');
    const parsed = JSON.parse(raw) as MobileBuildsManifest;
    if (!parsed || !Array.isArray(parsed.builds)) return null;
    return parsed;
  } catch (e) {
    // 文件不存在 (未跑过 sync) / JSON 损坏 → 返回 null 让上层 503, 客户端 fallback 到 GitHub.
    return null;
  }
}

function pickLatestAndroidBuilds(parsed: MobileBuildsManifest): {
  version: string;
  versionCode: number;
  releasedAt: string;
  builds: MobileBuildsManifest['builds'];
} | null {
  const androidBuilds = parsed.builds.filter(
    (b) => b.platform === 'android' && b.format === 'apk' && typeof b.version === 'string' && b.version.length > 0,
  );
  if (androidBuilds.length === 0) return null;
  // 按 version 字段取最大, 同版本下保留全部 ABI builds.
  const versions = Array.from(new Set(androidBuilds.map((b) => b.version as string))).sort(compareSemver);
  const latestVersion = versions[versions.length - 1];
  const latestBuilds = androidBuilds.filter((b) => b.version === latestVersion);
  if (latestBuilds.length === 0) return null;
  const maxVersionCode = latestBuilds.reduce(
    (acc, b) => Math.max(acc, typeof b.versionCode === 'number' ? b.versionCode : 0),
    0,
  );
  return {
    version: latestVersion,
    versionCode: maxVersionCode,
    // 优先 generatedAt (manifest 同步时间); 其次用本机当前时间; 没有 generatedAt 时退到当前时间.
    releasedAt: parsed.generatedAt || new Date().toISOString(),
    builds: latestBuilds,
  };
}

// ===== 0.4.3: CHANGELOG.md 解析 → changelog_items 数组 =====
//
// 输入: CHANGELOG.md 全文 + 目标版本 (e.g. "0.4.3")
// 输出: { type: "Feature"|"Fix"|"Breaking", text: string }[], 与客户端 ChangelogItem 字段一一对应.
//
// 解析规则(对齐客户端 ChangelogParser.kt):
// 1. 找到 `## [<targetVersion>]` 标题段, 截取到下一个 `## [` 或文件末尾;
// 2. 段内 `### xxx` 作为分类边界:
//    - "修复"/"Bug Fix"/"Fix" → Fix
//    - "破坏"/"Breaking"      → Breaking
//    - 其他(默认含 "新增")      → Feature
// 3. 段内 `- xxx` 列表项(可嵌套子项)合并为一条 text;
// 4. 非列表项 / 标题 / 空行 跳过.
//
// 文件不存在或解析抛错 → 返回空数组(不阻塞 OTA 主流程).
type ChangelogItemType = 'Feature' | 'Fix' | 'Breaking';

interface ChangelogItemOut {
  type: ChangelogItemType;
  text: string;
}

function classifySection(header: string): ChangelogItemType {
  const h = header.toLowerCase();
  if (h.includes('breaking') || h.includes('破坏') || h.includes('breaking change')) return 'Breaking';
  if (h.includes('fix') || h.includes('修复') || h.includes('bug')) return 'Fix';
  return 'Feature';
}

function parseChangelogForVersion(version: string): ChangelogItemOut[] {
  let raw: string;
  try {
    raw = fs.readFileSync(MOBILE_CHANGELOG_PATH, 'utf8');
  } catch (e) {
    // CHANGELOG.md 缺失 → 0.4.3 之前没有该 endpoint 解析 changelog 的能力; 返回空数组兜底.
    return [];
  }

  // 匹配 `## [0.4.3]` 段(可带日期 `- 2026-09-20`); 严格用 [version] 防止误命中 e.g. 0.4.30.
  const headerRegex = new RegExp(`^##\\s*\\[${version.replace(/[.+*?^$()|[\\]\\\\]/g, '\\\\$&')}\\]`, 'm');
  const headerMatch = headerRegex.exec(raw);
  if (!headerMatch) return [];

  // 截取该段到下一个 `## [` 之前.
  const startIdx = headerMatch.index + headerMatch[0].length;
  const restAfter = raw.slice(startIdx);
  const nextHeaderMatch = /^##\s*\[/m.exec(restAfter);
  const section = nextHeaderMatch
    ? restAfter.slice(0, nextHeaderMatch.index)
    : restAfter;

  const items: ChangelogItemOut[] = [];
  let currentType: ChangelogItemType = 'Feature';

  // 合并相邻同 type 条目(对齐 Kotlin 实现 appendOrMerge).
  function appendOrMerge(type: ChangelogItemType, text: string): void {
    const cleaned = text.trim();
    if (!cleaned) return;
    const last = items[items.length - 1];
    if (last && last.type === type) {
      last.text = last.text.endsWith('\n') ? last.text + cleaned : last.text + '\n' + cleaned;
    } else {
      items.push({ type, text: cleaned });
    }
  }

  for (const rawLine of section.split('\n')) {
    const line = rawLine.trim();
    if (!line) continue;

    // ### 标题作为分类边界
    if (line.startsWith('### ')) {
      currentType = classifySection(line.slice(4).trim());
      continue;
    }

    // 跳过 ## 二级标题(已是 section 自身边界, 段内不应再有)
    if (line.startsWith('## ')) continue;

    // 跳过 # 一级 / 其他 markdown 元字符
    if (line.startsWith('#')) continue;

    // 列表项 `- xxx` 或 `* xxx` 或 `• xxx`
    let body = line;
    if (body.startsWith('- ')) body = body.slice(2);
    else if (body.startsWith('* ')) body = body.slice(2);
    else if (body.startsWith('• ')) body = body.slice(2);
    body = body.trim();
    if (!body) continue;

    appendOrMerge(currentType, body);
  }

  return items;
}

router.get('/manifest.json', (_req: express.Request, res: express.Response) => {
  // CORS / no-cache: 客户端 Android App 跨域拉取; 应保证即时拿到最新 manifest, 不允许中间缓存.
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
  res.setHeader('Pragma', 'no-cache');

  const parsed = readMobileBuildsManifest();
  if (!parsed) {
    res.status(503).json({
      error: 'mobile-builds manifest unavailable',
      detail: `${MOBILE_BUILDS_MANIFEST} not found or invalid JSON`,
    });
    return;
  }

  const picked = pickLatestAndroidBuilds(parsed);
  if (!picked) {
    res.status(503).json({
      error: 'no android builds in mobile-builds/manifest.json',
    });
    return;
  }

  const abiSet = new Set<string>();
  const otaBuilds = picked.builds
    .map((b) => {
      const abi = archToAbi(b.arch || '');
      if (abi) abiSet.add(abi);
      return {
        platform: 'android',
        abi: abi || (b.arch || ''),
        version: picked.version,
        version_code: picked.versionCode,
        // URL: 相对路径, 由 server.js 的 /mobile-builds 自建中间件 (createBuildsRouter) 提供.
        // 例 /mobile-builds/mobius-mobile-0.4.1-android-arm64.apk
        url: `/mobile-builds/${b.file || ''}`,
        size: typeof b.size === 'number' ? b.size : 0,
        sha256: b.sha256 || '',
      };
    })
    // 兜底: 若某条 build 没匹配到 ABI 但 file/sha256 还在, 仍保留 (客户端按 url 自助下载)
    .filter((b) => b.url.length > `/mobile-builds/`.length && b.sha256.length > 0);

  if (otaBuilds.length === 0) {
    res.status(503).json({ error: 'no usable builds (missing file or sha256)' });
    return;
  }

  // 0.4.3: 读 CHANGELOG.md 解析对应版本 changelog_items, 失败兜底空数组.
  const changelogItems = parseChangelogForVersion(picked.version);

  const otaManifest = {
    version: picked.version,
    version_code: picked.versionCode,
    channel: 'stable',
    released_at: picked.releasedAt,
    min_supported_version: null,
    hard_block_bypassable: true,
    android: {
      package_name: 'com.mobius.momo',
      min_sdk: 24,
      target_sdk: 34,
      signature_scheme: ['v2', 'v3'],
      abi_filters: Array.from(abiSet),
      // OtaAndroidSpec 还有 requestInstallPackages / postNotificationsRequired 字段, 客户端走默认 false, 此处省略.
    },
    delta_enabled: false,
    release_notes_url: `https://github.com/hamy88/mobius/releases/tag/mobile-v${picked.version}`,
    // 0.4.3 新增: changelog_items 与 OtaRelease.changelogItems 字段保持一致,
    // 客户端 OtaCheckUseCase.Show 携带, 用于弹窗"首条摘要" + 全屏 modal 渲染.
    changelog_items: changelogItems,
    builds: otaBuilds,
  };

  res.json(otaManifest);
});

export = router;