#!/usr/bin/env node
'use strict';
/**
 * socks5-http-relay.js
 *
 * 本地 HTTP/HTTPS CONNECT 代理 → 上游 SOCKS5
 * 让 Claude Code (走 HTTP_PROXY) 这类只支持 HTTP proxy 的客户端, 也能用 SOCKS5 出网
 *
 * 用法:
 *   node /app/mobius/backend/utils/socks5-http-relay.js
 *   HTTP_PROXY=http://127.0.0.1:8888 curl https://api.ipify.org  → 走 SOCKS5
 *
 * 端口: 8888 (可改 RELAY_PORT env)
 * 上游: socks5://111.36.208.22:10880 (可改 RELAY_SOCKS5_URL env)
 */

const net = require('node:net');
const http = require('node:http');
const { URL } = require('node:url');
const { SocksClient } = require('socks');

const RELAY_HOST = process.env.RELAY_HOST || '127.0.0.1';
const RELAY_PORT = parseInt(process.env.RELAY_PORT || '8888', 10);
const SOCKS5_URL = process.env.RELAY_SOCKS5_URL || process.env.MOBIUS_LLM_SOCKS5_URL || 'socks5://111.36.208.22:10880';

function parseSocksUrl(socksUrl) {
  const u = new URL(socksUrl);
  return {
    host: u.hostname,
    port: u.port ? parseInt(u.port, 10) : 1080,
    type: 5,
    userId: u.auth ? decodeURIComponent(u.auth.split(':')[0]) : undefined,
    password: u.auth ? decodeURIComponent(u.auth.split(':')[1] || '') : undefined,
  };
}
const PROXY = parseSocksUrl(SOCKS5_URL);

function log(msg) {
  process.stderr.write(`[socks5-http-relay] ${msg}\n`);
}

const server = http.createServer();

// HTTP 普通代理: 收到完整 GET/POST, 通过 SOCKS5 转发 (用于 HTTP 目标)
server.on('request', (req, res) => {
  const targetUrl = req.url.startsWith('http') ? req.url : null;
  if (!targetUrl) {
    res.writeHead(400);
    res.end('Bad request: absolute URL required for HTTP proxy');
    return;
  }
  const u = new URL(targetUrl);
  log(`HTTP ${req.method} ${u.hostname}:${u.port || 80}${u.pathname}`);

  SocksClient.createConnection({
    proxy: PROXY,
    command: 'connect',
    destination: { host: u.hostname, port: u.port ? parseInt(u.port, 10) : 80 },
    timeout: 15000,
  }).then(({ socket }) => {
    // 把客户端请求原样写到 SOCKS5 隧道
    const reqLine = `${req.method} ${u.pathname}${u.search} HTTP/1.1\r\n`;
    const headers = Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n');
    socket.write(reqLine + headers + '\r\n\r\n');
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      req.pipe(socket);
    }
    socket.pipe(res);
    req.on('error', () => socket.destroy());
    socket.on('error', () => res.destroy());
  }).catch((err) => {
    log(`  err: ${err.message}`);
    res.writeHead(502);
    res.end(`SOCKS5 connect failed: ${err.message}`);
  });
});

// HTTPS CONNECT 隧道
server.on('connect', (req, clientSocket, head) => {
  const [host, portStr] = req.url.split(':');
  const port = parseInt(portStr, 10) || 443;
  log(`CONNECT ${host}:${port}`);

  SocksClient.createConnection({
    proxy: PROXY,
    command: 'connect',
    destination: { host, port },
    timeout: 15000,
  }).then(({ socket }) => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head && head.length) socket.write(head);
    clientSocket.pipe(socket);
    socket.pipe(clientSocket);
    clientSocket.on('error', () => socket.destroy());
    socket.on('error', () => clientSocket.destroy());
  }).catch((err) => {
    log(`  err: ${err.message}`);
    clientSocket.write('HTTP/1.1 502 Bad Gateway\r\n\r\n');
    clientSocket.end();
  });
});

server.listen(RELAY_PORT, RELAY_HOST, () => {
  log(`listening http://${RELAY_HOST}:${RELAY_PORT} → ${SOCKS5_URL}`);
});

process.on('SIGTERM', () => { log('SIGTERM, closing'); server.close(); process.exit(0); });
process.on('SIGINT', () => { log('SIGINT, closing'); server.close(); process.exit(0); });
