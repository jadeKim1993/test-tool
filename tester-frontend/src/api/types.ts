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
  /** 켜져 있어 실제로 전송에 쓰이는 로그 종류 수. */
  enabledTemplateCount: number;
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

/** 템플릿 본문에 쓰인 플레이스홀더 하나. start/end 는 본문 안 문자 위치(end 는 미포함). */
export interface TemplatePlaceholder {
  start: number;
  end: number;
  expression: string;
  function: string;
}

/** 렌더 결과 한 조각. source: 0 이상 = placeholders 인덱스, -1 = 고정 문자열, -2 = 포맷 헤더. */
export interface PreviewPart {
  text: string;
  source: number;
}

export interface PreviewResponse {
  lines: string[];
  averageBytes: number;
  error: string | null;
  position: number | null;
  placeholders: TemplatePlaceholder[];
  parts: PreviewPart[][];
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
  /** HTTP/HTTPS 전용: 요청 경로, 본문 형식, 추가 헤더("이름: 값" 줄). */
  httpPath: string;
  httpFormat: HttpFormat;
  httpHeaders: string;
  endpoint: string;
  updatedAt: string;
}

export type HttpFormat = 'LINES' | 'NDJSON' | 'JSON_ARRAY' | 'SPLUNK_HEC' | 'ES_BULK' | 'LOKI';

/** HTTP 본문 형식. 고르면 그 수집기의 기본 경로·포트를 채운다. */
export interface HttpFormatOption {
  value: HttpFormat;
  label: string;
  contentType: string;
  defaultPath: string;
  defaultPort: number;
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
  httpFormats: HttpFormatOption[];
  dictionaries: string[];
  transport: string;
  cpuCores: number;
}

/** 작업이 실제로 보내는 로그 종류. 엔진이 쓰는 목록·비율과 같다(GET /jobs/{id}/content). */
export interface JobTemplate {
  id: number;
  name: string;
  format: LogFormat;
  weight: number;
  /** 실제 전송 비율 0~1. 라운드로빈/순차는 가중치와 무관하게 균등하다. */
  share: number;
  /** 한 번 렌더링한 예시(인코딩 전). */
  sample: string | null;
  sampleBytes: number;
  error: string | null;
}

export interface JobContent {
  selection: SelectionStrategy;
  encoding: WireEncoding;
  templates: JobTemplate[];
}
