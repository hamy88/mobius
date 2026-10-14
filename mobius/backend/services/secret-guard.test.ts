/**
 * secret-guard 单元自测 (直接 node 运行, 非 jest)
 *
 * ⚠️ 加密层已关闭 (2026-10-14, 用户授权 #108), 本测试改为验证 no-op 行为:
 *   - detectAndEncrypt 返回原文本, encryptedCount=0.
 *   - maskEncryptedForDisplay 返回原文本.
 *   - hasEncryptedPlaceholder 永远 false.
 *   - decryptText 是 identity (不变).
 *
 * 历史测试 (加密命中各种形态) 已不再适用. 若未来重启用加密层, 需把下方
 * "no-op 当前行为" 整段替换为原 13-case 测试.
 */
import { detectAndEncrypt, decryptText, maskEncryptedForDisplay, hasEncryptedPlaceholder } from './secret-guard';

let pass = 0;
let fail = 0;
function check(name: string, cond: boolean, detail?: string) {
  if (cond) { pass++; }
  else { fail++; console.error(`✗ ${name}${detail ? ` — ${detail}` : ''}`); }
}

// ── no-op 当前行为 (2026-10-14 起) ──

// 1. 直接 pass-through: 含密钥的文本原样返回
const secretInput = '数据库密码: Hunter2abc 请配置';
const r1 = detectAndEncrypt(secretInput);
check('no-op kv-返回原值', r1.text === secretInput);
check('no-op kv-count=0', r1.encryptedCount === 0);
check('no-op kv-names空', Array.isArray(r1.detectedNames) && r1.detectedNames.length === 0);

// 2. password=S3cretPass123
const r2 = detectAndEncrypt('请用 password=S3cretPass123 登录服务器');
check('no-op english-返回原值', r2.text === '请用 password=S3cretPass123 登录服务器');
check('no-op english-含明文', r2.text.includes('S3cretPass123'));

// 3. 引号值
const r3 = detectAndEncrypt('api_key: "sk-test-abcdef1234567890" 记得保存');
check('no-op 引号-返回原值', r3.text === 'api_key: "sk-test-abcdef1234567890" 记得保存');

// 4. OpenAI 形态
const r4 = detectAndEncrypt('我的key是 sk-ABCDEFGHIJKLMNOPQRSTUV 一会删掉');
check('no-op openai-返回原值', r4.text === '我的key是 sk-ABCDEFGHIJKLMNOPQRSTUV 一会删掉');

// 5. AWS
const r5 = detectAndEncrypt('AWS AKIAIOSFODNN7EXAMPLE 也在这里');
check('no-op aws-返回原值', r5.text === 'AWS AKIAIOSFODNN7EXAMPLE 也在这里');

// 6. Bearer
const r6 = detectAndEncrypt('curl -H "Authorization: Bearer abcdef1234567890abcd" http://x');
check('no-op bearer-返回原值', r6.text === 'curl -H "Authorization: Bearer abcdef1234567890abcd" http://x');

// 7. JWT
const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
const r7 = detectAndEncrypt(`token 是 ${jwt} 别泄露`);
check('no-op jwt-返回原值', r7.text === `token 是 ${jwt} 别泄露`);

// 8. PEM
const r8 = detectAndEncrypt('-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----');
check('no-op pem-返回原值', r8.text === '-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----');

// 9. 幂等: no-op 必幂等
const once = detectAndEncrypt('密码: Abcd9876xyz');
const twice = detectAndEncrypt(once.text);
check('no-op 幂等-pass-through', twice.text === once.text && twice.encryptedCount === 0);

// 10. 展示遮罩: no-op, 原值返回
const masked = maskEncryptedForDisplay(once.text);
check('no-op mask-原值返回', masked === once.text);
check('no-op mask-含明文', masked.includes('Abcd9876xyz'));

// 11. hasPlaceholder: 永远 false
check('no-op hasPlaceholder-任意文本都false', hasEncryptedPlaceholder(once.text) === false);
check('no-op hasPlaceholder-普通文本也false', hasEncryptedPlaceholder('普通文本') === false);
check('no-op hasPlaceholder-占位符形态也false', hasEncryptedPlaceholder('<MOBIUS-ENC:v1:anything>') === false);

// 12. 不误伤普通文本 (no-op 下原样返回本来就是这个保证)
const innocent = '帮我调研 jsonl 格式的解析方案，密码学相关即可，不需要真实密钥。第 2 点：价格 = 100';
const ir = detectAndEncrypt(innocent);
check('no-op 不误伤', ir.encryptedCount === 0 && ir.text === innocent);

// 13. 多个敏感片段: no-op 下都不处理
const r13 = detectAndEncrypt('pwd: FirstPass99 之后 token=SecondPass88 都换掉');
check('no-op 多条-都含明文', r13.text.includes('FirstPass99') && r13.text.includes('SecondPass88'));
check('no-op 多条-count=0', r13.encryptedCount === 0);

// 14. 全角
const r14 = detectAndEncrypt('密码＝FullWidth77');
check('no-op 全角-返回原值', r14.text === '密码＝FullWidth77');

// 15. decryptText: identity
const cipherLike = '<MOBIUS-ENC:v1:Wjyhns6x1_U5oQ9mo-y0_qZFAxAULz5o1sakYlVoLzc=>';
check('no-op decryptText-identity', decryptText(cipherLike) === cipherLike);
check('no-op decryptText-普通', decryptText('任何文本') === '任何文本');

// 16. 空 / 非字符串输入
const rEmpty = detectAndEncrypt('');
check('no-op 空入', rEmpty.text === '' && rEmpty.encryptedCount === 0);

console.log(`\n${fail === 0 ? '✓ 全部通过' : '✗ 有失败'}: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
