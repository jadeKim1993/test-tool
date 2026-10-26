import type {
  Category, FunctionSpec, Job, JobContent, JobRun, JobStatus, LogTemplate, MetaOptions, PreviewResponse, ProtocolInfo, Target, TestResult, TokenResponse, User, WireEncoding,
} from './types';

const TOKEN_KEY = 'tester.token';

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* 시크릿 모드 등에서 저장이 막혀도 세션 자체는 동작해야 한다. */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });

  // 만료가 임박하면 서버가 갱신 토큰을 헤더로 내려준다.
  const refreshed = response.headers.get('X-Refresh-Token');
  if (refreshed) setToken(refreshed);

  if (response.status === 401) {
    setToken(null);
    onUnauthorized?.();
    throw new ApiError(401, '로그인이 필요합니다');
  }
  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;

  if (!response.ok) {
    // RFC7807 problem+json
    throw new ApiError(response.status, payload?.detail ?? payload?.title ?? '요청을 처리하지 못했습니다');
  }
  return payload as T;
}

const json = (body: unknown) => JSON.stringify(body);

export const api = {
  // ---- 인증 ----
  exists: (username: string) =>
    request<{ username: string; exists: boolean }>(`/api/auth/exists?username=${encodeURIComponent(username)}`),
  signup: (username: string) =>
    request<TokenResponse>('/api/auth/signup', { method: 'POST', body: json({ username }) }),
  login: (username: string) =>
    request<TokenResponse>('/api/auth/login', { method: 'POST', body: json({ username }) }),
  me: () => request<User>('/api/auth/me'),

  // ---- 카테고리 / 템플릿 ----
  categories: () => request<Category[]>('/api/categories'),
  createCategory: (body: { name: string; description?: string; shared: boolean; encoding?: WireEncoding }) =>
    request<Category>('/api/categories', { method: 'POST', body: json(body) }),
  updateCategory: (id: number, body: { name: string; description?: string; shared: boolean; encoding?: WireEncoding }) =>
    request<Category>(`/api/categories/${id}`, { method: 'PUT', body: json(body) }),
  deleteCategory: (id: number) => request<void>(`/api/categories/${id}`, { method: 'DELETE' }),
  reorderCategories: (ids: number[]) =>
    request<void>('/api/categories/order', { method: 'PUT', body: json({ ids }) }),
  duplicateCategory: (id: number, name?: string) =>
    request<Category>(`/api/categories/${id}/duplicate${name ? `?name=${encodeURIComponent(name)}` : ''}`,
      { method: 'POST' }),
  exportCategory: (id: number) => request<unknown>(`/api/categories/${id}/export`),
  importCategory: (payload: unknown) =>
    request<Category>('/api/categories/import', { method: 'POST', body: json(payload) }),

  templates: (categoryId: number) => request<LogTemplate[]>(`/api/categories/${categoryId}/templates`),
  createTemplate: (categoryId: number, body: Partial<LogTemplate>) =>
    request<LogTemplate>(`/api/categories/${categoryId}/templates`, { method: 'POST', body: json(body) }),
  updateTemplate: (id: number, body: Partial<LogTemplate>) =>
    request<LogTemplate>(`/api/templates/${id}`, { method: 'PUT', body: json(body) }),
  deleteTemplate: (id: number) => request<void>(`/api/templates/${id}`, { method: 'DELETE' }),
  preview: (content: string, format: string, count = 5) =>
    request<PreviewResponse>('/api/templates/preview', { method: 'POST', body: json({ content, format, count }) }),
  reorderTemplates: (categoryId: number, ids: number[]) =>
    request<void>(`/api/categories/${categoryId}/templates/order`, { method: 'PUT', body: json({ ids }) }),
  /** 카테고리 안의 로그 종류를 한 번에 켜고 끈다. */
  setAllTemplatesEnabled: (categoryId: number, enabled: boolean) =>
    request<{ changed: number; enabled: boolean }>(
      `/api/categories/${categoryId}/templates/enabled?enabled=${enabled}`, { method: 'PATCH' }),

  // ---- 전송 대상 ----
  targets: () => request<Target[]>('/api/targets'),
  createTarget: (body: Partial<Target>) => request<Target>('/api/targets', { method: 'POST', body: json(body) }),
  updateTarget: (id: number, body: Partial<Target>) =>
    request<Target>(`/api/targets/${id}`, { method: 'PUT', body: json(body) }),
  deleteTarget: (id: number) => request<void>(`/api/targets/${id}`, { method: 'DELETE' }),
  testTarget: (id: number) => request<TestResult>(`/api/targets/${id}/test`, { method: 'POST' }),

  // ---- 전송 작업 ----
  jobs: () => request<Job[]>('/api/jobs'),
  createJob: (body: Partial<Job>) => request<Job>('/api/jobs', { method: 'POST', body: json(body) }),
  updateJob: (id: number, body: Partial<Job>) => request<Job>(`/api/jobs/${id}`, { method: 'PUT', body: json(body) }),
  deleteJob: (id: number) => request<void>(`/api/jobs/${id}`, { method: 'DELETE' }),
  startJob: (id: number, body: { eps?: number; durationSec?: number; totalLimit?: number } = {}) =>
    request<JobStatus>(`/api/jobs/${id}/start`, { method: 'POST', body: json(body) }),
  stopJob: (id: number) => request<void>(`/api/jobs/${id}/stop`, { method: 'POST' }),
  setRate: (id: number, eps: number) =>
    request<JobStatus>(`/api/jobs/${id}/rate`, { method: 'PATCH', body: json({ eps }) }),
  jobStatus: (id: number) => request<JobStatus | null>(`/api/jobs/${id}/status`),
  jobRuns: (id: number, limit = 20) => request<JobRun[]>(`/api/jobs/${id}/runs?limit=${limit}`),
  /** 이 작업이 실제로 보내는 로그 종류·비율·예시. */
  jobContent: (id: number) => request<JobContent>(`/api/jobs/${id}/content`),
  running: () => request<{ runningJobs: number; totalEps: number; totalMbps: number; jobIds: number[] }>('/api/jobs/running'),

  // ---- 메타 ----
  functions: () => request<FunctionSpec[]>('/api/meta/functions'),
  protocols: () => request<ProtocolInfo[]>('/api/meta/protocols'),
  options: () => request<MetaOptions>('/api/meta/options'),

  // ---- 스트림 ----
  streamTicket: () => request<{ ticket: string }>('/api/stream/ticket', { method: 'POST' }),
  history: (jobId: number) => request<JobStatus[]>(`/api/stream/history/${jobId}`),
};
