/** secret-guard 单元自测 (直接 node 运行, 非 jest) */
import { detectAndEncrypt, decryptText, maskEncryptedForDisplay, hasEncryptedPlaceholder } from './secret-guard';

let pass = 0;
let fail = 0;
function check(name: string, cond: boolean, detail?: string) {
  if (cond) { pass++; }
  else { fail++; console.error(`✗ ${name}${detail ? ` — ${detail}` : ''}`); }
}

// 1. key=value 形态
let r = detectAndEncrypt('数据库密码: Hunter2abc 请配置');
check('kv-中文名值', r.encryptedCount === 1);
check('kv-密文入库', !r.text.includes('Hunter2abc'));
check('kv-占位符', r.text.includes('<MOBIUS-ENC:v1:'));
check('kv-解密还原', decryptText(r.text) === '数据库密码: Hunter2abc 请配置');

// 2. password=xxx
r = detectAndEncrypt('请用 password=S3cretPass123 登录服务器');
check('kv-english', r.encryptedCount === 1 && !r.text.includes('S3cretPass123'));

// 3. api_key
r = detectAndEncrypt('api_key: "sk-test-abcdef1234567890" 记得保存');
check('kv-引号值', r.encryptedCount === 1 && !r.text.includes('sk-test-abcdef1234567890'));

// 4. 形态识别: OpenAI key
r = detectAndEncrypt('我的key是 sk-ABCDEFGHIJKLMNOPQRSTUV 一会删掉');
check('shape-openai', r.encryptedCount === 1 && !r.text.includes('sk-ABCDEFGHIJKLMNOPQRSTUV'));

// 5. AWS
r = detectAndEncrypt('AWS AKIAIOSFODNN7EXAMPLE 也在这里');
check('shape-aws', !r.text.includes('AKIAIOSFODNN7EXAMPLE'));

// 6. Bearer
r = detectAndEncrypt('curl -H "Authorization: Bearer abcdef1234567890abcd" http://x');
check('shape-bearer', !r.text.includes('abcdef1234567890abcd'));

// 7. JWT
const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
r = detectAndEncrypt(`token 是 ${jwt} 别泄露`);
check('shape-jwt', !r.text.includes(jwt) && r.encryptedCount >= 1);

// 8. PEM
r = detectAndEncrypt('-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----');
check('shape-pem', !r.text.includes('MIIEow'));

// 9. 幂等: 已加密文本再过一遍不变
const once = detectAndEncrypt('密码: Abcd9876xyz');
const twice = detectAndEncrypt(once.text);
check('幂等', twice.text === once.text && twice.encryptedCount === 0);

// 10. 展示遮罩
check('mask-不含明文', !maskEncryptedForDisplay(once.text).includes('Abcd9876xyz'));
check('mask-尾4位', maskEncryptedForDisplay(once.text).includes('••••6xyz'));
check('hasPlaceholder', hasEncryptedPlaceholder(once.text) && !hasEncryptedPlaceholder('普通文本'));

// 11. 不误伤普通文本
const innocent = '帮我调研 jsonl 格式的解析方案，密码学相关即可，不需要真实密钥。第 2 点：价格 = 100';
const ir = detectAndEncrypt(innocent);
check('不误伤', ir.encryptedCount === 0 && ir.text === innocent, JSON.stringify(ir));

// 12. 多个敏感片段一条消息
r = detectAndEncrypt('pwd: FirstPass99 之后 token=SecondPass88 都换掉');
check('多条', r.encryptedCount === 2, `count=${r.encryptedCount}`);
check('多条-都不含明文', !r.text.includes('FirstPass99') && !r.text.includes('SecondPass88'));

// 13. 中文全角分隔
r = detectAndEncrypt('密码＝FullWidth77');
check('全角等号', !r.text.includes('FullWidth77'));

console.log(`\n${fail === 0 ? '✓ 全部通过' : '✗ 有失败'}: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
