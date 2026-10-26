import { useEffect, useRef } from 'react';

/** 입력칸에 글자를 치는 중인가. 단축키가 타이핑을 가로채면 안 된다. */
export function isTyping(): boolean {
  const el = document.activeElement as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

/**
 * 한 글자 단축키. 수정자 키(Ctrl/⌘/Alt)를 누른 경우, 입력 중일 때, 대화상자가 열려 있을 때는 무시한다.
 * handler 는 최신 것을 쓴다(매 렌더 바뀌어도 리스너를 다시 걸지 않는다).
 */
export function useHotkey(key: string, handler: () => void, enabled = true) {
  const latest = useRef(handler);
  latest.current = handler;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return;
      if (isTyping() || document.querySelector('[role="dialog"]')) return;
      if (e.key !== key) return;
      e.preventDefault();
      latest.current();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [key, enabled]);
}
