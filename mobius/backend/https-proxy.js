#!/usr/bin/env node
/**
 * mobius-system-https — 轻量 HTTPS 反向代理 (0.0.0.0:443 → 127.0.0.1:33316)
 *
 * 独立进程，不动主服务入口：失败/崩溃不影响现有 33316 服务。
 * 自签证书位于 backend/https-proxy-certs/（SAN 含 192.168.2.147 / mobius.local）。
 * 用法: node https-proxy.js  (pm2 挂载名 mobius-system-https)
 */
'use strict';

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const HTTPS_PORT = parseInt(process.env.MOBIUS_HTTPS_PORT || '443', 10);
const UPSTREAM_HOST = process.env.MOBIUS_HTTPS_UPSTREAM_HOST || '127.0.0.1';
const UPSTREAM_PORT = parseInt(process.env.MOBIUS_HTTPS_UPSTREAM_PORT || '33316', 10);
const CERT_DIR = path.join(__dirname, 'https-proxy-certs');

const options = {
  key: fs.readFileSync(path.join(CERT_DIR, 'mobius-local.key')),
  cert: fs.readFileSync(path.join(CERT_DIR, 'mobius-local.crt')),
};

// WebSocket / SSE 透传: 不设 timeout, 原样转发 Upgrade 头
const server = https.createServer(options, (req, res) => {
  const proxyReq = http.request(
    {
      host: UPSTREAM_HOST,
      port: UPSTREAM_PORT,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: `${UPSTREAM_HOST}:${UPSTREAM_PORT}` },
    },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode, proxyRes.headers);
      proxyRes.pipe(res, { end: true });
    }
  );
  proxyReq.on('error', (err) => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(`https-proxy upstream error: ${err.message}`);
  });
  req.pipe(proxyReq, { end: true });
});

// WebSocket Upgrade 转发 (Mobius 前端部分功能可能用到)
server.on('upgrade', (req, socket, head) => {
  const proxyReq = http.request({
    host: UPSTREAM_HOST,
    port: UPSTREAM_PORT,
    method: req.method,
    path: req.url,
    headers: { ...req.headers, host: `${UPSTREAM_HOST}:${UPSTREAM_PORT}` },
  });
  proxyReq.on('upgrade', (proxyRes, proxySocket, proxyHead) => {
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        Object.entries(proxyRes.headers)
          .map(([k, v]) => `${k}: ${v}`)
          .join('\r\n') +
        '\r\n\r\n'
    );
    if (proxyHead && proxyHead.length) socket.write(proxyHead);
    proxySocket.pipe(socket).pipe(proxySocket);
  });
  proxyReq.on('error', () => socket.destroy());
  proxyReq.end(head);
});

server.on('error', (err) => {
  console.error(`[https-proxy] server error: ${err.message}`);
  if (err.code === 'EACCES' || err.code === 'EADDRINUSE') {
    console.error(`[https-proxy] cannot listen on port ${HTTPS_PORT}, exiting`);
    process.exit(1);
  }
});

server.listen(HTTPS_PORT, '0.0.0.0', () => {
  console.log(`[https-proxy] https://0.0.0.0:${HTTPS_PORT} → http://${UPSTREAM_HOST}:${UPSTREAM_PORT}`);
});
