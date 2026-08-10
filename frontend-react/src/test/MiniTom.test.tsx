import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const appMocks = vi.hoisted(() => ({
  showToast: vi.fn(),
}));

const apiMocks = vi.hoisted(() => ({
  listTomCandidates: vi.fn(),
  listActiveTomModels: vi.fn(),
  listEnabledModelConfigs: vi.fn(),
  confirmTomCandidate: vi.fn(),
  rejectTomCandidate: vi.fn(),
  upgradeTomCandidate: vi.fn(),
  importManual: vi.fn(),
  buildTestScope: vi.fn(),
}));

vi.mock('../context/AppContext', () => ({
  useApp: () => appMocks,
}));

vi.mock('react-router-dom', () => ({
  useParams: () => ({ projectId: '12' }),
}));

vi.mock('../services/api', () => apiMocks);

import MiniTom from '../pages/project/MiniTom';

const enabledModels = [
  { id: 7, configName: '默认模型', modelName: 'deepseek-v4-pro' },
  { id: 8, configName: '备用模型', modelName: 'gpt-5' },
];

beforeEach(() => {
  vi.clearAllMocks();
  apiMocks.listTomCandidates.mockResolvedValue([]);
  apiMocks.listActiveTomModels.mockResolvedValue([]);
  apiMocks.listEnabledModelConfigs.mockResolvedValue(enabledModels);
  apiMocks.importManual.mockResolvedValue({ taskId: 1001 });
  apiMocks.buildTestScope.mockResolvedValue({
    affectedModules: ['预约模块'],
    affectedPages: ['预约页面'],
    suggestedAssertions: ['预约记录创建成功'],
    projectTomCount: 1,
    systemTomCount: 2,
  });
});

describe('MiniTom model selection', () => {
  it('passes selected modelConfigId when importing manual TOM content', async () => {
    render(<MiniTom />);

    await waitFor(() => {
      expect(apiMocks.listEnabledModelConfigs).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: '导入使用手册' }));

    expect(screen.getByLabelText('LLM 模型')).toHaveValue('7');
    fireEvent.change(screen.getByLabelText('文档标题'), {
      target: { value: '审批操作手册' },
    });
    fireEvent.change(screen.getByLabelText('业务领域'), {
      target: { value: '业务审批' },
    });
    fireEvent.change(screen.getByLabelText('Markdown 内容'), {
      target: { value: '# 预约\n提交预约后审批。' },
    });

    fireEvent.click(screen.getByRole('button', { name: '开始导入' }));

    await waitFor(() => {
      expect(apiMocks.importManual).toHaveBeenCalledWith({
        projectId: 12,
        docTitle: '审批操作手册',
        businessDomain: '业务审批',
        markdownContent: '# 预约\n提交预约后审批。',
        modelConfigId: 7,
      });
      expect(appMocks.showToast).toHaveBeenCalledWith('导入任务已启动');
    });
  });

  it('passes user-selected modelConfigId when building test scope', async () => {
    render(<MiniTom />);

    await waitFor(() => {
      expect(apiMocks.listEnabledModelConfigs).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: '构建测试范围' }));
    fireEvent.change(screen.getByLabelText('LLM 模型'), {
      target: { value: '8' },
    });
    fireEvent.change(screen.getByLabelText('需求描述'), {
      target: { value: '修改业务审批流程。' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: '构建测试范围' })[1]);

    await waitFor(() => {
      expect(apiMocks.buildTestScope).toHaveBeenCalledWith({
        projectId: 12,
        requirementText: '修改业务审批流程。',
        modelConfigId: 8,
      });
      expect(screen.getByText('预约模块')).toBeInTheDocument();
      expect(screen.getByText('预约页面')).toBeInTheDocument();
      expect(screen.getByText('预约记录创建成功')).toBeInTheDocument();
    });
  });

  it('blocks manual import when no enabled model is available', async () => {
    apiMocks.listEnabledModelConfigs.mockResolvedValueOnce([]);
    render(<MiniTom />);

    await waitFor(() => {
      expect(apiMocks.listEnabledModelConfigs).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: '导入使用手册' }));

    expect(screen.getByText('暂无可用模型')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('文档标题'), {
      target: { value: '审批操作手册' },
    });
    fireEvent.change(screen.getByLabelText('Markdown 内容'), {
      target: { value: '# 预约\n提交预约后审批。' },
    });

    expect(screen.getByRole('button', { name: '开始导入' })).toBeDisabled();
    expect(apiMocks.importManual).not.toHaveBeenCalled();
  });
});
