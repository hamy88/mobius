/**
 * 桌面客户端产物同步服务 (多服务器友好)。
 *
 * 每台服务器独立从 GitHub Release 拉取最新 zip + manifest.json 到 mobius/desktop-builds/。
 * 被三种方式调用:
 *   1. cron 定时:  server.js 启动后每 30 分钟自动跑一次
 *   2. webhook:   POST /api/webhook/desktop-sync (CI 发版后立即触发, 加速同步)
 *   3. CLI 手动:   node tools/sync-desktop-builds.js
 *
 * 幂等: 本地文件 size 与 GitHub Release asset 一致则跳过; manifest.json 每次覆盖。
 * 多服务器: 每台独立运行, 无需互相知道对方, 各自从同一个 GitHub Release 拉取即可。
 */

const fs = require("node:fs");
const path = require("node:path");
const https = require("node:https");

const DESKTOP_BUILDS_DIR = path.join(__dirname, "..", "..", "desktop-builds");
const MOBILE_BUILDS_DIR = path.join(__dirname, "..", "..", "mobile-builds");
const GITHUB_API = "https://api.github.com";
const REPO = "mobius-system/mobius";

/**
 * 下载单个文件 (支持自动跟随重定向, 幂等: size 一致跳过).
 */
function downloadFile(url, destPath, expectedSize) {
  return new Promise((resolve, reject) => {
    // 幂等检查
    try {
      const st = fs.statSync(destPath);
      if (st.size === expectedSize) {
        return resolve({ skipped: true, file: path.basename(destPath), size: st.size });
      }
    } catch (_) { /* 文件不存在, 继续 */ }

    const file = fs.createWriteStream(destPath);
    const timeout = setTimeout(() => {
      file.close();
      fs.unlink(destPath, () => {});
      reject(new Error("Download timeout"));
    }, 180000);

    https.get(url, { headers: { "User-Agent": "Mobius-Desktop-Sync/1.0" } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        file.close();
        clearTimeout(timeout);
        return downloadFile(res.headers.location, destPath, expectedSize).then(resolve, reject);
      }
      if (res.statusCode !== 200) {
        file.close();
        clearTimeout(timeout);
        fs.unlink(destPath, () => {});
        return reject(new Error(`HTTP ${res.statusCode}`));
      }
      res.pipe(file);
      file.on("finish", () => {
        clearTimeout(timeout);
        file.close();
        resolve({ skipped: false, file: path.basename(destPath), size: file.bytesWritten });
      });
      file.on("error", (err) => { clearTimeout(timeout); file.close(); fs.unlink(destPath, () => {}); reject(err); });
    }).on("error", (err) => { clearTimeout(timeout); file.close(); fs.unlink(destPath, () => {}); reject(err); });
  });
}

/**
 * 调用 GitHub REST API 获取最新非 draft Release 的 asset 列表。
 * token 可选 (public repo 不需要; 配了可提高 rate limit: 60→5000 req/h)。
 */
function fetchLatestRelease(token) {
  return new Promise((resolve, reject) => {
    const headers = { "User-Agent": "Mobius-Desktop-Sync/1.0", "Accept": "application/vnd.github+json" };
    if (token) headers["Authorization"] = `token ${token}`;

    const url = `${GITHUB_API}/repos/${REPO}/releases/latest`;
    https.get(url, { headers }, (res) => {
      if (res.statusCode !== 200) {
        let body = "";
        res.on("data", (d) => body += d);
        res.on("end", () => reject(new Error(`GitHub API ${res.statusCode}: ${body.slice(0, 200)}`)));
        return;
      }
      let body = "";
      res.on("data", (d) => body += d);
      res.on("end", () => {
        try { resolve(JSON.parse(body)); }
        catch (e) { reject(new Error(`Invalid JSON: ${e.message}`)); }
      });
    }).on("error", reject);
  });
}

/**
 * 核心同步逻辑: 从 GitHub Release 拉取所有桌面端产物 + manifest.json。
 * 返回 { ok, tag, version, downloaded, skipped, failed, elapsed, files[] }。
 */
async function syncDesktopBuilds(options = {}) {
  const {
    token: ghToken = process.env.GITHUB_TOKEN_DESKTOP || process.env.GITHUB_TOKEN || null,
    log = console.log,
  } = options;

  const startTime = Date.now();
  log(`[desktop-sync] Checking latest release from ${REPO}...`);

  // 1. 获取最新 desktop Release (tag 前缀 desktop-v; 不能用 releases/latest —
  //    mobile-v 等 Release 会抢占全局 latest 位置导致桌面端拉错资产)
  const release = await fetchLatestReleaseByTagPrefix("desktop-v", ghToken);
  const assets = release.assets || [];
  log(`[desktop-sync] Latest: ${release.tag_name}, ${assets.length} assets`);

  if (assets.length === 0) {
    return { ok: false, error: "No assets in release", tag: release.tag_name };
  }

  // 2. 确保目录存在
  fs.mkdirSync(DESKTOP_BUILDS_DIR, { recursive: true });

  const results = [];

  // 3. 下载所有资产 (manifest.json 每次覆盖; zip 按 size 幂等)
  for (const asset of assets) {
    const destPath = path.join(DESKTOP_BUILDS_DIR, asset.name);
    try {
      const r = await downloadFile(asset.browser_download_url, destPath, asset.size);
      results.push({ ...r });
      if (!r.skipped) {
        log(`[desktop-sync]   ↓ ${r.file} (${(r.size / 1024 / 1024).toFixed(1)} MB)`);
      }
    } catch (err) {
      log(`[desktop-sync]   ✗ ${asset.name}: ${err.message}`);
      results.push({ file: asset.name, error: err.message });
    }
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
  const downloaded = results.filter(r => !r.error && !r.skipped).length;
  const skipped = results.filter(r => r.skipped).length;
  const failed = results.filter(r => r.error).length;

  // 4. 清理旧版本文件 (不在当前 release 中的 zip, 保留 manifest.json)
  const currentZipNames = new Set(assets.map(a => a.name).filter(n => n.endsWith(".zip")));
  try {
    for (const entry of fs.readdirSync(DESKTOP_BUILDS_DIR)) {
      if (entry === "manifest.json") continue;
      if (entry.endsWith(".zip") && !currentZipNames.has(entry)) {
        const oldPath = path.join(DESKTOP_BUILDS_DIR, entry);
        fs.unlinkSync(oldPath);
        log(`[desktop-sync]   ✕ removed old: ${entry}`);
      }
    }
  } catch (_) { /* 清理失败不影响 */ }

  // 5. 若 Release 未包含 manifest.json (旧 CI), 则从本地 zip 重新生成
  const hasManifest = assets.some(a => a.name === "manifest.json");
  if (!hasManifest && downloaded > 0) {
    try {
      const version = (release.tag_name || "").replace("desktop-v", "");
      const builds = [];
      for (const name of currentZipNames) {
        const filePath = path.join(DESKTOP_BUILDS_DIR, name);
        try {
          const st = fs.statSync(filePath);
          const sha256 = require("crypto").createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
          // 从文件名解析: mobius-desktop-0.0.19-win-x64.zip
          const rest = name.replace(/^mobius-desktop-[^-]+-[^-]+-/, "").replace(".zip", ""); // mac-arm64
          const sep = rest.lastIndexOf("-");
          const platform = sep > 0 ? rest.slice(0, sep) : rest;
          const arch = sep > 0 ? rest.slice(sep + 1) : "x64";
          const format = name.endsWith(".dmg") ? "dmg" : "zip";
          builds.push({ platform, arch, format, file: name, size: st.size, sha256 });
        } catch (_) { /* skip */ }
      }
      if (builds.length > 0) {
        const manifest = {
          version,
          generatedAt: new Date().toISOString(),
          builds: builds.sort((a, b) => `${a.platform}-${a.arch}`.localeCompare(`${b.platform}-${b.arch}`)),
        };
        fs.writeFileSync(path.join(DESKTOP_BUILDS_DIR, "manifest.json"), JSON.stringify(manifest, null, 2));
        log(`[desktop-sync]   ✓ generated manifest.json (${builds.length} builds, from local zip)`);
      }
    } catch (e) {
      log(`[desktop-sync]   ⚠ manifest.json generation failed: ${e.message}`);
    }
  }

  log(`[desktop-sync] Done ${elapsed}s: ${downloaded} new, ${skipped} cached, ${failed} failed`);

  return {
    ok: failed === 0,
    tag: release.tag_name,
    version: (release.tag_name || "").replace("desktop-v", ""),
    downloaded,
    skipped,
    failed,
    elapsed: `${elapsed}s`,
    dest: DESKTOP_BUILDS_DIR,
    files: results,
  };
}

/** 从 tag 或 APK 文件名里提取版本号("mobile-v0.3.0" / "mobius-mobile-0.3.0-android-arm64.apk" → "0.3.0")。 */
function compareVersionOf(name) {
  const m = /(\d+)\.(\d+)\.(\d+)/.exec(String(name || ""));
  return m ? `${m[1]}.${m[2]}.${m[3]}` : null;
}

/** 语义化版本比较: a>b 返回 1, a<b 返回 -1, 相等/不可比返回 0。 */
function compareVersions(a, b) {
  const pa = String(a).split(".").map(Number);
  const pb = String(b).split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

/**
 * 按 tag 前缀查最新 Release (releases/latest 只返回全局最新, mobile 与 desktop 各自独立发版).
 * 列出全部 releases (per_page=20), 取 tag_name 以 prefix 开头且非 draft/prerelease 的第一个 (列表按创建时间倒序).
 */
function fetchLatestReleaseByTagPrefix(prefix, token) {
  return new Promise((resolve, reject) => {
    const headers = { "User-Agent": "Mobius-Desktop-Sync/1.0", "Accept": "application/vnd.github+json" };
    if (token) headers["Authorization"] = `token ${token}`;

    const url = `${GITHUB_API}/repos/${REPO}/releases?per_page=20`;
    https.get(url, { headers }, (res) => {
      if (res.statusCode !== 200) {
        let body = "";
        res.on("data", (d) => body += d);
        res.on("end", () => reject(new Error(`GitHub API ${res.statusCode}: ${body.slice(0, 200)}`)));
        return;
      }
      let body = "";
      res.on("data", (d) => body += d);
      res.on("end", () => {
        try {
          const list = JSON.parse(body);
          const hit = (Array.isArray(list) ? list : []).find(
            (r) => !r.draft && !r.prerelease && typeof r.tag_name === "string" && r.tag_name.startsWith(prefix)
          );
          if (!hit) return reject(new Error(`No release with tag prefix '${prefix}'`));
          resolve(hit);
        } catch (e) { reject(new Error(`Invalid JSON: ${e.message}`)); }
      });
    }).on("error", reject);
  });
}

/**
 * 解析移动端同步源仓库: MOBILE_SYNC_REPO 显式指定 > 本仓库 git origin (fork 部署自产自销
 * 自己 CI 的 mobile release) > 上游官方仓。
 * fork 部署的 mobile release 是自家 CI 发的 pre-release (softprops/action-gh-release 默认
 * prerelease=true), 与上游"正式版才对外分发"的语义不同, 因此非默认仓时把 prerelease 也纳入
 * 候选, 并按最大版本号(而非创建顺序)选择, 避免旧 pre-release 抢位。
 */
function resolveMobileSyncRepo() {
  const explicit = (process.env.MOBILE_SYNC_REPO || "").trim();
  if (explicit) return { repo: explicit, includePrerelease: true, source: "env MOBILE_SYNC_REPO" };
  try {
    // 从 services 目录向上找 git 仓库根的 .git/config (仓库根可能是本目录, 也可能是更上层)
    let gitConfig = null;
    for (let dir = path.join(__dirname, "..", ".."); ; dir = path.dirname(dir)) {
      const cfg = path.join(dir, ".git", "config");
      if (fs.existsSync(cfg)) { gitConfig = fs.readFileSync(cfg, "utf8"); break; }
      const parent = path.dirname(dir);
      if (parent === dir) break;
    }
    if (!gitConfig) throw new Error("no .git/config found");
    // 在 [remote "origin"] section 内找 url 行 (section 内键顺序不保证, 逐行扫描到下一个 section 为止)
    const lines = gitConfig.split("\n");
    let inOrigin = false;
    for (const line of lines) {
      const sec = /^\s*\[/.exec(line);
      if (sec) { inOrigin = /\[remote "origin"\]/.test(line); continue; }
      if (!inOrigin) continue;
      const m = /^\s*url\s*=\s*(?:git@github\.com:|https:\/\/github\.com\/)([\w.-]+\/[\w.-]+?)(?:\.git)?\s*$/.exec(line);
      if (m && m[1] && m[1] !== REPO) {
        return { repo: m[1], includePrerelease: true, source: "git origin" };
      }
    }
  } catch (_) { /* .git 不存在 (纯部署拷贝) → 回落上游 */ }
  return { repo: REPO, includePrerelease: false, source: "default upstream" };
}

/** 列出某仓库全部 releases (per_page=20, 匿名可达; token 可选提额)。 */
function listReleases(repo, token) {
  return new Promise((resolve, reject) => {
    const headers = { "User-Agent": "Mobius-Desktop-Sync/1.0", "Accept": "application/vnd.github+json" };
    if (token) headers["Authorization"] = `token ${token}`;

    const url = `${GITHUB_API}/repos/${repo}/releases?per_page=20`;
    https.get(url, { headers }, (res) => {
      if (res.statusCode !== 200) {
        let body = "";
        res.on("data", (d) => body += d);
        res.on("end", () => reject(new Error(`GitHub API ${res.statusCode}: ${body.slice(0, 200)}`)));
        return;
      }
      let body = "";
      res.on("data", (d) => body += d);
      res.on("end", () => {
        try { resolve(JSON.parse(body)); }
        catch (e) { reject(new Error(`Invalid JSON: ${e.message}`)); }
      });
    }).on("error", reject);
  });
}

/**
 * 移动端同步: 从 mobile-v Release 拉取 APK + manifest.json 到 mobius/mobile-builds/.
 * 与桌面端同构: 幂等 (size 一致跳过), 旧 APK 清理仅限 mobius-mobile-* 模式。
 * Release 不存在时静默跳过 (移动端发版晚于桌面端属正常), 不影响桌面端结果。
 *
 * 与桌面端的差异 (fork 部署 2026-09-10 治本):
 * 1. 仓库源: resolveMobileSyncRepo() — fork 部署同步自己 CI 的 release, 不再固定上游
 *    (此前上游 0.1.19 每小时覆盖回本地, fork 的 0.2.0/0.3.0 反而被当旧文件删掉)。
 * 2. 单调性保护: 候选 release 版本 < 本地已有最大 APK 版本 → 本轮跳过下载与清理,
 *    本地状态只进不退 (防配置回退/上游滞后把旧版拉回来)。
 * 3. manifest.json 不再盲目覆盖: release 带的 manifest 只在上游默认源且本地无自维护
 *    版本时落地; 本目录 manifest 由部署侧维护多版本清单, release manifest 不含 sha256
 *    且只有单版本, 覆盖会丢失 0.2.0/0.3.0 行。
 * 4. 清理白名单: manifest.json builds[] 里列出的文件永不删 (保留哪些版本是运营决策,
 *    sync 只负责清孤儿文件)。
 */
async function syncMobileBuilds(options = {}) {
  const {
    token: ghToken = process.env.GITHUB_TOKEN_DESKTOP || process.env.GITHUB_TOKEN || null,
    log = console.log,
  } = options;

  const startTime = Date.now();
  const { repo, includePrerelease, source: repoSource } = resolveMobileSyncRepo();

  let release;
  try {
    const list = await listReleases(repo, ghToken);
    const candidates = (Array.isArray(list) ? list : []).filter(
      (r) => !r.draft && (!r.prerelease || includePrerelease)
        && typeof r.tag_name === "string" && r.tag_name.startsWith("mobile-v")
    );
    // 按最大版本号选 (创建顺序最新的 pre-release 不一定是最高版本)
    release = candidates.reduce((best, r) => {
      if (!best) return r;
      const rv = compareVersionOf(r.tag_name) || "0";
      const bv = compareVersionOf(best.tag_name) || "0";
      return compareVersions(rv, bv) > 0 ? r : best;
    }, null);
    if (!release) throw new Error(`No release with tag prefix 'mobile-v' in ${repo}`);
  } catch (e) {
    log(`[mobile-sync] skip: ${e.message}`);
    return { ok: true, skipped: true, reason: e.message };
  }
  const assets = release.assets || [];
  log(`[mobile-sync] Latest: ${release.tag_name} from ${repo} (${repoSource}), ${assets.length} assets`);

  const apkAssets = assets.filter((a) => a.name.endsWith(".apk"));
  if (apkAssets.length === 0) {
    return { ok: true, skipped: true, reason: "No apk assets in release" };
  }

  // 单调性保护: 本地已有更高版本 APK 时, 本轮不下载/不清理/不动 manifest。
  const releaseVersion = compareVersionOf(release.tag_name);
  let maxLocalVersion = null;
  try {
    for (const entry of fs.readdirSync(MOBILE_BUILDS_DIR)) {
      if (!entry.startsWith("mobius-mobile-") || !entry.endsWith(".apk")) continue;
      const v = compareVersionOf(entry);
      if (v && (!maxLocalVersion || compareVersions(v, maxLocalVersion) > 0)) maxLocalVersion = v;
    }
  } catch (_) { /* 目录不存在 → 无本地版本 */ }
  if (maxLocalVersion && releaseVersion && compareVersions(maxLocalVersion, releaseVersion) > 0) {
    log(`[mobile-sync]   ⏩ local ${maxLocalVersion} > release ${releaseVersion} (${repo}), skip whole cycle`);
    return {
      ok: true, skipped: true, reason: `local ${maxLocalVersion} newer than release ${releaseVersion}`,
      tag: release.tag_name, repo, maxLocalVersion,
    };
  }

  fs.mkdirSync(MOBILE_BUILDS_DIR, { recursive: true });
  const results = [];
  for (const asset of apkAssets) {
    const destPath = path.join(MOBILE_BUILDS_DIR, asset.name);
    try {
      const r = await downloadFile(asset.browser_download_url, destPath, asset.size);
      results.push({ ...r });
      if (!r.skipped) log(`[mobile-sync]   ↓ ${r.file} (${(r.size / 1024 / 1024).toFixed(1)} MB)`);
    } catch (err) {
      log(`[mobile-sync]   ✗ ${asset.name}: ${err.message}`);
      results.push({ file: asset.name, error: err.message });
    }
  }

  // 清理旧版本 APK (mobius-mobile-*.apk 且不在当前 release 中; 备份目录 _backup-* 不动)
  // 规则(2026-09-10 治本重写):
  //   a) manifest.json builds[] 列出的文件是运营侧保留清单 → 永不删;
  //   b) 比当前 release 版本更新的本地 APK (自建新版) → 保留;
  //   c) 其余 = 孤儿旧文件 → 删。
  // 上游 0.1.19 之所以曾被反复拉回: 源仓库固定上游 + 非 prerelease 过滤, 上游 0.1.19 恰是
  // 唯一命中; 换 fork 源后 0.1.19 不再被下载, 若本地留着且在 manifest 白名单里也不会被清。
  const manifestKeep = new Set();
  try {
    const localManifest = JSON.parse(fs.readFileSync(path.join(MOBILE_BUILDS_DIR, "manifest.json"), "utf8"));
    for (const b of localManifest.builds || []) if (b.file) manifestKeep.add(b.file);
  } catch (_) { /* manifest 缺失/损坏 → 空白名单 */ }
  const currentApkNames = new Set(apkAssets.map((a) => a.name));
  const currentVersion = compareVersionOf(release.tag_name);
  try {
    for (const entry of fs.readdirSync(MOBILE_BUILDS_DIR)) {
      if (entry.startsWith("mobius-mobile-") && entry.endsWith(".apk") && !currentApkNames.has(entry)) {
        if (manifestKeep.has(entry)) {
          log(`[mobile-sync]   ⏩ kept (manifest-listed): ${entry}`);
          continue;
        }
        const localVersion = compareVersionOf(entry);
        // 本地版本更新(或无法比较)时保留, 只清理确定更旧的。
        if (localVersion && currentVersion && compareVersions(localVersion, currentVersion) > 0) {
          log(`[mobile-sync]   ⏩ kept newer local: ${entry} (local ${localVersion} > release ${currentVersion})`);
          continue;
        }
        fs.unlinkSync(path.join(MOBILE_BUILDS_DIR, entry));
        log(`[mobile-sync]   ✕ removed old: ${entry}`);
      }
    }
  } catch (_) { /* 清理失败不影响 */ }

  // 自动补 manifest 行: 新落盘的 APK 若不在本地 manifest 里, 追加条目 (含实测 size/sha256),
  // 并把顶层 version 推进到更高版本。只增不删, 既有行 (含人工维护的 source/versionCode) 不动。
  // 目的: 0.4.0 发布后 cron 自动拉 APK + 自动登记, 无需人工改 manifest。
  try {
    const manifestPath = path.join(MOBILE_BUILDS_DIR, "manifest.json");
    let manifest = null;
    try {
      manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    } catch (_) { manifest = null; }
    if (!manifest || !Array.isArray(manifest.builds)) {
      manifest = { version: releaseVersion || "", platform: "android", generatedAt: new Date().toISOString(), builds: [] };
    }
    const listed = new Set(manifest.builds.map((b) => b.file));
    const crypto = require("node:crypto");
    let added = 0;
    for (const r of results) {
      if (r.error || listed.has(r.file)) continue;
      try {
        const p = path.join(MOBILE_BUILDS_DIR, r.file);
        const buf = fs.readFileSync(p);
        const v = compareVersionOf(r.file) || releaseVersion || "";
        manifest.builds.push({
          platform: "android",
          arch: /armeabi/.test(r.file) ? "armeabi-v7a" : "arm64",
          format: "apk",
          file: r.file,
          size: buf.length,
          version: v,
          sha256: crypto.createHash("sha256").update(buf).digest("hex"),
          source: `github-release ${release.tag_name} auto-synced`,
        });
        added++;
      } catch (_) { /* 单文件失败不影响 */ }
    }
    if (added > 0 || (releaseVersion && compareVersions(releaseVersion, manifest.version || "0") > 0)) {
      if (releaseVersion && compareVersions(releaseVersion, manifest.version || "0") > 0) manifest.version = releaseVersion;
      manifest.generatedAt = new Date().toISOString();
      fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
      log(`[mobile-sync]   ✓ manifest.json: +${added} entries, version → ${manifest.version}`);
    }
  } catch (e) {
    log(`[mobile-sync]   ⚠ manifest.json auto-update failed: ${e.message}`);
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
  const downloaded = results.filter((r) => !r.error && !r.skipped).length;
  const skipped = results.filter((r) => r.skipped).length;
  const failed = results.filter((r) => r.error).length;
  log(`[mobile-sync] Done ${elapsed}s: ${downloaded} new, ${skipped} cached, ${failed} failed`);

  return {
    ok: failed === 0,
    tag: release.tag_name,
    version: (release.tag_name || "").replace("mobile-v", ""),
    downloaded, skipped, failed,
    elapsed: `${elapsed}s`,
    dest: MOBILE_BUILDS_DIR,
    files: results,
  };
}

module.exports = {
  syncDesktopBuilds,
  syncMobileBuilds,
  fetchLatestRelease,
  fetchLatestReleaseByTagPrefix,
  downloadFile,
  DESKTOP_BUILDS_DIR,
  MOBILE_BUILDS_DIR,
};
