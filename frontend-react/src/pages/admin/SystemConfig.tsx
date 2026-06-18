import { useMemo, useState, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import {
  createScanSourceConfig,
  activateTraceRulePack,
  archiveTraceRulePack,
  createTraceRulePack,
  deleteScanSourceConfig,
  deleteTraceRulePack,
  deactivateTraceRulePack,
  disableScanSourceConfig,
  enableScanSourceConfig,
  listBuiltinScanSources,
  listEnabledModelConfigs,
  listProjects,
  listScanJobs,
  listScanProfiles,
  listScanSourceConfigs,
  listTraceRulePacks,
  runScan,
  updateTraceRulePack,
  updateScanSourceConfig,
  type ModelConfigRecord,
  type Project,
  type ScanSourceConfig,
  type TraceRulePackConfig,
} from '../../services/api';

type ScanMode = 'builtin' | 'link';

type ScanSourceOption = {
  key: string;
  label: string;
  defaultSelected: boolean;
  sourceType?: string;
  sourceUrl?: string | null;
  sourceFilePath?: string | null;
  enabled?: boolean;
  scope?: 'GLOBAL' | 'PROJECT' | 'PROVIDER';
};

type SourceFormState = {
  sourceKey: string;
  sourceLabel: string;
  sourceType: string;
  sourceUrl: string;
  sourceFilePath: string;
  description: string;
  defaultSelected: boolean;
  enabled: boolean;
};

type RulePackFormState = {
  packKey: string;
  packName: string;
  status: string;
  priority: number;
  description: string;
  configJson: string;
};

type RulePackTemplate = {
  key: string;
  label: string;
  packKey: string;
  packName: string;
  description: string;
  config: Record<string, unknown>;
};

type RulePackValidation = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  counts: Record<string, number>;
  samples: string[];
  parsedName: string;
};

const emptySourceForm: SourceFormState = {
  sourceKey: '',
  sourceLabel: '',
  sourceType: 'URL_LIST',
  sourceUrl: '',
  sourceFilePath: '',
  description: '',
  defaultSelected: false,
  enabled: true,
};

const emptyRulePackConfig = {
  name: 'project-rule-pack',
  workflows: [],
  objectHistoryContextContains: [],
  objectSelectionDialogContains: [],
  objectSelectionTargetContains: [],
  dialogContextContains: [],
  dialogInputConfirmFlows: [],
  entryNavigationSubmitFlows: [],
  inputRules: [],
  changeRules: [],
  clickRules: [],
  objectLabelRules: [],
  checkboxSemanticRules: [],
  dialogActionRules: [],
  businessActionRules: [],
};

const rulePackArrayFields = [
  'workflows',
  'objectHistoryContextContains',
  'objectSelectionDialogContains',
  'objectSelectionTargetContains',
  'dialogContextContains',
  'dialogInputConfirmFlows',
  'entryNavigationSubmitFlows',
  'inputRules',
  'changeRules',
  'clickRules',
  'objectLabelRules',
  'checkboxSemanticRules',
  'dialogActionRules',
  'businessActionRules',
];

const rulePackTemplates: RulePackTemplate[] = [
  {
    key: 'input',
    label: '输入字段规范化',
    packKey: 'FIELD_INPUT_RULES',
    packName: '字段输入规则',
    description: '规范输入类步骤的字段和值描述',
    config: {
      ...emptyRulePackConfig,
      name: 'field-input-rules',
      inputRules: [
        {
          targetContains: '客户姓名',
          resultType: 'REPLACE',
          template: '填写${field}：${value}',
        },
      ],
    },
  },
  {
    key: 'choice',
    label: '选择项规范化',
    packKey: 'CHOICE_RULES',
    packName: '选择项规则',
    description: '规范下拉、单选、多选等选择类步骤',
    config: {
      ...emptyRulePackConfig,
      name: 'choice-rules',
      changeRules: [
        {
          targetContains: '状态',
          resultType: 'REPLACE',
          template: '选择${field}：${value}',
        },
      ],
      checkboxSemanticRules: [
        {
          elementTextContains: '全选',
          semantic: '批量选择当前列表',
        },
      ],
    },
  },
  {
    key: 'click',
    label: '点击动作规范化',
    packKey: 'ACTION_RULES',
    packName: '点击动作规则',
    description: '规范按钮、弹窗动作和业务动作名称',
    config: {
      ...emptyRulePackConfig,
      name: 'action-rules',
      clickRules: [
        {
          targetContains: '保存',
          resultType: 'REPLACE',
          template: '保存当前表单',
        },
      ],
      dialogActionRules: [
        {
          elementTextContains: '确定',
          dialogTitleContains: '确认',
          action: '确认操作',
        },
      ],
      businessActionRules: [
        {
          containsText: '提交',
          action: '提交业务单据',
        },
      ],
    },
  },
  {
    key: 'workflow',
    label: '工作流骨架',
    packKey: 'WORKFLOW_RULES',
    packName: '工作流规则',
    description: '为对话输入确认、入口导航提交流预留结构',
    config: {
      ...emptyRulePackConfig,
      name: 'workflow-rules',
      workflows: [
        {
          kind: 'dialog-input-confirm',
          ref: 'default-dialog-flow',
        },
      ],
      dialogInputConfirmFlows: [
        {
          name: 'default-dialog-flow',
          startDescription: '打开弹窗',
          inputPrefix: '在',
          mergedInputTemplate: '填写表单信息',
          confirmRewrite: '确认提交表单',
        },
      ],
    },
  },
];

function formatRulePackConfig(config: Record<string, unknown>) {
  return JSON.stringify(config, null, 2);
}

const defaultRulePackJson = formatRulePackConfig(rulePackTemplates[0].config);

function validateRulePackJson(configJson: string): RulePackValidation {
  const errors: string[] = [];
  const warnings: string[] = [];
  const counts: Record<string, number> = {};
  const samples: string[] = [];
  let parsedName = '-';

  try {
    const parsed = JSON.parse(configJson);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      errors.push('根节点必须是 JSON 对象');
      return { valid: false, errors, warnings, counts, samples, parsedName };
    }

    parsedName = typeof parsed.name === 'string' && parsed.name.trim() ? parsed.name.trim() : '-';
    for (const field of rulePackArrayFields) {
      const value = parsed[field];
      if (value === undefined) {
        counts[field] = 0;
        continue;
      }
      if (!Array.isArray(value)) {
        errors.push(`${field} 必须是数组`);
        counts[field] = 0;
        continue;
      }
      counts[field] = value.length;
    }

    for (const field of ['inputRules', 'changeRules', 'clickRules']) {
      const rules = Array.isArray(parsed[field]) ? parsed[field] : [];
      rules.forEach((rule: any, index: number) => {
        if (!rule || typeof rule !== 'object' || Array.isArray(rule)) {
          errors.push(`${field}[${index}] 必须是对象`);
          return;
        }
        const hasMatcher = [
          'targetEquals',
          'targetContains',
          'valueEquals',
          'pageNameContains',
          'dialogTitleContains',
          'sectionTitleContains',
        ].some(key => Boolean(rule[key]));
        if (!hasMatcher) {
          warnings.push(`${field}[${index}] 没有匹配条件，可能影响范围`);
        }
        if (rule.resultType && !['REPLACE', 'DROP'].includes(String(rule.resultType).toUpperCase())) {
          errors.push(`${field}[${index}].resultType 仅支持 REPLACE 或 DROP`);
        }
        if (rule.template) {
          samples.push(`${field}[${index}] → ${rule.template}`);
        }
      });
    }

    const totalRules = ['workflows', 'inputRules', 'changeRules', 'clickRules', 'objectLabelRules',
      'checkboxSemanticRules', 'dialogActionRules', 'businessActionRules']
      .reduce((sum, field) => sum + (counts[field] || 0), 0);
    if (totalRules === 0) {
      warnings.push('当前规则包没有可执行规则');
    }
  } catch (error: any) {
    errors.push(error?.message || 'JSON 格式不合法');
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
    counts,
    samples: samples.slice(0, 4),
    parsedName,
  };
}

const emptyRulePackForm: RulePackFormState = {
  packKey: '',
  packName: '',
  status: 'DRAFT',
  priority: 0,
  description: '',
  configJson: defaultRulePackJson,
};

const sourceTypeLabels: Record<string, string> = {
  URL_LIST: '页面链接',
  BUILTIN_JSON: 'JSON 文件',
  PROJECT_ASSET: '项目资产',
};

const ruleStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效',
  INACTIVE: '停用',
  ARCHIVED: '归档',
};

function statusLabel(status?: string) {
  if (status === 'SUCCESS' || status === 'COMPLETED') return '成功';
  if (status === 'RUNNING') return '运行中';
  if (status === 'FAILED') return '失败';
  return status || '未知';
}

function statusClass(status?: string) {
  if (status === 'SUCCESS' || status === 'COMPLETED') return 'bg-emerald-50 text-emerald-700 border-emerald-200';
  if (status === 'RUNNING') return 'bg-blue-50 text-blue-700 border-blue-200';
  if (status === 'FAILED') return 'bg-red-50 text-red-700 border-red-200';
  return 'bg-gray-50 text-gray-600 border-gray-200';
}

function compactDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export default function SystemConfig() {
  const { showToast } = useApp();
  const [builtinSources, setBuiltinSources] = useState<ScanSourceOption[]>([]);
  const [scanSourceConfigs, setScanSourceConfigs] = useState<ScanSourceConfig[]>([]);
  const [rulePacks, setRulePacks] = useState<TraceRulePackConfig[]>([]);
  const [jobs, setJobs] = useState<any[]>([]);
  const [profiles, setProfiles] = useState<any[]>([]);
  const [models, setModels] = useState<ModelConfigRecord[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedSources, setSelectedSources] = useState<Set<string>>(new Set());
  const [projectId, setProjectId] = useState('');
  const [selectedModel, setSelectedModel] = useState<number | undefined>();
  const [scanMode, setScanMode] = useState<ScanMode>('builtin');
  const [linkUrls, setLinkUrls] = useState('');
  const [running, setRunning] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [sourceForm, setSourceForm] = useState<SourceFormState>(emptySourceForm);
  const [editingSourceId, setEditingSourceId] = useState<number | null>(null);
  const [savingSource, setSavingSource] = useState(false);
  const [rulePackForm, setRulePackForm] = useState<RulePackFormState>(emptyRulePackForm);
  const [selectedRulePackTemplate, setSelectedRulePackTemplate] = useState(rulePackTemplates[0].key);
  const [editingRulePackId, setEditingRulePackId] = useState<number | null>(null);
  const [savingRulePack, setSavingRulePack] = useState(false);
  const rulePackValidation = useMemo(
    () => validateRulePackJson(rulePackForm.configJson),
    [rulePackForm.configJson]
  );

  useEffect(() => {
    setLoading(true);
    Promise.all([
      listBuiltinScanSources().catch(() => []),
      listEnabledModelConfigs().catch(() => []),
      listProjects({ page: 1, size: 200 }).then(result => result.items).catch(() => []),
    ]).then(([s, m, p]) => {
      const normalized = s.map((item: any) => ({
        key: item.key,
        label: item.label,
        defaultSelected: Boolean(item.defaultSelected),
        scope: 'PROVIDER' as const,
      }));
      setBuiltinSources(normalized);
      setModels(m);
      setProjects(p);
      setSelectedSources(new Set(normalized.filter(item => item.defaultSelected).map(item => item.key)));
      if (m.length > 0) setSelectedModel(m[0].id);
    }).finally(() => setLoading(false));
  }, []);

  const sourceOptions = useMemo(() => {
    const merged = new Map<string, ScanSourceOption>();
    for (const source of builtinSources) {
      merged.set(source.key, source);
    }
    for (const cfg of scanSourceConfigs) {
      merged.set(cfg.sourceKey, {
        key: cfg.sourceKey,
        label: cfg.sourceLabel,
        defaultSelected: cfg.defaultSelected,
        sourceType: cfg.sourceType,
        sourceUrl: cfg.sourceUrl,
        sourceFilePath: cfg.sourceFilePath,
        enabled: cfg.enabled,
        scope: cfg.projectId ? 'PROJECT' : 'GLOBAL',
      });
    }
    return Array.from(merged.values()).filter(item => item.enabled !== false);
  }, [builtinSources, scanSourceConfigs]);

  const defaultSourceKeys = useMemo(() => {
    return sourceOptions.filter(item => item.defaultSelected).map(item => item.key);
  }, [sourceOptions]);

  const loadProjectScanData = async (rawProjectId: string) => {
    if (!rawProjectId) {
      setJobs([]);
      setProfiles([]);
      setScanSourceConfigs([]);
      setRulePacks([]);
      return;
    }
    const numericProjectId = Number(rawProjectId);
    setReloading(true);
    try {
      const [jobData, profileData, configData, rulePackData] = await Promise.all([
        listScanJobs(numericProjectId),
        listScanProfiles(numericProjectId),
        listScanSourceConfigs(numericProjectId).catch(() => []),
        listTraceRulePacks(numericProjectId).catch(() => []),
      ]);
      setJobs(jobData);
      setProfiles(profileData);
      setScanSourceConfigs(configData);
      setRulePacks(rulePackData);
      const defaults = configData.filter(item => item.enabled && item.defaultSelected).map(item => item.sourceKey);
      setSelectedSources(new Set(defaults.length > 0 ? defaults : defaultSourceKeys));
    } catch {
      showToast('加载项目配置失败', 'error');
    } finally {
      setReloading(false);
    }
  };

  useEffect(() => {
    loadProjectScanData(projectId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const toggleSource = (key: string) => {
    setSelectedSources(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const handleRun = async () => {
    if (!projectId) {
      showToast('请选择项目', 'error');
      return;
    }
    const urls = linkUrls
      .split('\n')
      .map(item => item.trim())
      .filter(Boolean);
    if (scanMode === 'link' && urls.length === 0) {
      showToast('请输入至少一个页面链接', 'error');
      return;
    }
    if (scanMode === 'builtin' && selectedSources.size === 0) {
      showToast('请选择至少一个扫描源', 'error');
      return;
    }
    setRunning(true);
    try {
      await runScan({
        projectId: Number(projectId),
        modelConfigId: selectedModel,
        scanMode: scanMode === 'builtin' ? 'BUILTIN_JSON' : 'URL_LIST',
        sourceKeys: scanMode === 'builtin' ? Array.from(selectedSources) : undefined,
        urls: scanMode === 'link' ? urls : undefined,
      });
      showToast('扫描已完成，页面画像已刷新');
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '启动失败', 'error');
    } finally {
      setRunning(false);
    }
  };

  const resetSourceForm = () => {
    setEditingSourceId(null);
    setSourceForm(emptySourceForm);
  };

  const editSource = (source: ScanSourceConfig) => {
    if (!source.projectId) {
      showToast('全局扫描源不能在项目页编辑', 'error');
      return;
    }
    setEditingSourceId(source.id);
    setSourceForm({
      sourceKey: source.sourceKey,
      sourceLabel: source.sourceLabel,
      sourceType: source.sourceType,
      sourceUrl: source.sourceUrl || '',
      sourceFilePath: source.sourceFilePath || '',
      description: source.description || '',
      defaultSelected: source.defaultSelected,
      enabled: source.enabled,
    });
  };

  const handleSaveSource = async () => {
    if (!projectId) {
      showToast('请先选择项目', 'error');
      return;
    }
    const sourceKey = sourceForm.sourceKey.trim().toUpperCase();
    const sourceLabel = sourceForm.sourceLabel.trim();
    if (!sourceKey || !sourceLabel) {
      showToast('扫描源标识和名称必填', 'error');
      return;
    }
    if (sourceForm.sourceType === 'URL_LIST' && !sourceForm.sourceUrl.trim()) {
      showToast('页面链接扫描源需要填写入口 URL', 'error');
      return;
    }

    const payload = {
      sourceLabel,
      sourceType: sourceForm.sourceType,
      sourceUrl: sourceForm.sourceUrl.trim() || undefined,
      sourceFilePath: sourceForm.sourceFilePath.trim() || undefined,
      defaultSelected: sourceForm.defaultSelected,
      enabled: sourceForm.enabled,
      description: sourceForm.description.trim() || undefined,
    };
    setSavingSource(true);
    try {
      if (editingSourceId) {
        await updateScanSourceConfig(Number(projectId), editingSourceId, payload);
        showToast('扫描源已更新');
      } else {
        await createScanSourceConfig(Number(projectId), {
          sourceKey,
          ...payload,
        });
        showToast('扫描源已创建');
      }
      resetSourceForm();
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '保存扫描源失败', 'error');
    } finally {
      setSavingSource(false);
    }
  };

  const handleToggleSourceConfig = async (source: ScanSourceConfig) => {
    if (!projectId) return;
    if (!source.projectId) {
      showToast('全局扫描源不能在项目页启停', 'error');
      return;
    }
    try {
      if (source.enabled) {
        await disableScanSourceConfig(Number(projectId), source.id);
        showToast('扫描源已停用');
      } else {
        await enableScanSourceConfig(Number(projectId), source.id);
        showToast('扫描源已启用');
      }
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '更新扫描源状态失败', 'error');
    }
  };

  const handleDeleteSource = async (source: ScanSourceConfig) => {
    if (!projectId) return;
    if (!source.projectId) {
      showToast('全局扫描源不能在项目页删除', 'error');
      return;
    }
    if (!window.confirm(`删除扫描源「${source.sourceLabel}」？`)) return;
    try {
      await deleteScanSourceConfig(Number(projectId), source.id);
      if (editingSourceId === source.id) resetSourceForm();
      showToast('扫描源已删除');
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '删除扫描源失败', 'error');
    }
  };

  const resetRulePackForm = () => {
    setEditingRulePackId(null);
    setRulePackForm(emptyRulePackForm);
    setSelectedRulePackTemplate(rulePackTemplates[0].key);
  };

  const applyRulePackTemplate = () => {
    const template = rulePackTemplates.find(item => item.key === selectedRulePackTemplate) || rulePackTemplates[0];
    setRulePackForm(prev => ({
      ...prev,
      packKey: editingRulePackId ? prev.packKey : template.packKey,
      packName: editingRulePackId ? prev.packName : template.packName,
      description: prev.description || template.description,
      configJson: formatRulePackConfig(template.config),
    }));
  };

  const editRulePack = (pack: TraceRulePackConfig) => {
    if (!pack.projectId) {
      showToast('全局规则包不能在项目页编辑', 'error');
      return;
    }
    setEditingRulePackId(pack.id);
    setSelectedRulePackTemplate(rulePackTemplates[0].key);
    setRulePackForm({
      packKey: pack.packKey,
      packName: pack.packName,
      status: pack.status,
      priority: pack.priority,
      description: pack.description || '',
      configJson: pack.configJson || defaultRulePackJson,
    });
  };

  const handleSaveRulePack = async () => {
    if (!projectId) {
      showToast('请先选择项目', 'error');
      return;
    }
    const packKey = rulePackForm.packKey.trim().toUpperCase();
    const packName = rulePackForm.packName.trim();
    if (!packKey || !packName) {
      showToast('规则包标识和名称必填', 'error');
      return;
    }
    if (!rulePackValidation.valid) {
      showToast(rulePackValidation.errors[0] || '规则包 JSON 结构不合法', 'error');
      return;
    }

    const payload = {
      packName,
      packType: 'TRACE_CLEANING',
      status: rulePackForm.status,
      priority: Number(rulePackForm.priority) || 0,
      configJson: rulePackForm.configJson,
      description: rulePackForm.description.trim() || undefined,
    };
    setSavingRulePack(true);
    try {
      if (editingRulePackId) {
        await updateTraceRulePack(Number(projectId), editingRulePackId, payload);
        showToast('规则包已更新');
      } else {
        await createTraceRulePack(Number(projectId), { packKey, ...payload });
        showToast('规则包已创建');
      }
      resetRulePackForm();
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '保存规则包失败', 'error');
    } finally {
      setSavingRulePack(false);
    }
  };

  const handleToggleRulePack = async (pack: TraceRulePackConfig) => {
    if (!projectId) return;
    try {
      if (pack.status === 'ACTIVE') {
        await deactivateTraceRulePack(Number(projectId), pack.id);
        showToast('规则包已停用');
      } else {
        await activateTraceRulePack(Number(projectId), pack.id);
        showToast('规则包已激活');
      }
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '更新规则包状态失败', 'error');
    }
  };

  const handleArchiveRulePack = async (pack: TraceRulePackConfig) => {
    if (!projectId) return;
    try {
      await archiveTraceRulePack(Number(projectId), pack.id);
      showToast('规则包已归档');
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '归档规则包失败', 'error');
    }
  };

  const handleDeleteRulePack = async (pack: TraceRulePackConfig) => {
    if (!projectId) return;
    if (!window.confirm(`删除规则包「${pack.packName}」？`)) return;
    try {
      await deleteTraceRulePack(Number(projectId), pack.id);
      if (editingRulePackId === pack.id) resetRulePackForm();
      showToast('规则包已删除');
      await loadProjectScanData(projectId);
    } catch (error: any) {
      showToast(error?.message || '删除规则包失败', 'error');
    }
  };

  if (loading) {
    return (
      <div className="p-4 animate-fade-in sm:p-6">
        <h1 className="text-xl font-bold text-gray-900 mb-4">系统配置</h1>
        <div className="animate-pulse bg-gray-100 rounded-lg h-48 w-full" />
      </div>
    );
  }

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-6 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">系统配置</h1>
          <p className="text-sm text-gray-500 mt-1">配置扫描源、执行页面画像扫描，并检查扫描结果沉淀情况。</p>
        </div>
        <button
          onClick={() => loadProjectScanData(projectId)}
          disabled={!projectId || reloading}
          className="min-h-10 shrink-0 rounded-lg border border-gray-300 bg-white px-4 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {reloading ? '刷新中...' : '刷新当前项目'}
        </button>
      </div>

      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(0,0.95fr)]">
        <div className="space-y-6">
          <section className="bg-white rounded-lg border border-gray-200 p-5">
            <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <h2 className="text-sm font-semibold text-gray-900">执行扫描</h2>
                <p className="text-xs text-gray-500 mt-1">扫描结果会进入页面画像，并参与后续语义包和业务包沉淀。</p>
              </div>
              <span className="shrink-0 text-xs text-gray-500">{sourceOptions.length} 个可用扫描源</span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">目标项目</label>
                <select
                  value={projectId}
                  onChange={e => setProjectId(e.target.value)}
                  className="w-full h-10 px-3 bg-white border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                >
                  <option value="">选择项目</option>
                  {projects.map(project => (
                    <option key={project.id} value={String(project.id)}>
                      #{project.id} {project.projectName}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">扫描模型</label>
                <select
                  value={selectedModel || ''}
                  onChange={e => setSelectedModel(Number(e.target.value) || undefined)}
                  className="w-full h-10 px-3 bg-white border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                >
                  {models.length === 0 && <option value="">暂无可用模型</option>}
                  {models.map(m => (
                    <option key={m.id} value={m.id}>{m.configName} ({m.modelName})</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="mb-4">
              <label className="block text-xs font-medium text-gray-600 mb-2">扫描方式</label>
              <div className="inline-flex max-w-full flex-wrap rounded-lg border border-gray-200 bg-gray-100 p-1">
                <button
                  type="button"
                  onClick={() => setScanMode('builtin')}
                  className={`h-8 px-4 rounded-md text-sm font-medium transition-colors ${
                    scanMode === 'builtin' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                  }`}
                >
                  配置源
                </button>
                <button
                  type="button"
                  onClick={() => setScanMode('link')}
                  className={`h-8 px-4 rounded-md text-sm font-medium transition-colors ${
                    scanMode === 'link' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                  }`}
                >
                  临时链接
                </button>
              </div>
            </div>

            {scanMode === 'builtin' ? (
              <div className="mb-5">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <label className="block text-xs font-medium text-gray-600">扫描源</label>
                  {sourceOptions.length > 0 && (
                    <button
                      type="button"
                      onClick={() => setSelectedSources(new Set(defaultSourceKeys.length > 0 ? defaultSourceKeys : sourceOptions.map(item => item.key)))}
                      className="text-xs text-gray-500 hover:text-gray-800"
                    >
                      使用默认选择
                    </button>
                  )}
                </div>
                {sourceOptions.length === 0 ? (
                  <div className="rounded-lg border border-dashed border-gray-300 px-4 py-8 text-center text-sm text-gray-500">
                    暂无扫描源。请先在右侧为项目添加 URL 或 JSON 扫描源。
                  </div>
                ) : (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                    {sourceOptions.map(source => (
                      <button
                        type="button"
                        key={source.key}
                        onClick={() => toggleSource(source.key)}
                        className={`text-left rounded-lg border px-3 py-3 transition-colors ${
                          selectedSources.has(source.key)
                            ? 'border-gray-900 bg-gray-900 text-white'
                            : 'border-gray-200 bg-white text-gray-900 hover:border-gray-300'
                        }`}
                      >
                        <div className="flex items-start justify-between gap-3">
                          <span className="min-w-0 truncate text-sm font-medium">{source.label}</span>
                          <span className={`shrink-0 text-[11px] px-2 py-0.5 rounded-full ${
                            selectedSources.has(source.key) ? 'bg-white/15 text-white' : 'bg-gray-100 text-gray-600'
                          }`}>
                            {source.scope === 'PROJECT' ? '项目' : source.scope === 'GLOBAL' ? '全局' : '扩展'}
                          </span>
                        </div>
                        <div className={`mt-1 text-xs truncate ${selectedSources.has(source.key) ? 'text-gray-300' : 'text-gray-500'}`}>
                          {sourceTypeLabels[source.sourceType || ''] || source.sourceType || source.key}
                        </div>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div className="mb-5">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">页面链接</label>
                <textarea
                  value={linkUrls}
                  onChange={e => setLinkUrls(e.target.value)}
                  rows={5}
                  placeholder={'每行一个链接，例如：\nhttps://example.com/app/orders\nhttps://example.com/app/customers'}
                  className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm font-mono focus:border-gray-500 outline-none resize-none"
                />
              </div>
            )}

            <button
              onClick={handleRun}
              disabled={running || !projectId}
              className="h-10 px-5 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {running ? '执行中...' : '执行扫描'}
            </button>
          </section>

          <section className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100 bg-gray-50">
              <h2 className="text-sm font-semibold text-gray-900">扫描历史</h2>
            </div>
            {!projectId ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">先选择项目，再查看扫描历史</div>
            ) : jobs.length === 0 ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无扫描记录</div>
            ) : (
              <div className="divide-y divide-gray-100">
                {jobs.map((job: any) => (
                  <div key={job.id} className="flex items-center gap-4 px-5 py-3">
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-gray-900 truncate">{job.jobName || `扫描 #${job.id}`}</div>
                      <div className="text-xs text-gray-500">画像 {job.profileCount || 0} 个 · {compactDate(job.createdAt)}</div>
                    </div>
                    <span className={`text-[11px] font-medium px-2 py-0.5 rounded-full border ${statusClass(job.status)}`}>
                      {statusLabel(job.status)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>

        <div className="space-y-6">
          <section className="bg-white rounded-lg border border-gray-200 p-5">
            <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <h2 className="text-sm font-semibold text-gray-900">项目扫描源</h2>
                <p className="text-xs text-gray-500 mt-1">新业务优先在这里配置扫描入口，不需要新增 Java 扫描源。</p>
              </div>
              <button
                type="button"
                onClick={resetSourceForm}
                disabled={!projectId}
                className="h-8 shrink-0 px-3 rounded-lg border border-gray-300 bg-white text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                新建
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">扫描源标识</label>
                <input
                  value={sourceForm.sourceKey}
                  onChange={e => setSourceForm(prev => ({ ...prev, sourceKey: e.target.value.replace(/\s+/g, '_').toUpperCase() }))}
                  disabled={Boolean(editingSourceId)}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none disabled:bg-gray-100"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">显示名称</label>
                <input
                  value={sourceForm.sourceLabel}
                  onChange={e => setSourceForm(prev => ({ ...prev, sourceLabel: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">来源类型</label>
                <select
                  value={sourceForm.sourceType}
                  onChange={e => setSourceForm(prev => ({ ...prev, sourceType: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                >
                  <option value="URL_LIST">页面链接</option>
                  <option value="BUILTIN_JSON">JSON 文件</option>
                  <option value="PROJECT_ASSET">项目资产</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">入口 URL</label>
                <input
                  value={sourceForm.sourceUrl}
                  onChange={e => setSourceForm(prev => ({ ...prev, sourceUrl: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                />
              </div>
              <div className="md:col-span-2">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">JSON 文件路径</label>
                <input
                  value={sourceForm.sourceFilePath}
                  onChange={e => setSourceForm(prev => ({ ...prev, sourceFilePath: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm font-mono focus:border-gray-500 outline-none"
                />
              </div>
              <div className="md:col-span-2">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">描述</label>
                <textarea
                  value={sourceForm.description}
                  onChange={e => setSourceForm(prev => ({ ...prev, description: e.target.value }))}
                  rows={2}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none resize-none"
                />
              </div>
            </div>

            <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-wrap items-center gap-4">
                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="checkbox"
                    checked={sourceForm.defaultSelected}
                    onChange={e => setSourceForm(prev => ({ ...prev, defaultSelected: e.target.checked }))}
                    className="rounded border-gray-300 text-gray-900 focus:ring-gray-900"
                  />
                  默认选中
                </label>
                <label className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="checkbox"
                    checked={sourceForm.enabled}
                    onChange={e => setSourceForm(prev => ({ ...prev, enabled: e.target.checked }))}
                    className="rounded border-gray-300 text-gray-900 focus:ring-gray-900"
                  />
                  启用
                </label>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {editingSourceId && (
                  <button
                    type="button"
                    onClick={resetSourceForm}
                    className="h-9 px-3 rounded-lg border border-gray-300 text-sm text-gray-700 hover:bg-gray-50"
                  >
                    取消编辑
                  </button>
                )}
                <button
                  type="button"
                  onClick={handleSaveSource}
                  disabled={!projectId || savingSource}
                  className="h-9 px-4 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-50"
                >
                  {savingSource ? '保存中...' : editingSourceId ? '保存修改' : '创建扫描源'}
                </button>
              </div>
            </div>
          </section>

          <section className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100 bg-gray-50">
              <h2 className="text-sm font-semibold text-gray-900">扫描源清单</h2>
            </div>
            {!projectId ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">先选择项目，再管理扫描源</div>
            ) : scanSourceConfigs.length === 0 ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无项目扫描源</div>
            ) : (
              <div className="divide-y divide-gray-100">
                {scanSourceConfigs.map(source => (
                  <div key={source.id} className="px-5 py-4">
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="min-w-0 truncate text-sm font-semibold text-gray-900">{source.sourceLabel}</span>
                          <span className="text-[11px] px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
                            {source.projectId ? '项目' : '全局只读'}
                          </span>
                          {source.defaultSelected && (
                            <span className="text-[11px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-700">默认</span>
                          )}
                          <span className={`text-[11px] px-2 py-0.5 rounded-full ${
                            source.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-gray-100 text-gray-500'
                          }`}>
                            {source.enabled ? '启用' : '停用'}
                          </span>
                        </div>
                        <div className="mt-1 break-all text-xs text-gray-500">
                          {source.sourceKey} · {sourceTypeLabels[source.sourceType] || source.sourceType}
                        </div>
                        <div className="mt-1 break-all text-xs text-gray-400">
                          {source.sourceUrl || source.sourceFilePath || source.description || '未配置入口'}
                        </div>
                      </div>
                      <div className="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
                        <button
                          onClick={() => editSource(source)}
                          disabled={!source.projectId}
                          className="text-xs text-gray-600 hover:text-gray-900 disabled:text-gray-300 disabled:cursor-not-allowed"
                        >
                          编辑
                        </button>
                        <button
                          onClick={() => handleToggleSourceConfig(source)}
                          disabled={!source.projectId}
                          className="text-xs text-gray-600 hover:text-gray-900 disabled:text-gray-300 disabled:cursor-not-allowed"
                        >
                          {source.enabled ? '停用' : '启用'}
                        </button>
                        <button
                          onClick={() => handleDeleteSource(source)}
                          disabled={!source.projectId}
                          className="text-xs text-red-600 hover:text-red-700 disabled:text-gray-300 disabled:cursor-not-allowed"
                        >
                          删除
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="bg-white rounded-lg border border-gray-200 p-5">
            <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <h2 className="text-sm font-semibold text-gray-900">项目轨迹规则包</h2>
                <p className="text-xs text-gray-500 mt-1">用 JSON 规则调整输入、选择、点击描述；ACTIVE 后参与当前项目轨迹清洗。</p>
              </div>
              <button
                type="button"
                onClick={resetRulePackForm}
                disabled={!projectId}
                className="h-9 shrink-0 px-3 rounded-lg border border-gray-300 bg-white text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                新建
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">规则包标识</label>
                <input
                  value={rulePackForm.packKey}
                  onChange={e => setRulePackForm(prev => ({ ...prev, packKey: e.target.value.replace(/\s+/g, '_').toUpperCase() }))}
                  disabled={Boolean(editingRulePackId)}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none disabled:bg-gray-100"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">显示名称</label>
                <input
                  value={rulePackForm.packName}
                  onChange={e => setRulePackForm(prev => ({ ...prev, packName: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">状态</label>
                <select
                  value={rulePackForm.status}
                  onChange={e => setRulePackForm(prev => ({ ...prev, status: e.target.value }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                >
                  <option value="DRAFT">草稿</option>
                  <option value="ACTIVE">生效</option>
                  <option value="INACTIVE">停用</option>
                  <option value="ARCHIVED">归档</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1.5">优先级</label>
                <input
                  type="number"
                  value={rulePackForm.priority}
                  onChange={e => setRulePackForm(prev => ({ ...prev, priority: Number(e.target.value) || 0 }))}
                  className="w-full h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                />
              </div>
              <div className="md:col-span-2">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">规则模板</label>
                <div className="flex flex-col sm:flex-row gap-2">
                  <select
                    value={selectedRulePackTemplate}
                    onChange={e => setSelectedRulePackTemplate(e.target.value)}
                    className="flex-1 h-10 px-3 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none"
                  >
                    {rulePackTemplates.map(template => (
                      <option key={template.key} value={template.key}>{template.label}</option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={applyRulePackTemplate}
                    className="h-10 px-3 rounded-lg border border-gray-300 bg-white text-sm text-gray-700 hover:bg-gray-50"
                  >
                    应用模板
                  </button>
                </div>
                <div className="mt-1.5 text-xs text-gray-500">
                  {(rulePackTemplates.find(item => item.key === selectedRulePackTemplate) || rulePackTemplates[0]).description}
                </div>
              </div>
              <div className="md:col-span-2">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">描述</label>
                <textarea
                  value={rulePackForm.description}
                  onChange={e => setRulePackForm(prev => ({ ...prev, description: e.target.value }))}
                  rows={2}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:border-gray-500 outline-none resize-none"
                />
              </div>
              <div className="md:col-span-2">
                <label className="block text-xs font-medium text-gray-600 mb-1.5">规则 JSON</label>
                <textarea
                  value={rulePackForm.configJson}
                  onChange={e => setRulePackForm(prev => ({ ...prev, configJson: e.target.value }))}
                  rows={12}
                  spellCheck={false}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-xs font-mono leading-5 focus:border-gray-500 outline-none resize-y"
                />
              </div>
              <div className="md:col-span-2 grid grid-cols-1 lg:grid-cols-2 gap-4 border-t border-gray-100 pt-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-semibold text-gray-700">结构检查</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded-full ${
                      rulePackValidation.valid ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'
                    }`}>
                      {rulePackValidation.valid ? '可保存' : '需修正'}
                    </span>
                  </div>
                  <div className="mt-2 space-y-1 text-xs">
                    {rulePackValidation.errors.length === 0 && rulePackValidation.warnings.length === 0 ? (
                      <div className="text-gray-500">结构正常，规则包名称：{rulePackValidation.parsedName}</div>
                    ) : (
                      <>
                        {rulePackValidation.errors.slice(0, 3).map(item => (
                          <div key={item} className="text-red-600">{item}</div>
                        ))}
                        {rulePackValidation.warnings.slice(0, 3).map(item => (
                          <div key={item} className="text-amber-700">{item}</div>
                        ))}
                      </>
                    )}
                  </div>
                </div>
                <div className="lg:border-l lg:border-gray-100 lg:pl-4">
                  <div className="text-xs font-semibold text-gray-700">规则预览</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-gray-600">
                    <span className="px-2 py-0.5 rounded-full bg-gray-100">输入 {rulePackValidation.counts.inputRules || 0}</span>
                    <span className="px-2 py-0.5 rounded-full bg-gray-100">选择 {rulePackValidation.counts.changeRules || 0}</span>
                    <span className="px-2 py-0.5 rounded-full bg-gray-100">点击 {rulePackValidation.counts.clickRules || 0}</span>
                    <span className="px-2 py-0.5 rounded-full bg-gray-100">工作流 {rulePackValidation.counts.workflows || 0}</span>
                  </div>
                  <div className="mt-2 space-y-1 text-xs text-gray-500">
                    {rulePackValidation.samples.length === 0 ? (
                      <div>暂无模板输出样例</div>
                    ) : rulePackValidation.samples.map(item => (
                      <div key={item} className="truncate">{item}</div>
                    ))}
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
              {editingRulePackId && (
                <button
                  type="button"
                  onClick={resetRulePackForm}
                  className="h-9 px-3 rounded-lg border border-gray-300 text-sm text-gray-700 hover:bg-gray-50"
                >
                  取消编辑
                </button>
              )}
              <button
                type="button"
                onClick={handleSaveRulePack}
                disabled={!projectId || savingRulePack}
                className="h-9 px-4 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800 disabled:opacity-50"
              >
                {savingRulePack ? '保存中...' : editingRulePackId ? '保存修改' : '创建规则包'}
              </button>
            </div>
          </section>

          <section className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100 bg-gray-50">
              <h2 className="text-sm font-semibold text-gray-900">规则包清单</h2>
            </div>
            {!projectId ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">先选择项目，再管理规则包</div>
            ) : rulePacks.length === 0 ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无项目规则包</div>
            ) : (
              <div className="divide-y divide-gray-100">
                {rulePacks.map(pack => (
                  <div key={pack.id} className="px-5 py-4">
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-semibold text-gray-900 truncate">{pack.packName}</span>
                          <span className="text-[11px] px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
                            {pack.projectId ? '项目' : '全局'}
                          </span>
                          <span className={`text-[11px] px-2 py-0.5 rounded-full ${
                            pack.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-700' :
                            pack.status === 'DRAFT' ? 'bg-amber-50 text-amber-700' :
                            pack.status === 'ARCHIVED' ? 'bg-red-50 text-red-700' :
                            'bg-gray-100 text-gray-500'
                          }`}>
                            {ruleStatusLabels[pack.status] || pack.status}
                          </span>
                        </div>
                        <div className="mt-1 break-all text-xs text-gray-500">
                          {pack.packKey} · v{pack.version} · 优先级 {pack.priority}
                        </div>
                        <div className="mt-1 break-words text-xs text-gray-400">
                          {pack.description || '未填写描述'}
                        </div>
                      </div>
                      <div className="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
                        <button
                          onClick={() => editRulePack(pack)}
                          disabled={!pack.projectId}
                          className="text-xs text-gray-600 hover:text-gray-900 disabled:text-gray-300"
                        >
                          编辑
                        </button>
                        <button
                          onClick={() => handleToggleRulePack(pack)}
                          disabled={!pack.projectId}
                          className="text-xs text-gray-600 hover:text-gray-900 disabled:text-gray-300"
                        >
                          {pack.status === 'ACTIVE' ? '停用' : '激活'}
                        </button>
                        {pack.status !== 'ARCHIVED' && (
                          <button
                            onClick={() => handleArchiveRulePack(pack)}
                            disabled={!pack.projectId}
                            className="text-xs text-gray-600 hover:text-gray-900 disabled:text-gray-300"
                          >
                            归档
                          </button>
                        )}
                        <button
                          onClick={() => handleDeleteRulePack(pack)}
                          disabled={!pack.projectId}
                          className="text-xs text-red-600 hover:text-red-700 disabled:text-gray-300"
                        >
                          删除
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="bg-white rounded-lg border border-gray-200 overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100 bg-gray-50">
              <h2 className="text-sm font-semibold text-gray-900">页面画像</h2>
            </div>
            {!projectId ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">先选择项目，再查看页面画像</div>
            ) : profiles.length === 0 ? (
              <div className="px-5 py-12 text-center text-gray-400 text-sm">暂无页面画像</div>
            ) : (
              <div className="divide-y divide-gray-100">
                {profiles.slice(0, 12).map((profile: any) => (
                  <div key={profile.id} className="flex items-start gap-4 px-5 py-3">
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-gray-900 truncate">
                        {profile.pageLabel || profile.pageTitle || profile.routePattern || `画像 #${profile.id}`}
                      </div>
                      <div className="mt-1 break-all text-xs text-gray-500">
                        {profile.pageUrl || profile.routePath || profile.routePattern || profile.pageKey || ''}
                      </div>
                    </div>
                    <span className="shrink-0 text-[11px] font-medium px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
                      {profile.sourceKey || 'PROFILE'}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
