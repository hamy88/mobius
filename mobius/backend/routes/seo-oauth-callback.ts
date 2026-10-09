// =====================================================================
// seo-oauth-callback.ts — Google OAuth 公开回调接收端
//
// 路径: GET /api/seo/oauth-callback?code=...&state=...&scope=...
// 设计: Google OAuth 授权流程要求 redirect_uri 是公网可达 URL.
//   云机内部 127.0.0.1:33330 用户浏览器跳不进来, 所以把授权跳转改走
//   mobius 公开 URL (http://<host>:33316/api/seo/oauth-callback).
//   用户授权后浏览器跳回此 URL, mobius 在这里:
//     1. 从 query 拿 code
//     2. fetch POST :33330/api/google/exchange-code (内部地址, 同一云机)
//     3. 给浏览器返回 HTML 提示页 (成功/失败)
//
// 注: 此 handler 在 mobius 进程内, 与 :33330 seo 服务解耦.
//     :33330 只暴露 POST /api/google/exchange-code (内部接口).
//
// 写入 mount 位置: server.js 在 /api/mobile/ota 之后挂
//   app.use('/api/seo/oauth-callback', seoOauthCallbackRoutes).
// =====================================================================
import express from 'express';

const router = express.Router();

// :33330 内部地址. 此进程内 fetch, 不出公网.
const SEO_INTERNAL_BASE = process.env.SEO_INTERNAL_BASE || 'http://127.0.0.1:33330';
// 必须与 platforms/google/index.js 的 REDIRECT_URI_DEFAULT 完全一致,
// 因为 Google 后台校验 redirect_uri 必须与 auth-url 生成时一致.
const DEFAULT_REDIRECT_URI =
  process.env.SEO_OAUTH_REDIRECT_URI ||
  `http://${process.env.MOBIUS_PUBLIC_HOST || '192.168.2.147'}:${process.env.MOBIUS_PUBLIC_PORT || '33316'}/api/seo/oauth-callback`;

router.get('/', async (req, res) => {
  const code = typeof req.query.code === 'string' ? req.query.code : '';
  const error = typeof req.query.error === 'string' ? req.query.error : '';
  const errorDesc = typeof req.query.error_description === 'string' ? req.query.error_description : '';
  const scope = typeof req.query.scope === 'string' ? req.query.scope : '';

  // 用户在 Google 页面拒绝授权 / 后台 redirect_uri 没配
  if (error) {
    const msg = `Google 返回错误: ${error}${errorDesc ? ' — ' + errorDesc : ''}`;
    res
      .status(200)
      .send(renderHtml('Google OAuth 授权失败', msg, false, '请确认 Google Cloud Console → OAuth client → Authorized redirect URIs 已添加当前 URL, 然后重新发起授权.'));
    return;
  }

  if (!code) {
    res
      .status(400)
      .send(renderHtml('缺少 code 参数', 'Google 跳回时未携带 code. 通常意味着 Google 后台没把当前 URL 加进 Authorized redirect URIs.', false));
    return;
  }

  // fetch POST :33330 exchange-code (用默认 redirect_uri, 因为生成 auth-url 时用的就是这个值)
  let upstream: { ok: boolean; error?: string; expires_in?: number; scope?: string; token_type?: string };
  try {
    const r = await fetch(`${SEO_INTERNAL_BASE}/api/google/exchange-code`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, redirect_uri: DEFAULT_REDIRECT_URI }),
    });
    upstream = (await r.json()) as typeof upstream;
  } catch (e) {
    res
      .status(502)
      .send(renderHtml('无法连接 seo 服务', `fetch :33330/api/google/exchange-code 失败: ${(e as Error).message || String(e)}`, false, '检查 :33330 服务是否存活 (systemctl / pm2)'));
    return;
  }

  if (upstream?.ok) {
    const lines = [
      'refresh_token 已保存到 :33330 服务本地 (data/google-tokens.json).',
      '',
      `token_type: ${upstream.token_type || '(未知)'}`,
      `expires_in: ${upstream.expires_in || '(未知)'} 秒`,
      `scope: ${upstream.scope || scope || '(未知)'}`,
      '',
      '现在可以关闭此页, 回到 SEO 看板正常使用 Google 平台查询。',
    ];
    res.status(200).send(renderHtml('✓ Google OAuth 授权成功', lines.join('\n'), true));
  } else {
    res
      .status(500)
      .send(renderHtml('✗ Google OAuth 授权失败', upstream?.error || '未知错误', false, '常见原因: code 已被用过 / redirect_uri 不匹配 / 凭据过期. 重新发起授权即可.'));
  }
});

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string));
}

function renderHtml(title: string, body: string, success: boolean, hint?: string): string {
  const color = success ? '#3fb950' : '#f85149';
  const border = success ? '#3fb950' : '#f85149';
  const hintBlock = hint
    ? `<p class="hint">${escapeHtml(hint)}</p>`
    : '';
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
  body { font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; background:#0d1117; color:#c9d1d9; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; padding:16px; }
  .card { background:#161b22; border:1px solid ${border}; border-radius:8px; padding:24px 32px; max-width:560px; width:100%; }
  h1 { margin:0 0 16px; color:${color}; font-size:20px; }
  pre { background:#0d1117; padding:12px; border-radius:6px; overflow-x:auto; white-space:pre-wrap; word-break:break-all; font-size:13px; line-height:1.55; margin:0; }
  .hint { color:#8b949e; font-size:13px; margin:16px 0 0; }
  .back { margin-top:20px; font-size:13px; }
  .back a { color:#58a6ff; text-decoration:none; }
</style>
</head>
<body>
<div class="card">
  <h1>${escapeHtml(title)}</h1>
  <pre>${escapeHtml(body)}</pre>
  ${hintBlock}
  <p class="back"><a href="/extension/website-seo/">← 返回 SEO 看板</a></p>
</div>
</body>
</html>`;
}

export = router;