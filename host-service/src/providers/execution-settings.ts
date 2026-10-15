import { Codex } from './codex.js';

export const permissionModes = ['read-only', 'on-request', 'untrusted', 'never', 'full-access'] as const;
export type ExecutionSettings = { model: string; effort: string | null; permissionMode: typeof permissionModes[number] };

async function listModels(codex: Codex) {
  const models: any[] = [];
  let cursor: string | null = null;
  do {
    const page = await codex.request('model/list', { limit: 100, cursor, includeHidden: true });
    models.push(...page.data);
    cursor = page.nextCursor ?? null;
  } while (cursor);
  return models;
}

function modelEffort(model: any, requested?: string | null): string | null {
  // Null in turn/start inherits the previous model's effort; use the host default for fixed-effort models.
  if (model?.supportedReasoningEfforts.length === 0) return model.defaultReasoningEffort;
  return requested ?? model?.defaultReasoningEffort ?? null;
}

export async function executionOptions(codex: Codex, cwd?: string) {
  const models = await listModels(codex);
  const { config } = await codex.request('config/read', { includeLayers: false, cwd });
  const model = config.model ?? models.find(m => m.isDefault)?.model ?? models[0]?.model;
  return {
    data: models.map(m => ({ id: m.id, model: m.model, displayName: m.displayName, description: m.description,
      supportedReasoningEfforts: m.supportedReasoningEfforts, defaultReasoningEffort: m.defaultReasoningEffort, isDefault: m.isDefault })),
    defaults: { model, effort: modelEffort(models.find(m => m.model === model), config.model_reasoning_effort), permissionMode: 'on-request' },
  };
}

export async function validateSettings(codex: Codex, value: any): Promise<ExecutionSettings> {
  if (!value || typeof value.model !== 'string' || !permissionModes.includes(value.permissionMode)) throw new Error('执行设置无效');
  const models = await listModels(codex);
  const model = models.find(m => m.model === value.model);
  if (!model) throw new Error('此电脑的 Codex 未提供所选模型，请刷新模型列表');
  const effort = modelEffort(model, value.effort);
  if (model.supportedReasoningEfforts.length && !model.supportedReasoningEfforts.some((entry: any) => entry.reasoningEffort === effort)) throw new Error('所选模型不支持此思考强度');
  return { model: value.model, effort, permissionMode: value.permissionMode };
}

export function threadSettings(settings?: ExecutionSettings) {
  const mode = settings?.permissionMode ?? 'on-request';
  return {
    ...(settings ? { model: settings.model, config: { model_reasoning_effort: settings.effort } } : {}),
    approvalPolicy: mode === 'full-access' || mode === 'read-only' ? 'never' : mode,
    sandbox: mode === 'full-access' ? 'danger-full-access' : mode === 'read-only' ? 'read-only' : 'workspace-write',
  };
}

export function turnSettings(settings?: ExecutionSettings) {
  if (!settings) return {};
  const policy = threadSettings(settings);
  return { model: settings.model, effort: settings.effort, approvalPolicy: policy.approvalPolicy,
    sandboxPolicy: settings.permissionMode === 'full-access' ? { type: 'dangerFullAccess' }
      : settings.permissionMode === 'read-only' ? { type: 'readOnly', networkAccess: false }
      : { type: 'workspaceWrite', writableRoots: [], networkAccess: false, excludeTmpdirEnvVar: false, excludeSlashTmp: false } };
}

export async function effectiveSettings(codex: Codex, result: any): Promise<ExecutionSettings> {
  const sandbox = result.sandbox?.type;
  const model = (await listModels(codex)).find(m => m.model === result.model);
  const effort = modelEffort(model, result.reasoningEffort);
  return { model: result.model, effort,
    permissionMode: sandbox === 'dangerFullAccess' ? 'full-access' : sandbox === 'readOnly' ? 'read-only'
      : result.approvalPolicy === 'untrusted' ? 'untrusted' : result.approvalPolicy === 'never' ? 'never' : 'on-request' };
}
