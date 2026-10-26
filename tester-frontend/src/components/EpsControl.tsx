import { useRef, useState } from 'react';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { MONO } from '../theme';
import { fmt } from './common';

const PRESETS = [1_000, 10_000, 50_000, 100_000, 500_000];

/**
 * 속도 입력. 프리셋 버튼과 직접 입력을 나란히 둔다.
 * <p>부하 테스트에서 가장 자주 만지는 값이라 한 번에 보이는 게 중요하다. 0 은 "무제한"이다.
 * <p>입력칸에 마우스를 올리거나 누르면 숫자가 바로 비워져 새 값을 그냥 치면 된다(기존 값은 흐린 글씨로 남는다).
 * 아무것도 안 치고 나가거나 Esc 를 누르면 원래 값으로 돌아간다. 평소에는 천 단위 쉼표로 보여 준다.
 * 마우스만 올렸을 때는 다른 입력칸을 쓰는 중이 아닐 때만 포커스를 가져온다 — 지나가다 입력을 빼앗지 않도록.
 */
export function EpsControl({ value, onChange, disabled }: {
  value: number;
  onChange: (eps: number) => void;
  disabled?: boolean;
}) {
  // null 이면 편집 중이 아니다. 편집 중에는 사용자가 친 글자 그대로(빈칸 포함) 보여 준다.
  const [draft, setDraft] = useState<string | null>(null);
  const original = useRef(value);
  const inputRef = useRef<HTMLInputElement>(null);
  // 마우스를 올려서 들어온 편집이면, 아무것도 안 치고 나갈 때 편집을 끝낸다(클릭으로 들어오면 유지).
  const fromHover = useRef(false);

  const otherFieldActive = () => {
    const active = document.activeElement as HTMLElement | null;
    return active !== inputRef.current && active !== null
      && (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement || active.isContentEditable);
  };

  return (
    <Stack spacing={0.75} sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField
          value={draft ?? (value === 0 ? '무제한' : fmt.int(value))}
          placeholder={value === 0 ? '무제한' : fmt.int(value)}
          disabled={disabled}
          inputRef={inputRef}
          onMouseEnter={() => {
            if (disabled || draft !== null || otherFieldActive()) return;
            fromHover.current = true;
            inputRef.current?.focus();
          }}
          onMouseLeave={() => {
            if (fromHover.current && draft === '') inputRef.current?.blur();
          }}
          onMouseDown={() => { fromHover.current = false; }}
          onFocus={() => {
            original.current = value;
            setDraft('');
          }}
          onChange={(e) => {
            const digits = e.target.value.replace(/[^\d]/g, '').slice(0, 9);
            setDraft(digits);
            fromHover.current = false;
            // 다 지우면 원래 값으로 둔다 — 입력 도중 빈칸이 0(무제한)으로 저장되면 위험하다.
            onChange(digits === '' ? original.current : Number(digits));
          }}
          onBlur={() => {
            fromHover.current = false;
            setDraft(null);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
            if (e.key === 'Escape') {
              // 대화상자 안에서 Esc 가 위로 올라가면 대화상자째 닫힌다. 여기서는 입력 취소만 한다.
              e.stopPropagation();
              onChange(original.current);
              (e.target as HTMLInputElement).blur();
            }
          }}
          slotProps={{
            input: { startAdornment: <InputAdornment position="start">EPS</InputAdornment> },
            htmlInput: {
              inputMode: 'numeric',
              'aria-label': '초당 전송 건수',
              style: { textAlign: 'right', fontFamily: MONO },
            },
          }}
          sx={{ width: 170, flexShrink: 0 }}
        />

        <ToggleButtonGroup
          exclusive
          disabled={disabled}
          value={value}
          onChange={(_, next) => {
            // 이미 선택된 버튼을 다시 누르면 null 이 온다. 속도를 지울 수는 없으므로 무시한다.
            if (next !== null) onChange(next as number);
          }}
        >
          {PRESETS.map((preset) => (
            <ToggleButton key={preset} value={preset}>{fmt.eps(preset)}</ToggleButton>
          ))}
          <ToggleButton value={0} color="error" title="속도 제한 없이 낼 수 있는 최대치로 전송합니다">
            MAX
          </ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      {value === 0 && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', color: 'error.main' }}>
          <WarningAmberIcon sx={{ fontSize: 15 }} />
          <Typography variant="caption">
            무제한 모드: 장비가 낼 수 있는 최대 속도로 전송합니다.
          </Typography>
        </Stack>
      )}
    </Stack>
  );
}
