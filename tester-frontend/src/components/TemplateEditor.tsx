import { useEffect, useLayoutEffect, useMemo, useRef, useState, type Ref } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';

import type { FunctionSpec } from '../api/types';
import { MONO } from '../theme';
import { colorOf, specOf, tokenize, type Token } from './templateSyntax';

/** 강조 레이어와 입력창이 글자 단위로 겹쳐야 하므로 둘이 같은 값을 써야 한다. */
const TEXT_STYLE = {
  fontFamily: MONO,
  fontSize: '0.82rem',
  lineHeight: 1.6,
  padding: '10px 12px',
  margin: 0,
  border: 0,
  boxSizing: 'border-box',
  wordBreak: 'normal',
  letterSpacing: 'normal',
  tabSize: 4,
  // 세로 스크롤바가 생기면 입력창만 폭이 줄어 줄바꿈 위치가 강조 레이어와 어긋난다. 둘 다 자리를 비워 둔다.
  scrollbarGutter: 'stable',
} as const;

const WRAP_STYLE = { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' } as const;
const NOWRAP_STYLE = { whiteSpace: 'pre', overflowWrap: 'normal' } as const;

const MIN_ROWS = 6;
const MAX_ROWS = 20;
const WRAP_KEY = 'tester.templateWrap';

function loadWrap(): boolean {
  try {
    return localStorage.getItem(WRAP_KEY) !== 'off';
  } catch {
    return true;
  }
}

/**
 * 템플릿 편집기.
 * <p>투명한 textarea 아래에 같은 글꼴의 강조 레이어를 깔아, 입력은 평범한 textarea 그대로 두면서
 * {@code ${...}} 를 함수 그룹 색으로 칠한다(외부 에디터 라이브러리 없이 — 폐쇄망 번들 크기 때문).
 * <ul>
 *   <li>{@code ${} 를 치면 커서 위치에 함수 목록이 뜬다. Enter/Tab 으로 넣으면 인자 부분이 선택돼 바로 고쳐 쓸 수 있다.</li>
 *   <li>커서가 놓인 플레이스홀더의 설명과 예시를 아래 줄에 보여 준다.</li>
 *   <li>{@code activeIndex} 는 렌더 결과에서 마우스를 올린 값 — 원문의 해당 부분을 진하게 표시한다.</li>
 * </ul>
 */
export function TemplateEditor({
  value, onChange, functions, disabled, error, errorPosition, activeIndex, inputRef, placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  functions: FunctionSpec[];
  disabled?: boolean;
  error?: string | null;
  errorPosition?: number | null;
  activeIndex?: number | null;
  inputRef: Ref<HTMLTextAreaElement>;
  placeholder?: string;
}) {
  const theme = useTheme();
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const layerRef = useRef<HTMLPreElement>(null);
  const anchorRef = useRef<HTMLSpanElement>(null);

  const [caret, setCaret] = useState(0);
  const [focused, setFocused] = useState(false);
  const [suggest, setSuggest] = useState<{ start: number; query: string } | null>(null);
  const [dismissedAt, setDismissedAt] = useState<number | null>(null);
  const [active, setActive] = useState(0);
  // 액세스 로그처럼 긴 한 줄은 가로 스크롤로는 앞뒤를 같이 볼 수 없다. 기본은 줄바꿈.
  const [wrap, setWrap] = useState(loadWrap);

  const toggleWrap = () => {
    setWrap((prev) => {
      try { localStorage.setItem(WRAP_KEY, prev ? 'off' : 'on'); } catch { /* 저장 못 해도 동작에는 지장 없다 */ }
      return !prev;
    });
  };

  const specs = useMemo(() => new Map(functions.map((f) => [f.name, f])), [functions]);
  const known = useMemo(() => new Set(functions.map((f) => f.name)), [functions]);
  const tokens = useMemo(() => tokenize(value, known), [value, known]);

  const matches = useMemo(() => {
    if (!suggest) return [];
    const q = suggest.query.toLowerCase();
    const starts = functions.filter((f) => f.name.startsWith(q));
    const contains = q ? functions.filter((f) => !f.name.startsWith(q) && f.name.includes(q)) : [];
    return [...starts, ...contains];
  }, [suggest, functions]);

  const open = focused && !disabled && suggest !== null && suggest.start !== dismissedAt && matches.length > 0;

  useEffect(() => setActive(0), [suggest?.query]);

  /** 커서 앞이 {@code ${이름일부} 이면 자동완성을 연다. 이미 있는 플레이스홀더 한가운데서는 열지 않는다. */
  const track = (ta: HTMLTextAreaElement) => {
    const pos = ta.selectionStart;
    setCaret(pos);
    if (pos !== ta.selectionEnd) {
      setSuggest(null);
      return;
    }
    const before = ta.value.slice(0, pos);
    const match = /\$\{([A-Za-z0-9_]*)$/.exec(before);
    const nextChar = ta.value.charAt(pos);
    if (!match || before.endsWith('\\' + match[0]) || /[A-Za-z0-9_(]/.test(nextChar)) {
      setSuggest(null);
      setDismissedAt(null);
      return;
    }
    setSuggest({ start: pos - match[0].length, query: match[1] });
  };

  const accept = (spec: FunctionSpec) => {
    const ta = textareaRef.current;
    if (!ta || !suggest) return;
    const end = ta.selectionStart + (value.charAt(ta.selectionStart) === '}' ? 1 : 0);
    const next = value.slice(0, suggest.start) + spec.example + value.slice(end);
    const start = suggest.start;
    onChange(next);
    setSuggest(null);
    requestAnimationFrame(() => {
      ta.focus();
      // 인자 부분을 선택해 두면 예시 값이 힌트로 보이면서 바로 덮어쓸 수 있다.
      const openParen = spec.example.indexOf('(');
      const closeParen = spec.example.lastIndexOf(')');
      if (openParen > 0 && closeParen > openParen + 1) {
        ta.setSelectionRange(start + openParen + 1, start + closeParen);
      } else {
        ta.setSelectionRange(start + spec.example.length, start + spec.example.length);
      }
      track(ta);
    });
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      setActive((i) => (i + step + matches.length) % matches.length);
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      accept(matches[active]);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setDismissedAt(suggest!.start);
    }
  };

  // 목록이 길면 키보드로 고른 항목이 보이도록 따라 내려간다.
  useEffect(() => {
    document.getElementById(`tpl-suggest-${active}`)?.scrollIntoView({ block: 'nearest' });
  }, [active, open]);

  const syncScroll = () => {
    if (layerRef.current && textareaRef.current) {
      layerRef.current.scrollTop = textareaRef.current.scrollTop;
      layerRef.current.scrollLeft = textareaRef.current.scrollLeft;
    }
  };
  useEffect(syncScroll, [value]);

  // 내용만큼 자란다(최대 MAX_ROWS 줄). 줄바꿈 모드에서는 줄 수가 아니라 실제 높이를 재야 맞다.
  useLayoutEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    const style = getComputedStyle(ta);
    const lineHeight = parseFloat(style.lineHeight);
    const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    ta.style.height = 'auto';
    const height = Math.min(Math.max(ta.scrollHeight, lineHeight * MIN_ROWS + padding), lineHeight * MAX_ROWS + padding);
    ta.style.height = `${Math.ceil(height)}px`;
    syncScroll();
  }, [value, wrap]);

  const caretToken = tokens.find((t) => t.kind === 'ph' && caret > t.start && caret < t.end) ?? null;
  const caretSpec = specOf(caretToken?.fn, specs);
  const anchorAt = open ? suggest!.start : -1;
  // ${ 를 막 친 순간은 당연히 "닫히지 않은 플레이스홀더"다. 고르는 동안에는 빨간 표시를 미룬다.
  const shownError = open ? null : error;
  const lineStyle = wrap ? WRAP_STYLE : NOWRAP_STYLE;

  const renderToken = (token: Token) => {
    const anchored = anchorAt >= token.start && anchorAt < token.end;
    const content = anchored ? (
      <>
        {token.text.slice(0, anchorAt - token.start)}
        <span ref={anchorRef} />
        {token.text.slice(anchorAt - token.start)}
      </>
    ) : token.text;
    if (token.kind === 'text') return <span key={token.start}>{content}</span>;

    const problem = open && token.start === anchorAt
      ? undefined
      : token.problem ?? (errorPosition === token.start ? shownError ?? undefined : undefined);
    const color = problem ? 'error' : colorOf(token.fn, specs);
    const main = theme.palette[color].main;
    const highlighted = token.index !== undefined && token.index === activeIndex;
    return (
      <Box key={token.start} component="span" sx={{
        color: main,
        bgcolor: alpha(main, highlighted ? 0.32 : 0.1),
        borderRadius: '3px',
        boxShadow: highlighted ? `0 0 0 1px ${main}` : undefined,
        textDecoration: problem ? `underline wavy ${main}` : undefined,
        textUnderlineOffset: '3px',
      }}>
        {content}
      </Box>
    );
  };

  return (
    <Box>
      <Box sx={{
        position: 'relative',
        borderRadius: 1,
        border: 1,
        borderColor: shownError ? 'error.main' : 'divider',
        bgcolor: 'background.paper',
        opacity: disabled ? 0.7 : 1,
        '&:focus-within': {
          borderColor: shownError ? 'error.main' : 'primary.main',
          boxShadow: `0 0 0 1px ${shownError ? theme.palette.error.main : theme.palette.primary.main}`,
        },
      }}>
        <Box component="pre" ref={layerRef} aria-hidden sx={{
          ...TEXT_STYLE,
          ...lineStyle,
          position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none',
          color: 'text.primary',
        }}>
          {tokens.map(renderToken)}
          {anchorAt === value.length && <span ref={anchorRef} />}
          {/* 마지막 줄이 빈 줄이면 높이가 어긋나지 않도록 한 줄을 더 둔다. */}
          {'\n '}
        </Box>
        <Box
          component="textarea"
          ref={(el: HTMLTextAreaElement | null) => {
            textareaRef.current = el;
            if (typeof inputRef === 'function') inputRef(el);
            else if (inputRef) (inputRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
          }}
          value={value}
          rows={MIN_ROWS}
          wrap={wrap ? 'soft' : 'off'}
          spellCheck={false}
          disabled={disabled}
          placeholder={placeholder}
          aria-label="템플릿 내용"
          onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => { onChange(e.target.value); track(e.target); }}
          onSelect={(e: React.SyntheticEvent<HTMLTextAreaElement>) => track(e.currentTarget)}
          onKeyDown={onKeyDown}
          onScroll={syncScroll}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          sx={{
            ...TEXT_STYLE,
            ...lineStyle,
            position: 'relative', display: 'block', width: '100%',
            resize: 'none', outline: 0, overflow: 'auto',
            bgcolor: 'transparent', color: 'transparent',
            caretColor: theme.palette.text.primary,
            '&::placeholder': { color: 'text.disabled' },
            '&::selection': { bgcolor: alpha(theme.palette.primary.main, 0.3), color: 'transparent' },
          }}
        />
      </Box>

      {/* 오류 > 커서 위치 함수 설명 > 사용법 힌트 순으로 한 줄만 보여 준다. */}
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1, mt: 0.5 }}>
      <Typography variant="caption" component="div" sx={{
        flex: 1, minWidth: 0, minHeight: 20, px: 0.25,
        color: shownError ? 'error.main' : 'text.secondary',
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
      }}>
        {shownError ? shownError : caretToken && caretSpec ? (
          <>
            <Box component="span" sx={{ fontFamily: MONO, color: `${colorOf(caretToken.fn, specs)}.main` }}>
              {caretSpec.signature}
            </Box>
            {' — '}{caretSpec.description}
            <Box component="span" sx={{ opacity: 0.7 }}> · 예: {caretSpec.sample}</Box>
          </>
        ) : caretToken?.problem ? (
          <Box component="span" sx={{ color: 'error.main' }}>{caretToken.problem}</Box>
        ) : (
          <>
            <Box component="code" sx={{ fontFamily: MONO }}>{'${'}</Box> 를 입력하면 함수 목록이 뜹니다
            · 커서를 <Box component="code" sx={{ fontFamily: MONO }}>{'${...}'}</Box> 안에 두면 설명이 보입니다 · Ctrl+S 저장
          </>
        )}
      </Typography>
      <Typography component="button" type="button" variant="caption" onClick={toggleWrap}
                  title="긴 줄을 편집기 폭에 맞춰 접어 보여 줍니다(저장되는 내용은 바뀌지 않음)"
                  sx={{
                    flexShrink: 0, border: 0, bgcolor: 'transparent', cursor: 'pointer', p: 0,
                    color: wrap ? 'primary.main' : 'text.secondary', fontFamily: 'inherit',
                  }}>
        {wrap ? '✓ ' : ''}줄바꿈
      </Typography>
      </Box>

      <Popper
        open={open}
        anchorEl={() => anchorRef.current ?? textareaRef.current!}
        placement="bottom-start"
        sx={{ zIndex: theme.zIndex.modal }}
        modifiers={[{ name: 'offset', options: { offset: [0, 4] } }]}
      >
        <Paper elevation={8} sx={{ width: 380, maxHeight: 280, overflowY: 'auto', py: 0.5 }}
               onMouseDown={(e) => e.preventDefault()}>
          {matches.map((spec, i) => (
            <Box
              key={spec.name}
              id={`tpl-suggest-${i}`}
              onMouseEnter={() => setActive(i)}
              onClick={() => accept(spec)}
              sx={{
                px: 1.25, py: 0.5, cursor: 'pointer',
                bgcolor: i === active ? 'action.selected' : undefined,
              }}
            >
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'baseline' }}>
                <Typography sx={{ fontFamily: MONO, fontSize: '0.78rem', color: `${colorOf(spec.name, specs)}.main` }}>
                  {spec.signature}
                </Typography>
                <Typography variant="caption" color="textSecondary" sx={{ ml: 'auto', flexShrink: 0 }}>
                  {spec.group}
                </Typography>
              </Box>
              <Typography variant="caption" color="textSecondary" noWrap sx={{ display: 'block' }}>
                {spec.description}
              </Typography>
            </Box>
          ))}
          <Typography variant="caption" color="textSecondary" sx={{
            display: 'block', px: 1.25, pt: 0.5, mt: 0.5, borderTop: 1, borderColor: 'divider',
          }}>
            ↑↓ 선택 · Enter/Tab 넣기 · Esc 닫기
          </Typography>
        </Paper>
      </Popper>
    </Box>
  );
}
