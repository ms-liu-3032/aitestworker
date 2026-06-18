import { useState } from 'react';
import Modal from './Modal';
import { createProject } from '../services/api';

interface NewProjectModalProps {
  onClose: () => void;
  onSuccess: () => void;
  showToast: (msg: string, type?: 'success' | 'error' | 'info') => void;
}

export default function NewProjectModal({ onClose, onSuccess, showToast }: NewProjectModalProps) {
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleCreate = async () => {
    if (!name) return;
    setSubmitting(true);
    try {
      await createProject({ projectName: name, description: desc });
      showToast('项目创建成功');
      onSuccess();
    } catch (err: any) {
      showToast(err.message || '创建失败', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="新建项目"
      onClose={onClose}
      footer={
        <>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleCreate}
            disabled={submitting || !name}
            className="px-4 py-2 bg-slate-900 text-white text-sm font-semibold rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            {submitting ? '创建中...' : '创建'}
          </button>
        </>
      }
    >
      <div className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">项目名称</label>
          <input
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg focus:bg-white focus:border-gray-400 outline-none transition-all text-sm"
            placeholder="输入项目名称"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">项目描述</label>
          <input
            type="text"
            value={desc}
            onChange={e => setDesc(e.target.value)}
            className="w-full h-9 px-3 bg-gray-50 border border-gray-200 rounded-lg focus:bg-white focus:border-gray-400 outline-none transition-all text-sm"
            placeholder="输入项目描述（可选）"
          />
        </div>
      </div>
    </Modal>
  );
}
