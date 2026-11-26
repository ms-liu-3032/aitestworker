import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useApp } from '../../context/AppContext';
import {
  approveAutomationVersion, batchApproveAutomationVersions, batchPublishAutomationAssets, archiveAutomationAsset, discardAutomationCandidate,
  getAutomationApiEvidence, getAutomationVersion, listAutomationAssets, listAutomationRuns,
  proposeAutomationVersion, publishAutomationAsset, unblockAutomationAsset,
  listUiCoverageTasks, confirmUiCoverageTask, listPendingUiExecutionRules, approveUiExecutionRule,
  type AutomationApiEvidence, type AutomationAsset, type AutomationRun, type AutomationVersion,
  type UiCoverageVerdict, type UiExecutionRule,
} from '../../services/api';

const colors: Record<string, string> = {
  VERIFIED: 'bg-green-50 text-green-700', DRAFT: 'bg-gray-100 text-gray-600',
  DEGRADED: 'bg-amber-50 text-amber-700', BLOCKED: 'bg-red-50 text-red-700',
  ARCHIVED: 'bg-gray-200 text-gray-600',
};

function approvalBlocker(asset: AutomationAsset): string | null {
  if (asset.caseMissing) return '关联用例已不存在；请舍弃此资产并为现有用例建立映射';
  if (asset.caseStale) return '关联用例已改版；须生成候选版并重新执行验证';
  if (asset.status === 'BLOCKED') return '资产已阻断；须先人工解除并重新验证';
  if (asset.status === 'DEGRADED') return asset.latestVersion > asset.currentVersion
    ? `定位失效；请在 DSH 验证候选 v${asset.latestVersion}` : '定位失效；请修复映射并重新验证';
  if (asset.status === 'DRAFT') return asset.latestVersion > asset.currentVersion
    ? `请在 DSH 验证候选 v${asset.latestVersion}` : '执行映射尚未验证通过';
  if (asset.status === 'ARCHIVED') return '资产已舍弃';
  if (asset.status !== 'VERIFIED') return '执行映射尚未验证通过';
  return null;
}

function publicationBlocker(asset: AutomationAsset): string | null {
  const blocker = approvalBlocker(asset);
  if (blocker) return blocker;
  if (asset.reviewStatus !== 'APPROVED') return '执行映射须先审核通过';
  if (asset.caseKind !== 'FORMAL') return '个人草稿须先提交正式用例';
  return null;
}

export default function AutomationAssets() {
  const { projectId } = useParams<{ projectId: string }>();
  const [params] = useSearchParams();
  const requestedAssetId = Number(params.get('assetId'));
  const openedFromLink = useRef<number | null>(null);
  const project = Number(projectId);
  const { showToast } = useApp();
  const [items, setItems] = useState<AutomationAsset[]>([]);
  const [selected, setSelected] = useState<AutomationAsset | null>(null);
  const [version, setVersion] = useState<AutomationVersion | null>(null);
  const [candidate, setCandidate] = useState<AutomationVersion | null>(null);
  const [runs, setRuns] = useState<AutomationRun[]>([]);
  const [apiEvidence, setApiEvidence] = useState<AutomationApiEvidence | null>(null);
  const [typeFilter, setTypeFilter] = useState<'ALL' | 'UI' | 'API'>('ALL');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editJson, setEditJson] = useState('');
  const [changeReason, setChangeReason] = useState('');
  const [checkedIds, setCheckedIds] = useState<number[]>([]);
  const [selectionMode, setSelectionMode] = useState<'REVIEW' | 'PUBLISH'>('REVIEW');
  const [coverageTasks, setCoverageTasks] = useState<UiCoverageVerdict[]>([]);
  const [pendingRules, setPendingRules] = useState<UiExecutionRule[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [latest, coverage, rules] = await Promise.all([listAutomationAssets(project), listUiCoverageTasks(project),
        listPendingUiExecutionRules(project).catch(() => [] as UiExecutionRule[])]);
      setItems(latest);
      setCoverageTasks(coverage);
      setPendingRules(rules);
      setCheckedIds(ids => ids.filter(id => latest.some(asset => asset.id === id
        && (selectionMode === 'REVIEW' ? !approvalBlocker(asset) && asset.reviewStatus === 'PENDING'
          : !publicationBlocker(asset)))));
    }
    catch (error: any) { showToast(error.message || '加载自动化资产失败', 'error'); }
    finally { setLoading(false); }
  }, [project, showToast, selectionMode]);

  useEffect(() => { refresh(); }, [refresh]);

  const open = async (asset: AutomationAsset) => {
    setSelected(asset);
    setEditing(false);
    setVersion(null);
    setCandidate(null);
    setRuns([]);
    setApiEvidence(null);
    try {
      const [current, history, pending] = await Promise.all([
        getAutomationVersion(project, asset.id, asset.currentVersion),
        listAutomationRuns(project, asset.id),
        asset.latestVersion > asset.currentVersion
          ? getAutomationVersion(project, asset.id, asset.latestVersion) : Promise.resolve(null),
      ]);
      setVersion(current);
      setCandidate(pending);
      setEditJson(JSON.stringify(current.mapping, null, 2));
      setRuns(history);
    } catch (error: any) { showToast(error.message || '加载版本或运行记录失败', 'error'); }
  };

  useEffect(() => {
    if (loading || requestedAssetId <= 0 || openedFromLink.current === requestedAssetId) return;
    const target = items.find(item => item.id === requestedAssetId);
    if (target) {
      openedFromLink.current = requestedAssetId;
      void open(target);
    }
  }, [loading, items, requestedAssetId]);

  const showApiEvidence = async (assetId: number, evidenceId: number) => {
    try { setApiEvidence(await getAutomationApiEvidence(project, assetId, evidenceId)); }
    catch (error: any) { showToast(error.message || '加载接口证据失败', 'error'); }
  };

  const revise = async () => {
    if (!selected || !version) return;
    let mapping: Record<string, unknown>;
    try { mapping = JSON.parse(editJson); }
    catch { showToast('映射内容不是有效 JSON', 'error'); return; }
    setBusy(true);
    try {
      const candidate = await proposeAutomationVersion(project, selected.id, {
        requestKey: globalThis.crypto?.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        expectedVersion: selected.currentVersion, changeReason, mapping,
      });
      showToast(`修订候选 v${candidate.versionNo} 已保存，请在 DSH 执行验证`);
      setEditing(false);
      await refresh();
    } catch (error: any) { showToast(error.message || '修订失败', 'error'); }
    finally { setBusy(false); }
  };

  const revalidateCase = async () => {
    if (!selected || !version || !selected.caseNo || selected.caseMissing || candidate?.status === 'DRAFT') return;
    const mapping = { ...version.mapping, caseNo: selected.caseNo } as Record<string, unknown>;
    setBusy(true);
    try {
      const next = await proposeAutomationVersion(project, selected.id, {
        requestKey: globalThis.crypto?.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        expectedVersion: selected.currentVersion,
        changeReason: '关联用例已更新，复制当前执行映射并重新验证', mapping,
      });
      showToast(`已生成候选 v${next.versionNo}，请在 DSH 重新验证资产 #${selected.id}`);
      setSelected(null);
      await refresh();
    } catch (error: any) { showToast(error.message || '生成复验候选失败', 'error'); }
    finally { setBusy(false); }
  };

  const act = async (kind: 'unblock' | 'publish' | 'approve' | 'archive' | 'discard') => {
    if (!selected) return;
    let reason = '';
    if (kind === 'archive' || kind === 'discard') {
      const answer = window.prompt(kind === 'archive' ? '请输入舍弃整个自动化资产的原因（保留历史和证据）' : '请输入舍弃待验证版本的原因');
      if (answer === null) return;
      reason = answer.trim();
      if (reason.length < 2) { showToast('请填写至少 2 字的舍弃原因', 'error'); return; }
    }
    setBusy(true);
    try {
      if (kind === 'unblock') {
        await unblockAutomationAsset(project, selected.id);
        showToast('已人工解除阻断');
      } else if (kind === 'approve') {
        await approveAutomationVersion(project, selected.id, selected.currentVersion);
        showToast('当前映射已审核通过，可供日常复跑');
      } else if (kind === 'discard' && candidate) {
        await discardAutomationCandidate(project, selected.id, candidate.versionNo, reason);
        showToast('待验证版本已舍弃');
      } else if (kind === 'archive') {
        await archiveAutomationAsset(project, selected.id, reason);
        showToast('自动化资产已舍弃，历史版本和证据保留');
      } else if (kind === 'publish') {
        const publicationId = await publishAutomationAsset(project, selected.id);
        showToast(`已发布冻结版本，复用编号 ${publicationId}`);
      }
      await refresh();
      setSelected(null);
    } catch (error: any) { showToast(error.message || '操作失败', 'error'); }
    finally { setBusy(false); }
  };

  const eligible = items.filter(asset => (selectionMode === 'REVIEW'
    ? !approvalBlocker(asset) && asset.reviewStatus === 'PENDING' : !publicationBlocker(asset))
    && (typeFilter === 'ALL' || asset.assetType === typeFilter));
  const batchApprove = async () => {
    const targets = eligible.filter(asset => checkedIds.includes(asset.id))
      .map(asset => ({ assetId: asset.id, versionNo: asset.currentVersion }));
    if (!targets.length) return;
    setBusy(true);
    try {
      await batchApproveAutomationVersions(project, targets);
      setCheckedIds([]);
      showToast(`已审核通过 ${targets.length} 项自动化资产`);
      await refresh();
    } catch (error: any) { showToast(error.message || '批量审核失败；未提交任何审核结果', 'error'); }
    finally { setBusy(false); }
  };

  const batchPublish = async () => {
    const targets = eligible.filter(asset => checkedIds.includes(asset.id))
      .map(asset => ({ assetId: asset.id, versionNo: asset.currentVersion }));
    if (!targets.length) return;
    setBusy(true);
    try {
      const results = await batchPublishAutomationAssets(project, targets);
      setCheckedIds([]);
      showToast(`已提交 ${results.length} 项冻结复用版`);
      await refresh();
    } catch (error: any) { showToast(error.message || '批量发布失败；未发布任何资产', 'error'); }
    finally { setBusy(false); }
  };

  return <div className="p-4 sm:p-6">
    <div className="mb-5 flex items-start justify-between gap-3">
      <div><h1 className="text-xl font-bold text-gray-900">自动化资产</h1>
        <p className="mt-1 text-sm text-gray-500">仅已验证且关联用例未变更的映射可以审核。审核通过的正式用例映射可批量发布冻结复用版，每批最多 100 项；历史版本、运行和证据可回看。</p></div>
      <div className="flex flex-wrap gap-2"><select aria-label="资产类型" value={typeFilter} onChange={event => { setTypeFilter(event.target.value as 'ALL' | 'UI' | 'API'); setCheckedIds([]); }} className="rounded-lg border px-3 py-2 text-sm"><option value="ALL">全部类型</option><option value="UI">UI 自动化</option><option value="API">接口自动化</option></select><select aria-label="批量操作" value={selectionMode} onChange={event => { setSelectionMode(event.target.value as 'REVIEW' | 'PUBLISH'); setCheckedIds([]); }} className="rounded-lg border px-3 py-2 text-sm"><option value="REVIEW">选择待审核资产</option><option value="PUBLISH">选择可发布复用资产</option></select>{selectionMode === 'REVIEW' ? <button disabled={busy || checkedIds.length === 0} onClick={batchApprove} className="rounded-lg bg-green-700 px-3 py-2 text-sm text-white disabled:opacity-50">批量审核已选（{checkedIds.length}）</button> : <button disabled={busy || checkedIds.length === 0} onClick={batchPublish} className="rounded-lg bg-blue-700 px-3 py-2 text-sm text-white disabled:opacity-50">批量发布复用版（{checkedIds.length}）</button>}<button onClick={refresh} className="rounded-lg border px-3 py-2 text-sm">刷新</button></div>
    </div>
    <section className="mb-5 rounded-xl border bg-white p-5" aria-label="UI 规则覆盖任务">
      <h2 className="font-semibold">UI 规则覆盖任务</h2>
      <p className="mt-1 text-sm text-gray-500">逐条核对规则、适用性和场景来源后确认清单。单条自动化用例通过不会自动代表本轮规则覆盖完成。</p>
      {pendingRules.length > 0 && <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm">
        <strong>待审核规则</strong>{pendingRules.map(rule => <div key={rule.id} className="mt-2 flex items-center justify-between gap-2">
          <span>{rule.ruleCode} v{rule.versionNo} · {rule.title} · {rule.checkKind}</span>
          <button disabled={busy} onClick={async () => { setBusy(true); try { await approveUiExecutionRule(project, rule.id); await refresh(); showToast('规则已审核生效，后续新任务将使用'); }
            catch (error: any) { showToast(error.message || '审核失败', 'error'); } finally { setBusy(false); } }}
            className="rounded bg-amber-700 px-3 py-1 text-white disabled:opacity-50">审核生效</button>
        </div>)}</div>}
      {coverageTasks.length === 0 ? <p className="mt-3 text-sm text-gray-500">暂无覆盖任务，可在 DSH 发起 UI 测试时创建。</p>
        : <div className="mt-3 space-y-3">{coverageTasks.map(task => <div key={task.taskId} className="rounded-lg border p-3 text-sm">
          <div className="flex flex-wrap items-center justify-between gap-2"><strong>任务 #{task.taskId} · {task.taskKey}</strong>
            <span>{task.result === 'PASS' ? '已确认清单覆盖完成' : task.result === 'INVENTORY_UNCONFIRMED' ? '清单待确认' : '覆盖未完成'} · {task.coveredCount}/{task.requiredCount}</span></div>
          <p className="mt-1 text-gray-500">目标：{task.targetOrigin}</p>
          <ul className="mt-2 space-y-1">{task.rules.map(rule => <li key={rule.ruleCode}>
            {rule.ruleCode} v{rule.ruleVersion} · {rule.applicability} · {rule.status}
            {rule.reason && <span className="ml-2 text-gray-500">依据：{rule.reason}</span>}
          </li>)}</ul>
          <ul className="mt-2 space-y-1">{task.obligations.map(item => <li key={item.id}>
            {item.ruleCode} · {item.fieldLabel} · {item.pagePath} · {item.applicability === 'UNKNOWN' ? '适用性待核实' : item.status === 'COVERED' ? '已有运行证据' : '待执行'}
            <span className="ml-2 text-gray-500">来源：{item.sourceBasis}</span>
          </li>)}</ul>
          {task.inventoryStatus === 'UNCONFIRMED' && <button disabled={busy || task.rules.some(rule => rule.applicability === 'UNKNOWN')
            || task.rules.some(rule => rule.applicability === 'APPLICABLE' && !task.obligations.some(item => item.ruleCode === rule.ruleCode))
            || task.obligations.some(item => item.applicability === 'UNKNOWN')}
            onClick={async () => { setBusy(true); try { await confirmUiCoverageTask(project, task.taskId); await refresh(); showToast('已确认必填项清单'); }
              catch (error: any) { showToast(error.message || '确认失败', 'error'); } finally { setBusy(false); } }}
            className="mt-3 rounded bg-blue-700 px-3 py-2 text-white disabled:opacity-50">确认本轮规则与场景清单</button>}
        </div>)}</div>}
    </section>
    {loading ? <p className="text-sm text-gray-500">加载中…</p> : items.length === 0
      ? <div className="rounded-xl border bg-white p-8 text-center text-sm text-gray-500">暂无自动化资产。可在 DSH 中对已有用例提出执行映射，跑通后会显示为已验证。</div>
      : <div className="overflow-x-auto rounded-xl border bg-white"><table className="w-full min-w-[860px] text-left text-sm">
          <thead className="bg-gray-50 text-gray-500"><tr><th className="p-3"><input type="checkbox" aria-label={selectionMode === 'REVIEW' ? '选择全部可审核资产' : '选择全部可发布资产'} checked={eligible.length > 0 && eligible.slice(0, 100).every(asset => checkedIds.includes(asset.id))} onChange={event => setCheckedIds(event.target.checked ? eligible.slice(0, 100).map(asset => asset.id) : [])} /></th><th className="p-3">用例</th><th className="p-3">类型</th><th className="p-3">归属</th><th className="p-3">提交人</th><th className="p-3">执行状态</th><th className="p-3">审核</th><th className="p-3">当前版本</th><th className="p-3">自愈次数</th><th className="p-3">操作</th></tr></thead>
          <tbody>{items.filter(asset => typeFilter === 'ALL' || asset.assetType === typeFilter).map(asset => <tr key={asset.id} className="border-t">
            <td className="p-3" title={(selectionMode === 'REVIEW' ? approvalBlocker(asset) : publicationBlocker(asset)) || undefined}><input type="checkbox" aria-label={`选择资产 ${asset.caseNo || asset.id}`} disabled={!eligible.some(item => item.id === asset.id) || (checkedIds.length >= 100 && !checkedIds.includes(asset.id))} checked={checkedIds.includes(asset.id)} onChange={event => setCheckedIds(ids => event.target.checked ? [...ids, asset.id] : ids.filter(id => id !== asset.id))} /></td>
            <td className="p-3 font-medium">{asset.caseNo || `#${asset.caseId}`}</td>
            <td className="p-3">{asset.assetType === 'API' ? '接口' : 'UI'}</td>
            <td className="p-3">{asset.caseKind === 'FORMAL' ? '正式用例' : '个人草稿'}</td>
            <td className="p-3">#{asset.createdBy}</td>
            <td className="p-3"><span className={`rounded px-2 py-1 text-xs ${colors[asset.status] || 'bg-gray-100'}`}>{asset.status}</span>{asset.caseMissing ? <span className="ml-2 text-xs text-red-700">关联用例已删除</span> : asset.caseStale && <span className="ml-2 text-xs text-red-700">用例已更新</span>}</td>
            <td className="p-3" title={approvalBlocker(asset) || undefined}>{asset.reviewStatus === 'APPROVED' ? '已通过' : asset.reviewStatus === 'REJECTED' ? '已舍弃' : approvalBlocker(asset) ? '待复验' : '待审核'}</td>
            <td className="p-3">v{asset.currentVersion}{asset.latestVersion > asset.currentVersion ? ` · 待验证 v${asset.latestVersion}` : ''}</td>
            <td className="p-3">{asset.healCount}{asset.healCount > 3 ? ' · 高频自愈' : ''}</td>
            <td className="p-3"><button onClick={() => open(asset)} className="text-blue-700 hover:underline">查看详情</button></td>
          </tr>)}</tbody>
        </table></div>}
    {selected && <div className="mt-5 rounded-xl border bg-white p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{selected.caseNo || `原用例 #${selected.caseId}`} · {selected.assetType === 'API' ? '接口' : 'UI'} 映射 v{selected.currentVersion}</h2>
        <div className="flex gap-2">
          {selected.status === 'BLOCKED' && <button disabled={busy} onClick={() => act('unblock')} className="rounded bg-amber-100 px-3 py-2 text-sm text-amber-800 disabled:opacity-50">人工确认解除阻断</button>}
          {!approvalBlocker(selected) && selected.reviewStatus === 'PENDING' && <button disabled={busy} onClick={() => act('approve')} className="rounded bg-green-100 px-3 py-2 text-sm text-green-800 disabled:opacity-50">审核通过</button>}
          {selected.caseStale && !selected.caseMissing && selected.status !== 'ARCHIVED' && selected.status !== 'BLOCKED' && candidate?.status !== 'DRAFT' && <button disabled={busy || !version} onClick={revalidateCase} className="rounded bg-amber-100 px-3 py-2 text-sm text-amber-800 disabled:opacity-50">生成复验候选版</button>}
          {!publicationBlocker(selected) && <button disabled={busy} onClick={() => act('publish')} className="rounded bg-blue-100 px-3 py-2 text-sm text-blue-800 disabled:opacity-50">发布冻结复用版</button>}
          {candidate?.status === 'DRAFT' && <button disabled={busy} onClick={() => act('discard')} className="rounded border border-amber-300 px-3 py-2 text-sm text-amber-800 disabled:opacity-50">舍弃待验证版</button>}
          {selected.status !== 'ARCHIVED' && <button disabled={busy || !version || candidate?.status === 'DRAFT'} onClick={() => setEditing(value => !value)} className="rounded border px-3 py-2 text-sm disabled:opacity-50">{editing ? '取消修订' : '修订映射'}</button>}
          {selected.status !== 'ARCHIVED' && <button disabled={busy} onClick={() => act('archive')} className="rounded border border-red-300 px-3 py-2 text-sm text-red-700 disabled:opacity-50">舍弃资产</button>}
          <button onClick={() => setSelected(null)} className="rounded border px-3 py-2 text-sm">关闭</button>
        </div>
      </div>
      {selected.blockedReason && <p className="mt-2 text-sm text-red-700">{selected.blockedReason}</p>}
      {selected.archiveReason && <p className="mt-2 text-sm text-gray-600">舍弃原因：{selected.archiveReason}（操作人 #{selected.archivedBy}）</p>}
      {approvalBlocker(selected) && <p className="mt-2 rounded bg-amber-50 p-3 text-sm text-amber-800">当前不能审核：{approvalBlocker(selected)}。{selected.caseStale && candidate?.status === 'DRAFT' && candidate.revisionKind === 'HEAL'
        ? '这个定位自愈候选不能替代用例改版；请先舍弃候选，再生成复验候选版。'
        : candidate?.status === 'DRAFT' && !selected.caseMissing
          ? `在 DSH 中说“重新验证项目 ${project} 中自动化资产 #${selected.id} 的候选 v${candidate.versionNo}”。` : ''}</p>}
      {selected.caseStale && !selected.caseMissing && <p className="mt-2 text-sm text-amber-700">用例已从 v{selected.sourceCaseVersion} 更新到 v{selected.caseVersion}；复验通过后再审核。</p>}
      {version && <div className="mt-4"><h3 className="mb-2 text-sm font-semibold">执行映射</h3>
        {selected.assetType === 'API' && <p className="mb-2 text-xs text-gray-500">密钥以引用保存；执行证据仅含请求摘要、响应哈希和断言结果。</p>}
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-3 text-xs">{JSON.stringify(version.mapping, null, 2)}</pre></div>}
      {candidate && <div className="mt-4 rounded border border-amber-200 bg-amber-50 p-3 text-sm">候选 v{candidate.versionNo}：{candidate.status === 'DRAFT' ? '待验证' : '已舍弃'}。修订依据：{candidate.changeReason}<pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(candidate.mapping, null, 2)}</pre></div>}
      {editing && <div className="mt-4 space-y-2 rounded-lg border border-blue-100 bg-blue-50 p-4">
        <label className="block text-sm font-medium">修订依据</label>
        <input value={changeReason} onChange={event => setChangeReason(event.target.value)} placeholder="说明业务或页面变化，至少 10 字" className="w-full rounded border p-2 text-sm" />
        <label className="block text-sm font-medium">新执行映射 JSON</label>
        <textarea value={editJson} onChange={event => setEditJson(event.target.value)} rows={14} className="w-full rounded border p-2 font-mono text-xs" />
        <button disabled={busy} onClick={revise} className="rounded bg-blue-700 px-3 py-2 text-sm text-white disabled:opacity-50">保存为待验证版本</button>
      </div>}
      <h3 className="mb-2 mt-5 text-sm font-semibold">最近运行</h3>
      {runs.length === 0 ? <p className="text-sm text-gray-500">暂无运行记录</p> : <div className="space-y-2">{runs.map(run => <div key={run.id} className="rounded border p-3 text-sm">
        <span className="font-medium">#{run.id} · v{run.versionNo} · {run.result}</span>
        <span className="ml-3 text-gray-500">{run.durationMs} ms · {run.evidenceSessionId ? `浏览器证据会话 #${run.evidenceSessionId}` : `接口证据 #${run.apiEvidenceId}`}</span>
        {run.apiEvidenceId && <button onClick={() => showApiEvidence(selected.id, run.apiEvidenceId!)} className="ml-3 text-blue-700 hover:underline">查看接口证据</button>}
        {!!run.logEvidence?.length && <details className="mt-2"><summary>日志断言：{run.logEvidence.filter(item => item.result === 'PASS').length}/{run.logEvidence.length} 通过</summary>
          {run.logEvidence.map(item => <div key={item.ruleId} className="mt-1 text-sm"><strong>{item.ruleId}</strong> · {item.serviceName || '服务未确认'} · {item.result}
            <span className="ml-2">{item.purpose === 'REQUEST_ASSOCIATION' ? '请求关联证据' : '业务事件证据'}</span>
            <pre className="overflow-auto whitespace-pre-wrap">{JSON.stringify(item, null, 2)}</pre></div>)}
        </details>}
        {run.failureReason && <p className="mt-1 text-red-700">{run.failureReason}</p>}
      </div>)}</div>}
      {apiEvidence && <div className="mt-4 rounded-lg border bg-gray-50 p-3 text-sm"><div className="flex justify-between"><strong>接口证据 #{apiEvidence.id} · {apiEvidence.result}</strong><button onClick={() => setApiEvidence(null)}>关闭</button></div><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(apiEvidence.evidence, null, 2)}</pre></div>}
    </div>}
  </div>;
}
