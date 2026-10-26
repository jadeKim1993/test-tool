import type { FunctionSpec } from '../api/types';

/**
 * 템플릿 원문을 화면 강조용 토큰으로 나눈다.
 * <p>서버 파서(TemplateCompiler.Parser)와 같은 규칙으로 자른다 — {@code \${} 는 리터럴,
 * {@code ${} 부터 다음 {@code }} 까지가 플레이스홀더. 그래서 토큰의 {@code index} 는 미리보기 응답의
 * {@code placeholders} 인덱스와 일치한다. 서버를 기다리지 않고 타이핑 즉시 색을 입히려고 여기서도 자른다.
 */
export interface Token {
  kind: 'text' | 'ph';
  text: string;
  start: number;
  end: number;
  /** 소문자 함수명. 플레이스홀더만. */
  fn?: string;
  /** 본문 안에서 몇 번째 플레이스홀더인가. 미리보기 placeholders 인덱스와 같다. */
  index?: number;
  /** 화면에서 바로 알 수 있는 문제. 서버 검증과 별개로 밑줄만 긋는다. */
  problem?: string;
}

export function tokenize(src: string, known: Set<string>): Token[] {
  const tokens: Token[] = [];
  let text = '';
  let textStart = 0;
  let index = 0;
  let i = 0;

  const flush = (at: number) => {
    if (text) tokens.push({ kind: 'text', text, start: textStart, end: at });
    text = '';
    textStart = at;
  };

  while (i < src.length) {
    if (src.startsWith('\\${', i)) {
      text += '\\${';
      i += 3;
      continue;
    }
    if (src.startsWith('${', i)) {
      flush(i);
      const close = src.indexOf('}', i + 2);
      if (close < 0) {
        tokens.push({ kind: 'ph', text: src.slice(i), start: i, end: src.length, problem: '닫는 } 가 없습니다' });
        i = src.length;
        textStart = i;
        break;
      }
      const body = src.slice(i + 2, close);
      const fn = body.trim().split(/[\s(]/, 1)[0].toLowerCase();
      const nested = src.indexOf('${', i + 2);
      let problem: string | undefined;
      if (nested >= 0 && nested < close) problem = '플레이스홀더 안에 ${...} 를 넣을 수 없습니다';
      else if (!fn) problem = '함수 이름이 없습니다';
      else if (fn !== 'ref' && !known.has(fn)) problem = `알 수 없는 함수: ${fn}`;
      tokens.push({ kind: 'ph', text: src.slice(i, close + 1), start: i, end: close + 1, fn, index: index++, problem });
      i = close + 1;
      textStart = i;
      continue;
    }
    text += src[i];
    i++;
  }
  flush(src.length);
  return tokens;
}

export type GroupColor = 'info' | 'success' | 'secondary' | 'warning' | 'primary';

/** 함수 그룹별 색. 같은 색이 편집기·렌더 결과·값 목록에 똑같이 쓰여 서로 이어 보인다. */
const GROUP_COLOR: Record<string, GroupColor> = {
  네트워크: 'info',
  숫자: 'success',
  문자열: 'secondary',
  시간: 'warning',
};

export function colorOf(fn: string | undefined, specs: Map<string, FunctionSpec>): GroupColor {
  if (!fn) return 'primary';
  return GROUP_COLOR[specs.get(fn)?.group ?? ''] ?? 'primary';
}

/** ref 는 함수 목록(서버 레지스트리)에 없는 내장 문법이라 설명을 따로 둔다. */
export const REF_SPEC: FunctionSpec = {
  name: 'ref',
  signature: 'ref(name)',
  description: '앞에서 ${... as name} 으로 이름 붙인 값을 같은 로그 안에서 그대로 다시 쓴다',
  example: '${ref(cip)}',
  sample: '(앞의 값과 동일)',
  group: '참조',
};

export function specOf(fn: string | undefined, specs: Map<string, FunctionSpec>): FunctionSpec | undefined {
  if (!fn) return undefined;
  return fn === 'ref' ? REF_SPEC : specs.get(fn);
}
