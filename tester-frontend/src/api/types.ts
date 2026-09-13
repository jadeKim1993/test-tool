// 백엔드 DTO 와 1:1 대응한다.

export interface User {
  id: number;
  username: string;
  displayName: string;
}

export interface TokenResponse {
  accessToken: string;
  expiresAt: string;
  user: User;
}

export interface Category {
  id: number;
  name: string;
  description: string | null;
  shared: boolean;
  owned: boolean;
  templateCount: number;
  /** 이 카테고리의 로그를 회선에 실을 때의 바이트 변환 방식. 전송 대상이 아니라 여기 붙는다. */
  encoding: WireEncoding;
  updatedAt: string;
}

export type LogFormat = 'RAW' | 'SYSLOG_RFC3164' | 'SYSLOG_RFC5424';

export interface LogTemplate {
  id: number;
  categoryId: number;
  name: string;
  content: string;
  format: LogFormat;
  weight: number;
  enabled: boolean;
  sortOrder: number;
  updatedAt: string;
}

export interface PreviewResponse {
  lines: string[];
  averageBytes: number;
  error: string | null;
  position: number | null;
}

export type Framing = 'NEWLINE' | 'NONE' | 'LENGTH_PREFIX' | 'OCTET_COUNTING';
/** 전송 시 바이트 변환 방식. 수신 서버가 기대하는 형태에 맞춘다. */
export type WireEncoding = 'UTF_8' | 'CP949' | 'BASE64';

export interface Target {
  id: number;
  /** 주소에서 자동 생성되는 표시명. 입력받지 않는다. */
  name: string;
  protocol: string;
  host: string;
  port: number;
  connections: number;
  framing: Framing;
  batchBytes: number;
  sendBufferBytes: number;
  connectTimeoutMs: number;
  endpoint: string;
  updatedAt: string;
}

export interface TestResult {
  ok: boolean;
  latencyMs: number;
  message: string;
}

export type SelectionStrategy = 'WEIGHTED_RANDOM' | 'ROUND_ROBIN' | 'SEQUENTIAL';
export type OverflowPolicy = 'DROP' | 'BLOCK';
export type RunState = 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'COMPLETED' | 'FAILED';

export interface Job {
  id: number;
  name: string;
  categoryId: number;
  categoryName: string;
  targetId: number;
  targetName: string;
  endpoint: string;
  templateIds: number[];
  templateCount: number;
  eps: number;
  selection: SelectionStrategy;
  overflowPolicy: OverflowPolicy;
  durationSec: number | null;
  totalLimit: number | null;
  workerThreads: number;
  autostart: boolean;
  running: boolean;
  updatedAt: string;
}

export interface JobStatus {
  jobId: number;
  jobName: string;
  runId: number;
  timestamp: number;
  elapsedSec: number;
  eps: number;
  targetEps: number;
  mbps: number;
  sent: number;
  bytes: number;
  dropped: number;
  errors: number;
  state: RunState;
  connection: string;
  workers: number;
  endpoint: string;
  message: string | null;
}

export interface JobRun {
  id: number;
  jobId: number;
  startedAt: string;
  endedAt: string | null;
  status: RunState;
  targetEps: number;
  sentCount: number;
  byteCount: number;
  droppedCount: number;
  errorCount: number;
  avgEps: number;
  peakEps: number;
  endpoint: string;
  message: string | null;
}

export interface FunctionSpec {
  name: string;
  signature: string;
  description: string;
  example: string;
  sample: string;
  group: string;
}

export interface ProtocolInfo {
  protocol: string;
  description: string;
}

export interface EnumOption {
  value: string;
  label: string;
}

export interface MetaOptions {
  formats: EnumOption[];
  framings: EnumOption[];
  selections: EnumOption[];
  overflowPolicies: EnumOption[];
  encodings: EnumOption[];
  dictionaries: string[];
  transport: string;
  cpuCores: number;
}
