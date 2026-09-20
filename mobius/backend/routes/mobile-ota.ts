// =====================================================================
// mobile-ota.ts — Mobius Mobile OTA manifest endpoint.
//
// 路径: GET /api/mobile/ota/manifest.json
// 返回: OtaManifest (commonMain OtaManifest 序列化 schema)
//
// 数据源: mobius/mobile-builds/manifest.json (sync-desktop-builds.js 维护的本地 APK 清单).
//   现状 schema:
//     { "version": "0.4.1", "platform": "android", "generatedAt": "...",
//       "builds": [ { "platform": "android", "arch": "arm64|v7a", "format": "apk",
//                     "file": "...", "size": ..., "version": "...",
//                     "versionCode": ..., "sha256": "...", "source": "..." }, ... ] }
//   含多版本 builds (0.4.0/0.4.1/0.3.1/0.2.0); 这里取 version 字段最大的版本对应 builds[].
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
    builds: otaBuilds,
  };

  res.json(otaManifest);
});

export = router;