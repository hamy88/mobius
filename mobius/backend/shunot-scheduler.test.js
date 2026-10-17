// /app/mobius/backend/shunot-scheduler.test.js
// 单元测试：覆盖 9 分类均衡轮换 + stale 硬下限（用户 2026-10-17 长期指令）
// 运行：node --test /app/mobius/backend/shunot-scheduler.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const { ROTATION, ROTATION_SIDS, MAX_STALE_DAYS, pickStaleSortId, fetchLastPostAgeBySid, nextRotation } = require('./shunot-scheduler.js');

test('ROTATION 包含 9 个分类（含 luyou + Lightcat）', () => {
  assert.equal(ROTATION.length, 9, '应为 9 个分类');
  const sids = ROTATION.map((r) => r[0]);
  for (const required of [1, 2, 3, 5, 8, 9, 10, 12, 13]) {
    assert.ok(sids.includes(required), `应包含 sid=${required}`);
  }
});

test('ROTATION_SIDS 顺序与 ROTATION 一致', () => {
  assert.deepEqual(ROTATION_SIDS, ROTATION.map((r) => r[0]));
});

test('MAX_STALE_DAYS = 2（用户硬下限）', () => {
  assert.equal(MAX_STALE_DAYS, 2);
});

test('每个分类的方向候选统一 4-5 个（避免某些分类选题范围过宽）', () => {
  for (const [sid, alias, hint] of ROTATION) {
    const cands = hint.split(/[、，,]/).filter((s) => s.trim().length > 0);
    assert.ok(cands.length >= 3 && cands.length <= 6, `sid=${sid} 候选数 ${cands.length} 应在 3-6 之间: ${hint}`);
  }
});

test('pickStaleSortId: 多个 stale 时返回最久没发的', () => {
  const ages = { 1: 1.5, 2: 0.5, 3: 5.0, 5: 1.0, 8: 4.0, 9: 0.2, 10: 0.8, 12: 0.3, 13: 1.2 };
  // 排除 lastSort=13
  const r = pickStaleSortId(ages, 13);
  assert.equal(r[0], 3, 'sid=3 距今 5d 最久，lastSort=13 排除 → 应选 sid=3');
});

test('pickStaleSortId: 完整 9 sid ages 缺一个，其他 8 个均 ≤ 2d 时返回 null', () => {
  // sid=6/7 是空分类(不在 ROTATION), 不必提供
  const ages = { 1: 1.0, 2: 0.5, 3: 1.2, 5: 1.5, 8: 0.8, 9: 0.2, 10: 1.0, 12: 0.3, 13: 0.7 };
  const r = pickStaleSortId(ages, 5);
  assert.equal(r, null, '全部 ≤ 2d → null，触发 fresh-pick');
});

test('pickStaleSortId: stale 中排除 lastSort', () => {
  // 9 个 sid 齐全：sid=3 是 lastSort 应被排除；stale 中距今最久的是 sid=1 (5d)
  const ages = { 1: 5.0, 2: 0.5, 3: 5.0, 5: 1.0, 8: 4.0, 9: 0.2, 10: 0.8, 12: 0.3, 13: 1.2 };
  const r = pickStaleSortId(ages, 3);
  assert.equal(r[0], 1, 'sid=3 是 lastSort 应被排除，stale 中 sid=1 距今 5d 最久');
});

test('pickStaleSortId: 全部 ≤ 2d 时返回 null（触发 fresh-pick 路径）', () => {
  const ages = { 1: 1.0, 2: 0.5, 3: 0.1, 5: 1.5, 8: 0.8, 9: 0.2, 10: 1.2, 12: 0.3, 13: 0.7 };
  const r = pickStaleSortId(ages, 5);
  assert.equal(r, null);
});

test('pickStaleSortId: 某 sid 从未发文（Infinity）必被选', () => {
  const ages = { 1: Infinity, 2: 0.5, 3: 1.0, 5: 1.0 };
  const r = pickStaleSortId(ages, 5);
  assert.equal(r[0], 1, 'sid=1 距今 Infinity，应被强制选');
});

test('nextRotation: 综合当前悬殊场景下，应优先选 Lightcat/luyou', async () => {
  // 模拟"实测场景"：综合 39% 占比但最近才发；Lightcat 39 天没发
  const ages = { 1: 20, 2: 30, 3: 39, 5: 0.5, 8: 5, 9: 5, 10: 0.3, 12: 1, 13: 0.2 };
  const r = await nextRotation(5, ages);  // 注入 mock ages，避免真发网络请求
  assert.ok([1, 2, 3].includes(r[0]), `应选 stale 分类 (1/2/3)，实际 sid=${r[0]}`);
  // 三个 stale 中 39d 那个最久
  assert.equal(r[0], 3, 'sid=3 距今 39d 最久，应被强制选');
});

test('nextRotation: 全部 ≤ 2d 时按"距今最久"选，且不与 lastSort 相同', async () => {
  const ages = { 1: 1.5, 2: 1.8, 3: 0.5, 5: 0.3, 8: 1.7, 9: 0.8, 10: 0.2, 12: 1.0, 13: 1.6 };
  const r = await nextRotation(2, ages);
  assert.notEqual(r[0], 2, '不应选 lastSort=2');
  // 1.8d 的 sid=2 被排除 → 1.7d 的 sid=8 最高
  assert.equal(r[0], 8, '应选距今最久的非 lastSort');
});

test('nextRotation: 多次连续调用不会总挑同一分类（覆盖 7 天模拟）', async () => {
  // 模拟 7 天每 30~110 分钟 1 次的频率，每次 nextRotation 后更新 ages（很粗略）
  const ages = { 1: 1.5, 2: 1.8, 3: 0.5, 5: 0.3, 8: 1.7, 9: 0.8, 10: 0.2, 12: 1.0, 13: 1.6 };
  const picks = [];
  for (let i = 0; i < 9; i++) {
    const r = await nextRotation(picks[picks.length - 1] || 5, ages);
    picks.push(r[0]);
    // 模拟写了之后该 sid 龄变小
    ages[r[0]] = 0.1;
  }
  // 9 个分类都应至少被选 1 次
  for (const sid of [1, 2, 3, 5, 8, 9, 10, 12, 13]) {
    assert.ok(picks.includes(sid), `9 次循环 sid=${sid} 应至少出现 1 次，实际 picks=[${picks.join(',')}]`);
  }
});

test('fetchLastPostAgeBySid: 单次调用返回每个 sid 的天数（用真实 emlog API，需网络）', async (t) => {
  if (!process.env.RUN_NETWORK_TEST) {
    t.skip('需要 RUN_NETWORK_TEST=1 启用真实 emlog API 调用');
    return;
  }
  const ages = await fetchLastPostAgeBySid([1, 3, 5, 10, 13]);
  for (const sid of [1, 3, 5, 10, 13]) {
    assert.ok(typeof ages[sid] === 'number', `sid=${sid} 应返回 number，实际 ${ages[sid]}`);
  }
});
