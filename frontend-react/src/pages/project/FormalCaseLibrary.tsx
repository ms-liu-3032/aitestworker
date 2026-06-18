import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import RichList from '../../components/RichList';
import MultiSelectFilter from '../../components/MultiSelectFilter';
import { listFormalCases, type FormalCase } from '../../services/api';

const priorityConfig: Record<string, { bg: string; text: string }> = {
  P0: { bg: 'bg-red-50', text: 'text-red-600' },
  P1: { bg: 'bg-orange-50', text: 'text-orange-600' },
  P2: { bg: 'bg-yellow-50', text: 'text-yellow-600' },
  P3: { bg: 'bg-gray-100', text: 'text-gray-500' },
};

type SourceFilter = 'GENERATION' | 'TRACE' | 'MANUAL';

function isFormalCaseActive(status: string | null | undefined) {
  return status === 'SUBMITTED' || status === 'ACTIVE';
}

function getFormalCaseSourceType(testCase: FormalCase): SourceFilter {
  return testCase.sourceTraceGroupId || testCase.sourceTraceSessionId || testCase.sourceIssueClipId
    ? 'TRACE'
    : 'GENERATION';
}

function formalCaseSourceLabel(sourceType: SourceFilter) {
  return sourceType === 'TRACE' ? '轨迹回放' : '需求生成';
}

function formalCaseSourceClass(sourceType: SourceFilter) {
  return sourceType === 'TRACE'
    ? 'bg-purple-50 text-purple-700'
    : 'bg-sky-50 text-sky-700';
}

export default function FormalCaseLibrary() {
  const { projectId } = useParams<{ projectId: string }>();
  const { showToast } = useApp();
  const [cases, setCases] = useState<FormalCase[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [moduleFilter, setModuleFilter] = useState<string[]>([]);
  const [priorityFilter, setPriorityFilter] = useState<string[]>([]);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [detailCase, setDetailCase] = useState<FormalCase | null>(null);
  const [exporting, setExporting] = useState(false);

  const loadCases = async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listFormalCases(Number(projectId));
      setCases(data);
    } catch (error: any) {
      showToast(error.message || '加载正式用例失败', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCases();
  }, [projectId]);

  const moduleOptions = useMemo(
    () => Array.from(new Set(cases.map(item => item.moduleName).filter(Boolean))) as string[],
    [cases]
  );

  const filteredCases = useMemo(() => {
    const term = keyword.trim().toLowerCase();
    return cases.filter(item => {
      const sourceType = getFormalCaseSourceType(item);
      if (moduleFilter.length > 0 && !moduleFilter.includes(item.moduleName || '')) return false;
      if (priorityFilter.length > 0 && !priorityFilter.includes(item.priority || '')) return false;
      if (statusFilter.length > 0) {
        const normalizedStatus = item.caseStatus === 'DEPRECATED' ? 'DEPRECATED' : isFormalCaseActive(item.caseStatus) ? 'ACTIVE' : item.caseStatus;
        if (!statusFilter.includes(normalizedStatus)) return false;
      }
      if (sourceFilter.length > 0 && !sourceFilter.includes(sourceType)) return false;
      if (!term) return true;
      return [
        item.caseTitle,
        item.caseNo || '',
        item.moduleName || '',
        item.caseType || '',
        item.caseScope || '',
        formalCaseSourceLabel(sourceType),
      ].some(value => value.toLowerCase().includes(term));
    });
  }, [cases, keyword, moduleFilter, priorityFilter, statusFilter, sourceFilter]);

  const counts = useMemo(() => ({
    total: cases.length,
    active: cases.filter(item => isFormalCaseActive(item.caseStatus)).length,
    deprecated: cases.filter(item => item.caseStatus === 'DEPRECATED').length,
    exported: cases.filter(item => item.exportedAt).length,
    generation: cases.filter(item => getFormalCaseSourceType(item) === 'GENERATION').length,
    trace: cases.filter(item => getFormalCaseSourceType(item) === 'TRACE').length,
  }), [cases]);

  const toggleSelect = (id: number) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedIds.size === filteredCases.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(filteredCases.map(c => c.id)));
    }
  };

  const handleExport = async () => {
    if (!projectId) return;
    setExporting(true);
    try {
      const ids = selectedIds.size > 0 ? Array.from(selectedIds) : undefined;
      const response = await fetch(`/api/projects/${projectId}/export/formal-cases`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('aitest_token')}`,
        },
        body: JSON.stringify(ids),
      });
      if (!response.ok) throw new Error('导出失败');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '正式用例库.xmind';
      a.click();
      URL.revokeObjectURL(url);
      showToast(`已导出 ${ids ? ids.length : filteredCases.length} 条用例`);
    } catch (error: any) {
      showToast(error.message || '导出失败', 'error');
    } finally {
      setExporting(false);
    }
  };

  const columns = [
    { key: 'select', label: '', width: '40px' },
    { key: 'no', label: '编号', width: '90px' },
    { key: 'title', label: '用例名称' },
    { key: 'module', label: '模块', width: '100px' },
    { key: 'priority', label: '优先级', width: '60px' },
    { key: 'status', label: '状态', width: '70px' },
    { key: 'updated', label: '更新时间', width: '90px' },
  ];

  return (
    <div className="p-4 animate-fade-in sm:p-6">
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight">正式用例库</h1>
          <p className="text-sm text-gray-500 mt-1">查看项目已沉淀的正式用例，并按模块、优先级和状态快速定位。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 sm:justify-end">
          <button
            onClick={handleExport}
            disabled={exporting}
            className="min-h-10 shrink-0 rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
          >
            {exporting ? '导出中...' : `导出 (${selectedIds.size > 0 ? selectedIds.size : filteredCases.length})`}
          </button>
          <button
            onClick={loadCases}
            className="min-h-10 shrink-0 rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-300 hover:bg-gray-50"
          >
            刷新列表
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-5 gap-3 mb-4">
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">正式用例总数</div>
          <div className="text-2xl font-semibold text-gray-900 mt-1">{counts.total}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">生效</div>
          <div className="text-2xl font-semibold text-green-700 mt-1">{counts.active}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">已弃用</div>
          <div className="text-2xl font-semibold text-gray-700 mt-1">{counts.deprecated}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">已导出</div>
          <div className="text-2xl font-semibold text-blue-700 mt-1">{counts.exported}</div>
        </div>
        <div className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="text-xs text-gray-500">来源分布</div>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-sky-50 text-sky-700">
              需求生成 {counts.generation}
            </span>
            <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-purple-50 text-purple-700">
              轨迹回放 {counts.trace}
            </span>
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-5 gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">关键词</label>
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="搜索编号、名称、模块..."
              className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none"
            />
          </div>
          <MultiSelectFilter label="模块" options={moduleOptions} value={moduleFilter} onChange={setModuleFilter} />
          <MultiSelectFilter label="优先级" options={['P0', 'P1', 'P2', 'P3']} value={priorityFilter} onChange={setPriorityFilter} />
          <MultiSelectFilter label="状态" options={['ACTIVE', 'DEPRECATED']} value={statusFilter} onChange={setStatusFilter} />
          <MultiSelectFilter label="来源" options={['GENERATION', 'TRACE', 'MANUAL']} value={sourceFilter} onChange={setSourceFilter} />
        </div>
      </div>

      <div className="flex items-center gap-2 mb-2">
        <input
          type="checkbox"
          checked={selectedIds.size === filteredCases.length && filteredCases.length > 0}
          onChange={toggleSelectAll}
          className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-xs text-gray-500">全选</span>
        {selectedIds.size > 0 && (
          <span className="text-xs text-blue-600">已选 {selectedIds.size} 条</span>
        )}
      </div>

      <RichList
        items={filteredCases}
        columns={columns}
        loading={loading}
        emptyText="暂无匹配的正式用例"
        renderRow={(testCase: FormalCase) => {
          const sourceType = getFormalCaseSourceType(testCase);
          return (
          <>
            <div className="flex items-center flex-shrink-0" style={{ width: '40px' }}>
              <input
                type="checkbox"
                checked={selectedIds.has(testCase.id)}
                onChange={() => toggleSelect(testCase.id)}
                className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
            </div>
            <div className="flex-shrink-0 font-mono text-xs text-gray-500" style={{ width: '90px' }}>
              {testCase.caseNo || `#${testCase.id}`}
            </div>
            <div className="flex-1 min-w-0">
              <button
                onClick={() => setDetailCase(testCase)}
                className="text-sm font-medium text-gray-900 truncate hover:text-blue-600 text-left"
              >
                {testCase.caseTitle}
              </button>
              <div className="flex items-center gap-2 mt-1 min-w-0 text-xs text-gray-500">
                <span className={`inline-flex items-center px-2 py-0.5 rounded font-medium ${formalCaseSourceClass(sourceType)}`}>
                  {formalCaseSourceLabel(sourceType)}
                </span>
                <span className="truncate">
                  {testCase.moduleName || '未分模块'} · {testCase.caseScope || testCase.caseType || '未分类'}
                </span>
              </div>
            </div>
            <div className="flex-shrink-0 text-sm text-gray-600 truncate" style={{ width: '100px' }}>{testCase.moduleName || '-'}</div>
            <div className="flex-shrink-0" style={{ width: '60px' }}>
              {testCase.priority && (
                <span className={`text-[11px] font-semibold px-2 py-0.5 rounded ${priorityConfig[testCase.priority]?.bg || 'bg-gray-100'} ${priorityConfig[testCase.priority]?.text || 'text-gray-500'}`}>
                  {testCase.priority}
                </span>
              )}
            </div>
            <div className="flex-shrink-0" style={{ width: '70px' }}>
              <span className={`text-[11px] font-medium px-2 py-0.5 rounded ${
                isFormalCaseActive(testCase.caseStatus) ? 'bg-green-50 text-green-700' : 'bg-gray-100 text-gray-600'
              }`}>
                {isFormalCaseActive(testCase.caseStatus) ? '生效' : testCase.caseStatus === 'DEPRECATED' ? '已弃用' : testCase.caseStatus}
              </span>
            </div>
            <div className="flex-shrink-0 text-xs text-gray-500 font-mono" style={{ width: '90px' }}>
              {new Date(testCase.updatedAt).toLocaleDateString('zh-CN')}
            </div>
          </>
          );
        }}
      />

      {/* 用例详情弹窗 */}
      {detailCase && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={() => setDetailCase(null)}>
          <div className="mx-4 w-full max-w-2xl max-h-[calc(100vh-3rem)] overflow-y-auto rounded-xl bg-white p-5 sm:p-6" onClick={e => e.stopPropagation()}>
            <div className="mb-4 flex items-center justify-between gap-3">
              <h3 className="min-w-0 text-lg font-semibold text-gray-900">用例详情</h3>
              <button onClick={() => setDetailCase(null)} className="text-gray-400 hover:text-gray-600">✕</button>
            </div>
            <div className="space-y-4">
              <div>
                <label className="text-xs text-gray-500">编号</label>
                <p className="break-all text-sm font-mono">{detailCase.caseNo || `#${detailCase.id}`}</p>
              </div>
              <div>
                <label className="text-xs text-gray-500">用例名称</label>
                <p className="break-words text-sm font-medium">{detailCase.caseTitle}</p>
              </div>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <div>
                  <label className="text-xs text-gray-500">模块</label>
                  <p className="text-sm">{detailCase.moduleName || '-'}</p>
                </div>
                <div>
                  <label className="text-xs text-gray-500">优先级</label>
                  <p className="text-sm">{detailCase.priority || '-'}</p>
                </div>
                <div>
                  <label className="text-xs text-gray-500">状态</label>
                  <p className="text-sm">{isFormalCaseActive(detailCase.caseStatus) ? '生效' : detailCase.caseStatus === 'DEPRECATED' ? '已弃用' : detailCase.caseStatus}</p>
                </div>
                <div>
                  <label className="text-xs text-gray-500">来源</label>
                  <p className="text-sm">{formalCaseSourceLabel(getFormalCaseSourceType(detailCase))}</p>
                </div>
              </div>
              {detailCase.precondition && (
                <div>
                  <label className="text-xs text-gray-500">前置条件</label>
                  <p className="break-words text-sm whitespace-pre-wrap">{detailCase.precondition}</p>
                </div>
              )}
              {detailCase.steps && (
                <div>
                  <label className="text-xs text-gray-500">测试步骤</label>
                  <div className="break-words text-sm whitespace-pre-wrap bg-gray-50 p-3 rounded-lg">{detailCase.steps}</div>
                </div>
              )}
              {detailCase.expectedResult && (
                <div>
                  <label className="text-xs text-gray-500">预期结果</label>
                  <div className="break-words text-sm whitespace-pre-wrap bg-gray-50 p-3 rounded-lg">{detailCase.expectedResult}</div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
