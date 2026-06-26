export interface ConsumeBindCodeRequest {
  code: string;
  deviceName: string;
  platform: string;
  arch: string;
  workerVersion: string;
  protocolVersion: string;
}

export interface ConsumeBindCodeResponse {
  deviceId: number;
  workerToken: string;
  bindStatus: string;
  serverTime: string;
}

interface ApiEnvelope<T> {
  success: boolean;
  data: T | null;
  message: string | null;
}

export async function consumeBindCode(
  serverUrl: string,
  request: ConsumeBindCodeRequest,
): Promise<ConsumeBindCodeResponse> {
  const endpoint = joinUrl(serverUrl, '/api/trace/devices/consume-bind-code');
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  const body = await readEnvelope<ConsumeBindCodeResponse>(res);
  if (!body.success || !body.data) {
    throw new Error(body.message || '设备绑定失败，请重新生成绑定码后重试。');
  }
  return body.data;
}

export async function sendHeartbeat(serverUrl: string, workerToken: string): Promise<boolean> {
  if (!serverUrl || !workerToken) return false;
  try {
    const endpoint = joinUrl(serverUrl, '/api/trace/devices/heartbeat');
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { Authorization: `Bearer ${workerToken}` },
    });
    if (!res.ok) return false;
    const body = await readEnvelope<unknown>(res);
    return body.success;
  } catch {
    return false;
  }
}

async function readEnvelope<T>(res: Response): Promise<ApiEnvelope<T>> {
  let parsed: unknown;
  try {
    parsed = await res.json();
  } catch {
    throw new Error(`平台接口返回无法解析（HTTP ${res.status}）。`);
  }

  const body = parsed as Partial<ApiEnvelope<T>>;
  if (!res.ok) {
    throw new Error(body.message || `平台接口请求失败（HTTP ${res.status}）。`);
  }
  return {
    success: Boolean(body.success),
    data: body.data ?? null,
    message: body.message ?? null,
  };
}

function joinUrl(base: string, path: string): string {
  const normalizedBase = base.replace(/\/+$/, '');
  return `${normalizedBase}${path}`;
}
