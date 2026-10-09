// 反代 /api/seo/oauth-callback → :33330/api/google/oauth-callback
// 用户从 Google 同意授权后跳转回这个地址（带 ?code=&state=&scope=）
// 转发到 :33330 让它 exchange-code 拿 refresh_token
const http = require('http');

const HTTP_PORT = process.env.SEO_HTTP_PORT || '33330';

module.exports = function (req, res) {
  // 把 query 串过去
  const qs = req.url && req.url.includes('?') ? req.url.split('?')[1] : '';
  const options = {
    host: '127.0.0.1',
    port: HTTP_PORT,
    path: `/api/google/oauth-callback${qs ? '?' + qs : ''}`,
    method: 'GET',
    timeout: 25000,
  };

  const proxyReq = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode, proxyRes.headers);
    proxyRes.pipe(res);
  });
  proxyReq.on('error', (e) => {
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`bad gateway: ${e.message}`);
  });
  proxyReq.on('timeout', () => {
    proxyReq.destroy(new Error('timeout'));
  });
  proxyReq.end();
};