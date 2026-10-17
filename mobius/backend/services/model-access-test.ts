/**
 * model-access-test.ts — 模型接入页「测试」按钮的后端连通性测试.
 *
 * 复用 light-model-api-test 的轻量工具调用测试: 让模型调用 add(7,35),
 * 同时验证凭证、网络与 function-calling 链路。只做一次性测试, 不接入业务流程.
 */
import * as modelAccess from './model-access';
import { testLightModelApi } from './light-model-api-test';

export type ModelAccessTestKind = 'claude-code' | 'codex' | 'deepseek-harness';
type ApiType = 'openai-chat-completion' | 'openai-response' | 'claude-message';

interface ResolvedTestConfig {
  type: ApiType;
  baseUrl: string;
  apiKey: string;
  model: string;
  label: string;
}

function normalizeBaseUrl(raw: unknown): string {
  const value = String(raw || '').trim().replace(/\/+$/, '');
  if (!value) throw new Error('模型配置缺少 base_url');
  return value;
}

function extractTomlValue(text: string, key: string): string {
  const re = new RegExp(`(?:^|\\n)\\s*${key}\\s*=\\s*"([^"]*)"\\s*(?:\\n|$)`, 'm');
  const match = String(text || '').match(re);
  return match ? match[1].trim() : '';
}

function claudeBaseUrlForTest(baseUrl: string): string {
  const trimmed = baseUrl.replace(/\/+$/, '');
  if (/\/v1\/messages$/.test(trimmed)) return trimmed.slice(0, -'/messages'.length);
  if (/\/v1$/.test(trimmed)) return trimmed;
  return `${trimmed}/v1`;
}

function resolveClaudeCode(key: string): ResolvedTestConfig {
  const row = modelAccess.findClaudeCodeModel(key, { includeSettings: true }) as any;
  if (!row) throw new Error(`模型配置不存在: ${key}`);
  let settings: any = {};
  try {
    settings = JSON.parse(row.settings_json || '{}') || {};
  } catch (e: any) {
    throw new Error(`settings JSON 非法: ${e.message}`);
  }
  const env = settings?.env && typeof settings.env === 'object' ? settings.env : {};
  const baseUrl = normalizeBaseUrl(env.ANTHROPIC_BASE_URL);
  const apiKey = String(env.ANTHROPIC_AUTH_TOKEN || env.ANTHROPIC_API_KEY || '').trim();
  if (!apiKey) throw new Error('settings JSON 缺少 ANTHROPIC_AUTH_TOKEN / ANTHROPIC_API_KEY');
  const model = String(row.claude_model || env.ANTHROPIC_MODEL || settings.model || '').trim();
  if (!model) throw new Error('settings JSON 缺少模型名');
  return {
    type: 'claude-message',
    baseUrl: claudeBaseUrlForTest(baseUrl),
    apiKey,
    model,
    label: String(row.label || key),
  };
}

function resolveCodex(key: string): ResolvedTestConfig {
  const row = modelAccess.findCodexModel(key, { includeConfig: true, includeSecret: true }) as any;
  if (!row) throw new Error(`Codex 渠道不存在: ${key}`);
  const toml = String(row.config_toml || '');
  const baseUrl = normalizeBaseUrl(extractTomlValue(toml, 'base_url'));
  const wireApi = extractTomlValue(toml, 'wire_api') || 'responses';
  const apiKey = String(row.secret_value || '').trim() || extractTomlValue(toml, 'api_key');
  if (!apiKey) {
    if (key === 'mobiusopenaisubscription') {
      throw new Error('Codex 订阅渠道的凭证保存在 ~/.codex/auth.json，无法用 HTTP 密钥测试；请用 Codex CLI 验证登录状态');
    }
    throw new Error('Codex 配置缺少 API Key (secret_value 或 TOML 中的 api_key)');
  }
  const model = String(row.codex_model || extractTomlValue(toml, 'model')).trim();
  if (!model) throw new Error('Codex 配置缺少模型名');
  const type: ApiType = /(^|[-_\s])chat([-_\s]|$)/i.test(wireApi) || wireApi === 'chat'
    ? 'openai-chat-completion'
    : 'openai-response';
  return { type, baseUrl, apiKey, model, label: String(row.label || key) };
}

function resolveHarness(key: string): ResolvedTestConfig {
  const row = modelAccess.findHarnessModel(key, { includeSecret: true }) as any;
  if (!row) throw new Error(`Harness 模型不存在: ${key}`);
  const apiKey = String(row.secret_value || '').trim();
  if (!apiKey) throw new Error('Harness 配置缺少 API Key');
  return {
    type: 'openai-chat-completion',
    baseUrl: normalizeBaseUrl(row.base_url),
    apiKey,
    model: String(row.model || '').trim(),
    label: String(row.label || key),
  };
}

export async function testModelAccess(kind: ModelAccessTestKind, key: string): Promise<any> {
  const cfg = kind === 'claude-code'
    ? resolveClaudeCode(key)
    : kind === 'codex'
      ? resolveCodex(key)
      : resolveHarness(key);

  try {
    const result = await testLightModelApi({
      type: cfg.type,
      baseUrl: cfg.baseUrl,
      apiKey: cfg.apiKey,
      model: cfg.model,
    });
    return {
      ok: !!result.ok,
      model: cfg.model,
      label: cfg.label,
      summary: result.summary || `${cfg.label} 连接正常`,
      reason: result.reason,
    };
  } catch (e: any) {
    return {
      ok: false,
      model: cfg.model,
      label: cfg.label,
      error: e?.message || String(e),
    };
  }
}
