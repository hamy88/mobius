// 扩展 handler 的 node:test。覆盖 whoami / register / list / unregister / notify（含本地
// capture server 重定向 JPush API）/ 校验。运行：node --test backend/
//
// 这里验证的是「后端链路」的核心逻辑：设备令牌持久化、JPush 推送请求的正确形状（Basic Auth、
// registration_id、notification、extras.deepLink），以及错误兜底——不依赖真实 Mobius 平台或 JPush 服务。

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');

const handler = require('./extension_backend_handler.js');

// ----- 测试夹具 -----
let tmpDir;
const ENV_KEYS = ['MOMO_JPUSH_APPKEY', 'MOMO_JPUSH_MASTER_SECRET', 'MOMO_JPUSH_API'];
const savedEnv = {};

async function freshDir() {
  tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'momo-ext-test-'));
  return tmpDir;
}

async function rmDir() {
  if (tmpDir) await fs.rm(tmpDir, { recursive: true, force: true });
  tmpDir = null;
}

function setEnv(obj) {
  for (const k of ENV_KEYS) {
    if (!(k in savedEnv)) savedEnv[k] = process.env[k];
    if (obj && k in obj) process.env[k] = obj[k];
    else delete process.env[k];
  }
}

function restoreEnv() {
  for (const k of ENV_KEYS) {
    if (savedEnv[k] === undefined) delete process.env[k];
    else process.env[k] = savedEnv[k];
    delete savedEnv[k];
  }
}

const noopLogger = { info() {}, warn() {}, error() {} };

function call(username, payload, extDataDir) {
  return handler({
    username,
    display_name: username,
    extension_name: 'momo-mobile',
    ext_main_payload: payload,
    ext_data_dir: extDataDir || tmpDir,
    logger: noopLogger,
  });
}

// 本地 HTTP capture server，模拟 JPush /v3/push，捕获请求并按可控状态码响应。
function startCaptureServer(status = 200, sink = {}) {
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      sink.method = req.method;
      sink.url = req.url;
      sink.auth = req.headers['authorization'];
      sink.contentType = req.headers['content-type'];
      sink.body = Buffer.concat(chunks).toString('utf8');
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end('{"sendno":"123"}');
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

// ----- 用例 -----

test.beforeEach(async () => { await freshDir(); });
test.afterEach(async () => { await rmDir(); restoreEnv(); });

test('whoami returns identity', async () => {
  const r = await call('alice', { action: 'whoami' });
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.username, 'alice');
  assert.strictEqual(r.extension_name, 'momo-mobile');
});

test('unknown action is rejected', async () => {
  const r = await call('alice', { action: 'nope' });
  assert.strictEqual(r.ok, false);
  assert.match(r.error, /unknown action/);
});

test('register requires auth (username)', async () => {
  const r = await call('', { action: 'register_device', token: 't1', platform: 'jpush' });
  assert.strictEqual(r.ok, false);
  assert.match(r.error, /auth required/);
});

test('register_device persists token and dedupes', async () => {
  assert.strictEqual((await call('alice', { action: 'register_device', token: 't1', platform: 'jpush' })).registered, 1);
  // 同一 token 再注册：去重，数量不变。
  assert.strictEqual((await call('alice', { action: 'register_device', token: 't1', platform: 'jpush' })).registered, 1);
  // 第二个 token。
  assert.strictEqual((await call('alice', { action: 'register_device', token: 't2', platform: 'jpush' })).registered, 2);

  const raw = await fs.readFile(path.join(tmpDir, 'devices.json'), 'utf8');
  const data = JSON.parse(raw);
  assert.deepStrictEqual(
    data.alice.map((d) => d.token).sort(),
    ['t1', 't2'],
  );
  assert.strictEqual(data.alice[0].platform, 'jpush');
});

test('register rejects invalid token', async () => {
  let r = await call('alice', { action: 'register_device', token: '', platform: 'jpush' });
  assert.strictEqual(r.ok, false);
  r = await call('alice', { action: 'register_device', token: 123, platform: 'jpush' });
  assert.strictEqual(r.ok, false);
});

test('list_devices returns caller devices', async () => {
  await call('alice', { action: 'register_device', token: 't1', platform: 'jpush' });
  await call('bob', { action: 'register_device', token: 't9', platform: 'jpush' });
  const alice = await call('alice', { action: 'list_devices' });
  assert.strictEqual(alice.ok, true);
  assert.strictEqual(alice.devices.length, 1);
  // 隔离：alice 看不到 bob 的设备。
  assert.strictEqual(alice.devices[0].platform, 'jpush');
});

test('unregister_device removes token', async () => {
  await call('alice', { action: 'register_device', token: 't1', platform: 'jpush' });
  await call('alice', { action: 'register_device', token: 't2', platform: 'jpush' });
  assert.strictEqual((await call('alice', { action: 'unregister_device', token: 't1' })).registered, 1);
  const list = await call('alice', { action: 'list_devices' });
  assert.strictEqual(list.devices.length, 1);
});

test('notify_user with no device returns pushed:0', async () => {
  setEnv({ MOMO_JPUSH_APPKEY: 'k', MOMO_JPUSH_MASTER_SECRET: 's' });
  const r = await call('alice', { action: 'notify_user', title: 'Mobius', body: 'hi' });
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.pushed, 0);
});

test('notify_user posts JPush-shaped request and returns pushed count', async () => {
  setEnv({ MOMO_JPUSH_APPKEY: 'appkey-x', MOMO_JPUSH_MASTER_SECRET: 'secret-y' });
  const sink = {};
  const { server, port } = await startCaptureServer(200, sink);
  setEnv({ MOMO_JPUSH_APPKEY: 'appkey-x', MOMO_JPUSH_MASTER_SECRET: 'secret-y', MOMO_JPUSH_API: `http://127.0.0.1:${port}` });

  await call('alice', { action: 'register_device', token: 'rid-A', platform: 'jpush' });
  const r = await call('alice', { action: 'notify_user', title: 'Mobius', body: '你好', deepLink: 'momo://chat/42' });

  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.pushed, 1);

  // 捕获到的 JPush 推送请求形状
  assert.strictEqual(sink.method, 'POST');
  assert.strictEqual(sink.url, '/v3/push');
  assert.strictEqual(sink.contentType, 'application/json');
  // Basic Auth = base64(appkey:master_secret)
  const expected = `Basic ${Buffer.from('appkey-x:secret-y').toString('base64')}`;
  assert.strictEqual(sink.auth, expected);

  const body = JSON.parse(sink.body);
  assert.deepStrictEqual(body.audience.registration_id, ['rid-A']);
  assert.strictEqual(body.notification.alert, '你好');
  assert.strictEqual(body.notification.android.title, 'Mobius');
  assert.deepStrictEqual(body.notification.android.extras, { deepLink: 'momo://chat/42' });
  assert.deepStrictEqual(body.notification.ios.extras, { deepLink: 'momo://chat/42' });

  await new Promise((res) => server.close(res));
});

test('notify_user fails clearly when master secret missing', async () => {
  setEnv({ MOMO_JPUSH_APPKEY: 'k' }); // 缺 master secret
  await call('alice', { action: 'register_device', token: 'rid-B', platform: 'jpush' });
  const r = await call('alice', { action: 'notify_user', body: 'hi' });
  assert.strictEqual(r.ok, false);
  assert.match(r.error, /master_secret missing/);
});

test('notify_user surfaces JPush non-2xx as failure', async () => {
  setEnv({ MOMO_JPUSH_APPKEY: 'appkey-x', MOMO_JPUSH_MASTER_SECRET: 'secret-y' });
  const sink = {};
  const { server, port } = await startCaptureServer(401, sink);
  setEnv({ MOMO_JPUSH_APPKEY: 'appkey-x', MOMO_JPUSH_MASTER_SECRET: 'secret-y', MOMO_JPUSH_API: `http://127.0.0.1:${port}` });
  await call('alice', { action: 'register_device', token: 'rid-C', platform: 'jpush' });
  const r = await call('alice', { action: 'notify_user', body: 'hi' });
  assert.strictEqual(r.ok, false);
  assert.match(r.error, /jpush rejected/);
  await new Promise((res) => server.close(res));
});
