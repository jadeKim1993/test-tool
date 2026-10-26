import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { keyframes } from '@mui/material/styles';
import type { RunState } from '../api/types';
import { MONO } from '../theme';

const pulse = keyframes`
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
`;

/** 전송 중일 때만 깜빡인다. 멈춰 있는 화면에서 움직이는 건 이것 하나뿐이라 눈에 바로 띈다. */
export function PulseDot({ color = 'currentColor' }: { color?: string }) {
  return (
    <Box component="span" sx={{
      width: 7, height: 7, borderRadius: '50%', bgcolor: color,
      animation: `${pulse} 1.4s ease-in-out infinite`, flexShrink: 0,
    }} />
  );
}

const STATE_META: Record<RunState, { label: string; color: 'default' | 'info' | 'success' | 'warning' | 'primary' | 'error' }> = {
  STARTING: { label: '시작 중', color: 'info' },
  RUNNING: { label: '전송 중', color: 'success' },
  STOPPING: { label: '정지 중', color: 'warning' },
  STOPPED: { label: '정지', color: 'default' },
  COMPLETED: { label: '완료', color: 'primary' },
  FAILED: { label: '실패', color: 'error' },
};

/** 실행 상태를 색으로 즉시 구분한다. 모니터링 화면에서는 텍스트보다 색이 빠르다. */
export function StateChip({ state, connection }: { state?: RunState; connection?: string }) {
  const meta = state ? STATE_META[state] : { label: '정지', color: 'default' as const };
  const reconnecting = connection === 'RECONNECTING' || connection === 'FAILED';
  return (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', flexShrink: 0 }}>
      <Chip
        color={meta.color}
        variant={state === 'RUNNING' ? 'filled' : 'outlined'}
        label={
          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
            {state === 'RUNNING' && <PulseDot />}
            <span>{meta.label}</span>
          </Stack>
        }
      />
      {reconnecting && <Chip color="warning" variant="outlined" label="재연결" />}
    </Stack>
  );
}

/** 숫자 하나를 크게 보여주는 타일. 전송 콘솔은 이 타일들의 나열이다. */
export function Metric({ label, value, unit, tone, title }: {
  label: string;
  value: string | number;
  unit?: string;
  tone?: 'ok' | 'warn' | 'bad';
  title?: string;
}) {
  const color = tone === 'bad' ? 'error.main' : tone === 'warn' ? 'warning.main' : 'text.primary';
  return (
    <Box sx={{ minWidth: 0 }} title={title}>
      <Typography variant="caption" sx={{
        color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.04em',
        display: 'block', lineHeight: 1.4,
      }}>
        {label}
      </Typography>
      <Typography component="div" sx={{
        fontFamily: MONO, fontSize: '1.3rem', fontWeight: 600, lineHeight: 1.2,
        whiteSpace: 'nowrap', color,
      }}>
        {value}
        {unit && (
          <Box component="span" sx={{ fontSize: '0.72rem', fontWeight: 400, color: 'text.secondary', ml: 0.4 }}>
            {unit}
          </Box>
        )}
      </Typography>
    </Box>
  );
}

/** 알림을 잠깐 띄웠다가 치우는 기본 시간. 토스트(Snackbar)와 같은 3초. */
export const NOTICE_MS = 3000;

/**
 * 오류 배너. autoHideMs 를 주면 그 시간 뒤에 onClose 를 불러 스스로 사라진다
 * (전송 콘솔처럼 동작 결과를 잠깐 알리는 화면용 — 새로고침해야 지워지던 문제).
 */
export function ErrorAlert({ error, onClose, autoHideMs }: {
  error: string | null;
  onClose?: () => void;
  autoHideMs?: number;
}) {
  useEffect(() => {
    if (!error || !autoHideMs || !onClose) return;
    const timer = window.setTimeout(onClose, autoHideMs);
    return () => window.clearTimeout(timer);
    // onClose 는 매 렌더 새로 만들어지는 경우가 많다. 같은 오류가 떠 있는 동안 타이머를 다시 걸지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error, autoHideMs]);
  if (!error) return null;
  return (
    <Alert severity="error" onClose={onClose} sx={{ mb: 2 }}>
      {error}
    </Alert>
  );
}

export function Loading({ label = '불러오는 중' }: { label?: string }) {
  return (
    <Stack direction="row" spacing={1.5}
           sx={{ alignItems: 'center', justifyContent: 'center', py: 8, color: 'text.secondary' }}>
      <CircularProgress size={18} />
      <Typography variant="body2">{label}</Typography>
    </Stack>
  );
}

export function EmptyState({ icon, title, hint, action }: {
  icon: ReactNode;
  title: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <Stack spacing={1} sx={{ alignItems: 'center', py: 7, px: 2, textAlign: 'center', color: 'text.secondary' }}>
      <Box sx={{ opacity: 0.4, '& svg': { fontSize: 44 } }}>{icon}</Box>
      <Typography variant="subtitle2" sx={{ color: 'text.primary' }}>{title}</Typography>
      {hint && <Typography variant="body2" sx={{ maxWidth: 460 }}>{hint}</Typography>}
      {action && <Box sx={{ pt: 1 }}>{action}</Box>}
    </Stack>
  );
}

/**
 * key 가 새 값이 될 때마다 ms 동안만 true. 상태 변화를 "알림"으로 잠깐 띄우고 치우는 데 쓴다.
 * key 가 null 이 됐다가 같은 값으로 돌아와도(예: 재연결이 다시 시작) 다시 보인다.
 */
export function useTransient(key: string | null, ms = NOTICE_MS): boolean {
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    setShown(key);
    if (key === null) return;
    const timer = window.setTimeout(() => setShown((current) => (current === key ? null : current)), ms);
    return () => window.clearTimeout(timer);
  }, [key, ms]);
  return key !== null && shown === key;
}

/** 저장/시작 같은 동작의 결과를 방해 없이 알린다. */
export function useToast() {
  const [message, setMessage] = useState<{ text: string; tone: 'success' | 'error' } | null>(null);

  const toast = useMemo(() => ({
    ok: (text: string) => setMessage({ text, tone: 'success' as const }),
    bad: (text: string) => setMessage({ text, tone: 'error' as const }),
  }), []);

  const element = (
    <Snackbar
      open={message !== null}
      autoHideDuration={NOTICE_MS}
      onClose={() => setMessage(null)}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      {message ? (
        <Alert severity={message.tone} variant="filled" onClose={() => setMessage(null)} sx={{ boxShadow: 6 }}>
          {message.text}
        </Alert>
      ) : undefined}
    </Snackbar>
  );
  return { toast, element };
}

interface ConfirmOptions {
  title: string;
  message?: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
}

/**
 * window.confirm 대체. 삭제·무제한 전송처럼 되돌리기 어려운 동작 앞에 세운다.
 * <p>브라우저 기본 대화상자와 달리 무엇이 위험한지 색과 문장으로 구분해 보여 준다.
 */
export function useConfirm() {
  const [request, setRequest] = useState<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null);

  const confirm = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => setRequest({ ...options, resolve })),
    [],
  );

  const close = (ok: boolean) => {
    request?.resolve(ok);
    setRequest(null);
  };

  const element = (
    <Dialog open={request !== null} onClose={() => close(false)} maxWidth="xs">
      <DialogTitle sx={{ pb: 1 }}>{request?.title}</DialogTitle>
      {request?.message && (
        <DialogContent>
          <DialogContentText component="div" variant="body2">{request.message}</DialogContentText>
        </DialogContent>
      )}
      <DialogActions>
        <Button color="inherit" onClick={() => close(false)}>취소</Button>
        <Button variant="contained" color={request?.danger ? 'error' : 'primary'}
                onClick={() => close(true)} autoFocus>
          {request?.confirmLabel ?? '확인'}
        </Button>
      </DialogActions>
    </Dialog>
  );

  return { confirm, element };
}

export const fmt = {
  int: (n: number) => Math.round(n).toLocaleString('ko-KR'),
  eps: (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : n.toFixed(0)),
  bytes: (n: number) => {
    if (n < 1024) return `${n} B`;
    if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`;
    return `${(n / 1073741824).toFixed(2)} GB`;
  },
  duration: (sec: number) => {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    return h > 0 ? `${h}시간 ${m}분` : m > 0 ? `${m}분 ${s}초` : `${s}초`;
  },
  time: (iso: string | null) => (iso ? new Date(iso).toLocaleString('ko-KR', { hour12: false }) : '-'),
};
