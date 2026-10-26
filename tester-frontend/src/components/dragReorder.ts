import { useRef, useState, type DragEvent } from 'react';
import type { Theme } from '@mui/material/styles';

/**
 * 목록 끌어서 순서 바꾸기. 브라우저 기본 드래그 이벤트만 쓴다(라이브러리 없이 — 폐쇄망 번들 크기).
 * <p>항목 위에 올리면 위/아래 절반 중 어느 쪽인지에 따라 그 앞/뒤에 파란 선을 그리고, 놓으면 새 id 순서로
 * onReorder 를 부른다. 같은 목록 안에서만 동작한다 — 다른 목록에서 끌어온 것은 무시한다.
 */
export function useDragReorder(ids: number[], onReorder: (ids: number[]) => void) {
  const [dragId, setDragId] = useState<number | null>(null);
  const [over, setOver] = useState<{ id: number; after: boolean } | null>(null);
  // 이벤트 처리는 ref 를 본다. state 는 다음 렌더에야 바뀌므로, 빠르게 놓으면 drop 이 옛 값을 볼 수 있다.
  const dragRef = useRef<number | null>(null);
  const overRef = useRef<{ id: number; after: boolean } | null>(null);

  const reset = () => {
    dragRef.current = null;
    overRef.current = null;
    setDragId(null);
    setOver(null);
  };

  /** 항목 요소에 펼쳐 넣는다. enabled=false 면 끌 수도, 놓을 수도 없다(공유 카테고리 등). */
  const itemProps = (id: number, enabled = true) => {
    if (!enabled) return {};
    return {
      draggable: true,
      onDragStart: (e: DragEvent<HTMLElement>) => {
        dragRef.current = id;
        setDragId(id);
        e.dataTransfer.effectAllowed = 'move';
        // Firefox 는 데이터가 없으면 드래그를 시작하지 않는다.
        e.dataTransfer.setData('text/plain', String(id));
      },
      onDragOver: (e: DragEvent<HTMLElement>) => {
        if (dragRef.current === null) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const rect = e.currentTarget.getBoundingClientRect();
        const after = e.clientY > rect.top + rect.height / 2;
        if (overRef.current?.id !== id || overRef.current.after !== after) {
          overRef.current = { id, after };
          setOver(overRef.current);
        }
      },
      onDrop: (e: DragEvent<HTMLElement>) => {
        e.preventDefault();
        const dragging = dragRef.current;
        const target = overRef.current;
        if (dragging !== null && target && dragging !== target.id) {
          const rest = ids.filter((x) => x !== dragging);
          const index = rest.indexOf(target.id) + (target.after ? 1 : 0);
          const next = [...rest.slice(0, index), dragging, ...rest.slice(index)];
          if (next.some((x, i) => x !== ids[i])) onReorder(next);
        }
        reset();
      },
      onDragEnd: reset,
    };
  };

  /** 항목 sx 에 더한다: 끄는 중인 항목은 흐리게, 놓일 자리에는 위/아래 선. */
  // 해당할 때만 키를 넣는다 — undefined 값을 펼치면 항목이 원래 갖던 opacity 등을 지워 버린다.
  const itemSx = (id: number) => ({
    ...(dragId === id ? { opacity: 0.4 } : {}),
    ...(over?.id === id && dragId !== id ? {
      boxShadow: (theme: Theme) => `inset 0 ${over.after ? -3 : 3}px 0 0 ${theme.palette.primary.main}`,
    } : {}),
  });

  return { itemProps, itemSx, dragging: dragId !== null };
}
