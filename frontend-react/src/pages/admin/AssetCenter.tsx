import { useSearchParams } from 'react-router-dom';
import CandidateReview from './CandidateReview';
import AssetLibrary from './AssetLibrary';
import MiniTomModels from './MiniTomModels';

type AssetCenterTab = 'review' | 'catalog' | 'tom';

const views: Array<{ key: AssetCenterTab; label: string; description: string }> = [
  { key: 'review', label: '待审核', description: '处理待确认的资产与驳回记录' },
  { key: 'catalog', label: '资产目录', description: '查看资产、状态和来源，管理已生效资产' },
  { key: 'tom', label: 'TOM 模型', description: '检查模型详情、引用关系和有效性' },
];

export default function AssetCenter() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const active: AssetCenterTab = views.some(view => view.key === requested)
    ? requested as AssetCenterTab : 'review';

  return (
    <div>
      <div className="border-b border-gray-200 bg-white px-4 pt-5 sm:px-6">
        <h1 className="text-xl font-bold text-gray-900">资产中心</h1>
        <p className="mt-1 text-sm text-gray-500">按审核、检索和模型关系处理资产；每条资产保留原有来源与审核记录。</p>
        <nav aria-label="资产中心" className="mt-4 flex gap-1 overflow-x-auto">
          {views.map(view => <button key={view.key} type="button"
            onClick={() => setParams({ tab: view.key })}
            aria-current={active === view.key ? 'page' : undefined}
            className={`whitespace-nowrap border-b-2 px-4 py-2 text-sm font-medium ${active === view.key
              ? 'border-purple-600 text-purple-900' : 'border-transparent text-gray-500 hover:text-gray-900'}`}>
            {view.label}
          </button>)}
        </nav>
      </div>
      <p className="px-4 pt-4 text-xs text-gray-500 sm:px-6">{views.find(view => view.key === active)?.description}</p>
      {active === 'review' ? <CandidateReview embedded /> : active === 'catalog' ? <AssetLibrary embedded /> : <MiniTomModels embedded />}
    </div>
  );
}
