import { useParams } from 'react-router-dom';
import TracePanel from '../../components/trace/TracePanel';

export default function TracePanelPage() {
  const { projectId } = useParams<{ projectId: string }>();

  if (!projectId) {
    return <div className="p-4 text-gray-500 sm:p-6">项目 ID 缺失</div>;
  }

  return <TracePanel projectId={Number(projectId)} />;
}
