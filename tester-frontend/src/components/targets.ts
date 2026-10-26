import type { Target } from '../api/types';

/** 프로토콜 표시 순서와 색. 목록·필터·선택 상자가 모두 같은 규칙을 쓴다. */
export const PROTOCOLS = ['TCP', 'UDP', 'TLS', 'HTTP', 'HTTPS'] as const;

export const isHttp = (protocol?: string) => protocol === 'HTTP' || protocol === 'HTTPS';

// TCP 와 UDP 는 같은 IP·포트에 나란히 두는 일이 많아 색상 계열부터 달라야 한다(파랑/보라).
// HTTP 계열은 주황(평문)·하늘(암호화)로 스트림 프로토콜과 구분한다.
export const PROTOCOL_COLOR: Record<string, 'primary' | 'secondary' | 'success' | 'warning' | 'info' | 'default'> = {
  TCP: 'primary', UDP: 'secondary', TLS: 'success', HTTP: 'warning', HTTPS: 'info',
};

/** 형식 이름을 짧게(카드·작업 카드용). */
export const HTTP_FORMAT_SHORT: Record<string, string> = {
  LINES: '텍스트 줄', NDJSON: 'JSON Lines', JSON_ARRAY: 'JSON 배열',
  SPLUNK_HEC: 'Splunk HEC', ES_BULK: 'ES _bulk', LOKI: 'Loki',
};

/** IPv4 면 옥텟 배열, 아니면 null(호스트명). */
function ipv4(host: string): number[] | null {
  const parts = host.split('.');
  if (parts.length !== 4) return null;
  const nums = parts.map(Number);
  return nums.every((n, i) => /^\d{1,3}$/.test(parts[i]) && n <= 255) ? nums : null;
}

/**
 * 주소 순 비교. IP 는 **숫자로** 비교한다 — 문자열로 정렬하면 10.0.0.9 가 10.0.0.10 뒤로 간다.
 * IP 가 아닌 호스트명은 IP 뒤에 사전순으로 둔다. 같은 호스트면 포트, 그다음 프로토콜 순.
 */
export function compareHost(a: string, b: string): number {
  const ia = ipv4(a);
  const ib = ipv4(b);
  if (ia && ib) {
    for (let i = 0; i < 4; i++) {
      if (ia[i] !== ib[i]) return ia[i] - ib[i];
    }
    return 0;
  }
  if (ia) return -1;
  if (ib) return 1;
  return a.localeCompare(b);
}

export function compareTargets(a: Target, b: Target): number {
  return compareHost(a.host, b.host)
    || a.port - b.port
    || PROTOCOLS.indexOf(a.protocol as never) - PROTOCOLS.indexOf(b.protocol as never);
}

export function protocolOrder(protocol: string): number {
  const index = PROTOCOLS.indexOf(protocol as never);
  return index < 0 ? PROTOCOLS.length : index;
}
