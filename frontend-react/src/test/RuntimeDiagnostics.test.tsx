import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'

const mockShowToast = vi.fn()

vi.mock('../context/AppContext', () => ({
  useApp: () => ({ showToast: mockShowToast, setModal: vi.fn() }),
}))

vi.mock('../services/api', () => ({
  exportLlmInvocationReport: vi.fn(),
  exportSecurityEventReport: vi.fn(),
  getLlmInvocationChain: vi.fn(),
  getLlmInvocationSnapshot: vi.fn(),
  listLlmInvocationLogs: vi.fn(),
  listSecurityEventLogs: vi.fn(),
}))

import RuntimeDiagnostics from '../pages/admin/RuntimeDiagnostics'
import {
  exportLlmInvocationReport,
  exportSecurityEventReport,
  getLlmInvocationChain,
  getLlmInvocationSnapshot,
  listLlmInvocationLogs,
  listSecurityEventLogs,
} from '../services/api'

const mockExportLlmInvocationReport = vi.mocked(exportLlmInvocationReport)
const mockExportSecurityEventReport = vi.mocked(exportSecurityEventReport)
const mockGetLlmInvocationChain = vi.mocked(getLlmInvocationChain)
const mockGetLlmInvocationSnapshot = vi.mocked(getLlmInvocationSnapshot)
const mockListLlmInvocationLogs = vi.mocked(listLlmInvocationLogs)
const mockListSecurityEventLogs = vi.mocked(listSecurityEventLogs)

describe('RuntimeDiagnostics', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:diagnostics'), writable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), writable: true })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    mockExportLlmInvocationReport.mockResolvedValue('# LLM 运行诊断脱敏报告')
    mockExportSecurityEventReport.mockResolvedValue('# 安全事件脱敏报告')
    mockGetLlmInvocationSnapshot.mockResolvedValue({
      id: 11,
      requestId: 'req-1#a1',
      userId: 1,
      projectId: 10,
      taskId: 20,
      stage: 'CASE_GENERATION',
      provider: 'OTHER',
      modelName: 'gpt-test',
      status: 'ATTEMPT_RETRY',
      errorCode: 'PROVIDER_ERROR',
      errorMessage: 'provider 500',
      rawOutput: 'full masked output',
      createdAt: '2026-07-01T10:00:01',
    })
    mockListLlmInvocationLogs.mockResolvedValue([
      {
        id: 1,
        requestId: 'req-1',
        userId: 1,
        projectId: 10,
        taskId: 20,
        taskType: 'TEST_CASE_GENERATION',
        stage: 'CASE_GENERATION',
        modelConfigId: 99,
        provider: 'OTHER',
        modelName: 'gpt-test',
        retryIndex: 1,
        status: 'ATTEMPT_FAILED',
        errorCode: 'TIMEOUT',
        errorMessage: '模型调用超时',
        durationMs: 120000,
        tokenInput: 100,
        tokenOutput: 20,
        rawOutputPreview: 'partial output',
        createdAt: '2026-07-01T10:00:00',
      },
    ])
    mockListSecurityEventLogs.mockResolvedValue([
      {
        id: 2,
        eventType: 'PROMPT_INJECTION',
        severity: 'WARN',
        userId: 1,
        projectId: 10,
        taskId: 20,
        requestId: 'req-2',
        detailPreview: '{"rule":"IGNORE_PREVIOUS"}',
        createdAt: '2026-07-01T10:01:00',
      },
    ])
    mockGetLlmInvocationChain.mockResolvedValue({
      rootRequestId: 'req-1',
      entries: [
        {
          id: 11,
          requestId: 'req-1#a1',
          userId: 1,
          projectId: 10,
          taskId: 20,
          taskType: 'TEST_CASE_GENERATION',
          stage: 'CASE_GENERATION',
          modelConfigId: 99,
          provider: 'OTHER',
          modelName: 'gpt-test',
          retryIndex: 1,
          status: 'ATTEMPT_RETRY',
          errorCode: 'PROVIDER_ERROR',
          errorMessage: 'provider 500',
          durationMs: 1000,
          tokenInput: 0,
          tokenOutput: 0,
          rawOutputPreview: 'temporary failure',
          createdAt: '2026-07-01T10:00:01',
        },
      ],
    })
  })

  it('loads and renders llm invocation logs by default', async () => {
    render(<RuntimeDiagnostics />)

    await waitFor(() => expect(mockListLlmInvocationLogs).toHaveBeenCalled())
    expect(screen.getByText('gpt-test')).toBeDefined()
    expect(screen.getByText('调用超时')).toBeDefined()
    expect(screen.getByText('partial output')).toBeDefined()
  })

  it('switches to security events and renders event details', async () => {
    render(<RuntimeDiagnostics />)

    fireEvent.click(screen.getByText('安全事件'))
    await waitFor(() => expect(mockListSecurityEventLogs).toHaveBeenCalled())
    expect(screen.getByText('PROMPT_INJECTION')).toBeDefined()
    expect(screen.getByText('{"rule":"IGNORE_PREVIOUS"}')).toBeDefined()
  })

  it('passes filters when querying llm logs', async () => {
    render(<RuntimeDiagnostics />)

    await waitFor(() => expect(mockListLlmInvocationLogs).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByPlaceholderText('项目 ID'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('任务 ID'), { target: { value: '20' } })
    fireEvent.change(screen.getByPlaceholderText('状态'), { target: { value: 'FAILED' } })
    fireEvent.change(screen.getByPlaceholderText('错误码'), { target: { value: 'TIMEOUT' } })
    fireEvent.change(screen.getByPlaceholderText('关键字'), { target: { value: 'CASE' } })
    fireEvent.click(screen.getByText('查询'))

    await waitFor(() => {
      expect(mockListLlmInvocationLogs).toHaveBeenLastCalledWith({
        projectId: 10,
        taskId: 20,
        status: 'FAILED',
        errorCode: 'TIMEOUT',
        severity: undefined,
        keyword: 'CASE',
        limit: 50,
      })
    })
  })

  it('opens llm invocation chain drawer from a log row', async () => {
    render(<RuntimeDiagnostics />)

    await waitFor(() => expect(screen.getByText('gpt-test')).toBeDefined())
    fireEvent.click(screen.getByText('查看链路'))

    await waitFor(() => expect(mockGetLlmInvocationChain).toHaveBeenCalledWith('req-1'))
    expect(screen.getByText('LLM 调用链路')).toBeDefined()
    expect(screen.getByText('等待重试')).toBeDefined()
    expect(screen.getByText('temporary failure')).toBeDefined()
  })

  it('opens authorized full snapshot from chain drawer', async () => {
    render(<RuntimeDiagnostics />)

    await waitFor(() => expect(screen.getByText('gpt-test')).toBeDefined())
    fireEvent.click(screen.getByText('查看链路'))
    await waitFor(() => expect(screen.getByText('temporary failure')).toBeDefined())
    fireEvent.click(screen.getByText('查看完整快照'))

    await waitFor(() => expect(mockGetLlmInvocationSnapshot).toHaveBeenCalledWith(11))
    expect(screen.getByText('完整快照')).toBeDefined()
    expect(screen.getByText('full masked output')).toBeDefined()
  })

  it('exports llm diagnostics report with current filters', async () => {
    render(<RuntimeDiagnostics />)

    await waitFor(() => expect(mockListLlmInvocationLogs).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByPlaceholderText('项目 ID'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('任务 ID'), { target: { value: '20' } })
    fireEvent.change(screen.getByPlaceholderText('状态'), { target: { value: 'FAILED' } })
    fireEvent.change(screen.getByPlaceholderText('错误码'), { target: { value: 'TIMEOUT' } })
    fireEvent.click(screen.getByText('导出调用报告'))

    await waitFor(() => {
      expect(mockExportLlmInvocationReport).toHaveBeenCalledWith({
        projectId: 10,
        taskId: 20,
        status: 'FAILED',
        errorCode: 'TIMEOUT',
        severity: undefined,
        keyword: undefined,
        limit: 50,
      })
    })
    expect(mockShowToast).toHaveBeenCalledWith('运行诊断报告已导出', 'success')
  })

  it('exports security event report with current filters', async () => {
    render(<RuntimeDiagnostics />)

    fireEvent.click(screen.getByText('安全事件'))
    await waitFor(() => expect(mockListSecurityEventLogs).toHaveBeenCalled())
    fireEvent.change(screen.getByPlaceholderText('项目 ID'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('级别'), { target: { value: 'WARN' } })
    fireEvent.change(screen.getByPlaceholderText('关键字'), { target: { value: 'PROMPT' } })
    fireEvent.click(screen.getByText('导出安全报告'))

    await waitFor(() => {
      expect(mockExportSecurityEventReport).toHaveBeenCalledWith({
        projectId: 10,
        taskId: undefined,
        status: undefined,
        errorCode: undefined,
        severity: 'WARN',
        keyword: 'PROMPT',
        limit: 50,
      })
    })
    expect(mockShowToast).toHaveBeenCalledWith('运行诊断报告已导出', 'success')
  })
})
