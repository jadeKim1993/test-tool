import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';

import AddIcon from '@mui/icons-material/Add';
import CancelIcon from '@mui/icons-material/Cancel';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import PowerSettingsNewIcon from '@mui/icons-material/PowerSettingsNew';
import SearchIcon from '@mui/icons-material/Search';

import { api } from '../api/client';
import type { HttpFormat, MetaOptions, ProtocolInfo, Target, TestResult } from '../api/types';
import { EmptyState, ErrorAlert, Loading, useConfirm, useToast } from '../components/common';
import { TargetAddress, sharedSubnets } from '../components/TargetAddress';
import { useHotkey } from '../hooks/useHotkey';
import { HTTP_FORMAT_SHORT, PROTOCOL_COLOR, compareTargets, isHttp } from '../components/targets';
import { MONO } from '../theme';

/** 자동 연결 확인 주기. 수집 서버에 부담이 없도록 넉넉하게. */
const AUTO_CHECK_MS = 60_000;
const AUTO_CHECK_KEY = 'tester.targetsAutoCheck';
/** 한 번에 확인할 대상 수. 대상이 많아도 타임아웃(3초)이 줄줄이 쌓이지 않게 몇 개씩 동시에. */
const CHECK_PARALLEL = 4;

const clock = (millis: number) => new Date(millis).toTimeString().slice(0, 8);

/** 프로토콜별 기본 포트. 새 대상을 추가하거나 프로토콜을 바꿀 때 채워 넣는다(HTTP 는 본문 형식의 기본 포트). */
const DEFAULT_PORTS: Record<string, number> = { TCP: 7720, UDP: 514, TLS: 7721 };

/** HTTP 는 응답을 기다리는 동안 묶어 보내므로 스트림(16KB)보다 큰 묶음이 기본이다. */
const HTTP_BATCH_BYTES = 64 * 1024;

/** 형식별 헤더 예시. 수집기마다 인증 방식이 다르다. */
const HEADER_HINT: Record<string, string> = {
  LINES: '예) Authorization: Bearer <토큰>',
  NDJSON: '예) Authorization: Bearer <토큰>',
  JSON_ARRAY: '예) Authorization: Bearer <토큰>',
  SPLUNK_HEC: 'Authorization: Splunk <HEC 토큰>',
  ES_BULK: 'Authorization: ApiKey <키>\n또는 Authorization: Basic <base64(아이디:비밀번호)>',
  LOKI: 'X-Scope-OrgID: <테넌트>   (멀티 테넌트일 때만)',
};

/** "1.2.3.4", ".110", ":514", "1.2.3.4:514", "udp" 어느 형태로 쳐도 걸리게 한다. */
function matches(target: Target, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return `${target.protocol} ${target.host}:${target.port}`.toLowerCase().includes(q);
}

/**
 * 전송 대상(수집 서버) 관리.
 * <p>대상은 <b>프로토콜 + 호스트 + 포트</b>로 구분한다. 이름이나 커넥션 수는 받지 않는다
 * (이름은 주소에서 파생되고, 커넥션 수는 워커 수에 맞춰 엔진이 잡는다).
 * <p>표를 프로토콜별로 나누던 방식 대신, 주소를 크게 쓴 카드를 IP·포트 순으로 늘어놓는다.
 * 사람은 대상을 "몇 번 서버 몇 번 포트"로 기억하므로 주소가 곧 식별자다. 프로토콜은 카드의 표시일 뿐 묶음 기준이 아니다.
 */
export default function Targets() {
  const { toast, element: toastElement } = useToast();
  const { confirm, element: confirmElement } = useConfirm();

  const [targets, setTargets] = useState<Target[]>([]);
  const [usage, setUsage] = useState<Record<number, string[]>>({});
  const [protocols, setProtocols] = useState<ProtocolInfo[]>([]);
  const [options, setOptions] = useState<MetaOptions | null>(null);
  const [editing, setEditing] = useState<Partial<Target> | null>(null);
  const [results, setResults] = useState<Record<number, TestResult>>({});
  const [testing, setTesting] = useState<number | null>(null);
  const [testingAll, setTestingAll] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  // 화면이 열려 있는 동안 주기적으로 연결을 확인해 카드에 상태 점으로 보여 준다(UDP 는 확인할 수 없어 뺀다).
  const [autoCheck, setAutoCheck] = useState(() => {
    try { return localStorage.getItem(AUTO_CHECK_KEY) !== 'off'; } catch { return true; }
  });
  const [checkedAt, setCheckedAt] = useState<Record<number, number>>({});
  const [lastRound, setLastRound] = useState<number | null>(null);
  const checking = useRef(false);
  const targetsRef = useRef<Target[]>([]);

  const visible = useMemo(
    () => targets.filter((t) => matches(t, query)).sort(compareTargets),
    [targets, query],
  );
  const subnets = useMemo(() => sharedSubnets(visible), [visible]);
  const filtering = query.trim() !== '';
  const visibleSelected = visible.filter((t) => selected.has(t.id));

  const reload = async () => {
    try {
      const [targetList, jobs] = await Promise.all([api.targets(), api.jobs().catch(() => [])]);
      setTargets(targetList);
      // 어떤 작업이 쓰는지 카드에 적는다 — 삭제가 왜 안 되는지도 여기서 미리 보인다.
      const map: Record<number, string[]> = {};
      for (const job of jobs) (map[job.targetId] ??= []).push(job.name);
      setUsage(map);
      setSelected((prev) => new Set([...prev].filter((id) => targetList.some((t) => t.id === id))));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    reload();
    api.protocols().then(setProtocols).catch(() => undefined);
    api.options().then(setOptions).catch(() => undefined);
  }, []);

  targetsRef.current = targets;

  /** 조용히 전부 확인한다(토스트 없이). 수동 테스트와 같은 API 를 쓰고 결과도 같은 자리에 둔다. */
  const checkAll = useCallback(async () => {
    if (checking.current) return;
    checking.current = true;
    try {
      const queue = targetsRef.current.filter((t) => t.protocol !== 'UDP');
      for (let i = 0; i < queue.length; i += CHECK_PARALLEL) {
        await Promise.all(queue.slice(i, i + CHECK_PARALLEL).map(async (t) => {
          try {
            const result = await api.testTarget(t.id);
            setResults((prev) => ({ ...prev, [t.id]: result }));
            setCheckedAt((prev) => ({ ...prev, [t.id]: Date.now() }));
          } catch {
            // 대상이 방금 지워졌거나 서버가 잠깐 안 될 때. 다음 주기에 다시 본다.
          }
        }));
      }
      setLastRound(Date.now());
    } finally {
      checking.current = false;
    }
  }, []);

  useEffect(() => {
    if (!autoCheck || targets.length === 0) return;
    checkAll();
    const timer = window.setInterval(checkAll, AUTO_CHECK_MS);
    return () => window.clearInterval(timer);
    // 대상이 추가·삭제될 때만 다시 건다(결과가 바뀔 때마다 걸면 끝없이 돈다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoCheck, targets.length, checkAll]);

  const toggleAutoCheck = (on: boolean) => {
    setAutoCheck(on);
    try { localStorage.setItem(AUTO_CHECK_KEY, on ? 'on' : 'off'); } catch { /* 기억 못 해도 동작에는 지장 없다 */ }
  };

  const test = async (target: Target) => {
    setTesting(target.id);
    try {
      const result = await api.testTarget(target.id);
      setResults((prev) => ({ ...prev, [target.id]: result }));
      setCheckedAt((prev) => ({ ...prev, [target.id]: Date.now() }));
      return result;
    } catch (e) {
      setError((e as Error).message);
      return null;
    } finally {
      setTesting(null);
    }
  };

  /** 화면에 보이는 대상을 순서대로 찔러 본다. 수집 서버가 여러 대일 때 한 번에 확인한다. */
  const testAll = async () => {
    setTestingAll(true);
    let ok = 0;
    for (const target of visible) {
      const result = await test(target);
      if (result?.ok) ok += 1;
    }
    setTestingAll(false);
    toast.ok(`${visible.length}개 중 ${ok}개 연결 성공`);
  };

  const toggle = (id: number) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });

  const toggleAllVisible = () => setSelected((prev) => {
    const next = new Set(prev);
    const all = visible.every((t) => next.has(t.id));
    for (const t of visible) {
      if (all) next.delete(t.id);
      else next.add(t.id);
    }
    return next;
  });

  /** 한 개든 여러 개든 같은 경로로 지운다. 작업이 쓰고 있는 대상은 건너뛰고 알려 준다. */
  const removeMany = async (list: Target[]) => {
    if (list.length === 0) return;
    const inUse = list.filter((t) => usage[t.id]?.length);
    const ok = await confirm({
      title: list.length === 1 ? '전송 대상을 삭제할까요?' : `전송 대상 ${list.length}개를 삭제할까요?`,
      message: <>
        <Box component="span" sx={{ fontFamily: MONO }}>
          {list.slice(0, 5).map((t) => `${t.host}:${t.port} (${t.protocol})`).join(', ')}
          {list.length > 5 && ` 외 ${list.length - 5}개`}
        </Box>
        {inUse.length > 0 && (
          <Box component="span" sx={{ display: 'block', mt: 1, color: 'warning.main' }}>
            {inUse.length}개는 전송 작업이 쓰고 있어 삭제되지 않습니다.
          </Box>
        )}
      </>,
      confirmLabel: '삭제',
      danger: true,
    });
    if (!ok) return;
    let deleted = 0;
    const failed: string[] = [];
    // 작업이 쓰는 대상은 서버가 어차피 거절한다. 위에서 이미 알렸으니 요청하지 않고 건너뛴다.
    for (const target of list.filter((t) => !usage[t.id]?.length)) {
      try {
        await api.deleteTarget(target.id);
        deleted += 1;
      } catch (e) {
        failed.push(`${target.host}:${target.port} — ${(e as Error).message}`);
      }
    }
    toast.ok(`${deleted}개 삭제했습니다${inUse.length ? ` · 사용 중인 ${inUse.length}개는 건너뜀` : ''}`);
    if (failed.length) setError(`삭제하지 못한 대상 ${failed.length}개: ${failed.join(' / ')}`);
    reload();
  };

  const openNew = () => {
    const protocol = protocols[0]?.protocol ?? 'TCP';
    setEditing({
      protocol, host: '', port: DEFAULT_PORTS[protocol] ?? 7720, framing: 'NEWLINE',
    });
  };

  useHotkey('n', openNew);
  useHotkey('/', () => searchRef.current?.focus());

  if (loading) return <Loading />;

  const allVisibleSelected = visible.length > 0 && visible.every((t) => selected.has(t.id));

  return (
    <>
      <Stack direction="row" spacing={2} useFlexGap
             sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', mb: 2 }}>
        <Box>
          <Typography variant="h6">
            전송 대상
            {targets.length > 0 && (
              <Box component="span" sx={{ ml: 1, color: 'text.secondary', fontWeight: 400 }}>{targets.length}</Box>
            )}
          </Typography>
          <Typography variant="body2" color="textSecondary">
            로그를 받을 수집 서버. IP·포트 순으로 정렬됩니다.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} sx={{ ml: 'auto', '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
          {targets.length > 0 && (
            <Button variant="outlined" color="inherit"
                    startIcon={testingAll
                      ? <CircularProgress size={15} color="inherit" />
                      : <PowerSettingsNewIcon />}
                    disabled={testingAll || visible.length === 0}
                    onClick={testAll}>
              {filtering ? `표시된 ${visible.length}개 연결 테스트` : '전체 연결 테스트'}
            </Button>
          )}
          <Tooltip title="대상 추가 (N)">
            <Button variant="contained" startIcon={<AddIcon />} onClick={openNew}>
              대상 추가
            </Button>
          </Tooltip>
        </Stack>
      </Stack>

      <ErrorAlert error={error} onClose={() => setError(null)} />

      {targets.length === 0 ? (
        <Paper variant="outlined">
          <EmptyState
            icon={<HubOutlinedIcon />}
            title="등록된 전송 대상이 없습니다"
            hint="Fluent-bit, Logstash 등 로그를 받을 서버의 IP와 포트를 등록하세요."
            action={<Button variant="contained" startIcon={<AddIcon />} onClick={openNew}>대상 추가</Button>}
          />
        </Paper>
      ) : (
        <>
          <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1.5 }}>
            <TextField
              placeholder="IP·포트·프로토콜 (예: .110, :514, udp)   /"
              value={query} onChange={(e) => setQuery(e.target.value)}
              inputRef={searchRef}
              onKeyDown={(e) => { if (e.key === 'Escape') { setQuery(''); (e.target as HTMLInputElement).blur(); } }}
              sx={{ flex: '1 1 260px', maxWidth: 380 }}
              slotProps={{
                input: {
                  startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>,
                },
              }}
            />
            <Tooltip title={`TCP·TLS·HTTP 대상의 연결을 ${AUTO_CHECK_MS / 60_000}분마다 확인해 카드에 점으로 표시합니다(UDP 는 확인할 수 없음)`}>
              <Stack direction="row" spacing={0.25} sx={{ alignItems: 'center' }}>
                <Switch size="small" checked={autoCheck} onChange={(e) => toggleAutoCheck(e.target.checked)} />
                <Typography variant="body2" color="textSecondary" sx={{ whiteSpace: 'nowrap' }}>
                  자동 연결 확인{autoCheck && lastRound ? ` · ${clock(lastRound)}` : ''}
                </Typography>
              </Stack>
            </Tooltip>
            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', ml: 'auto' }}>
              <Checkbox size="small" checked={allVisibleSelected}
                        indeterminate={visibleSelected.length > 0 && !allVisibleSelected}
                        disabled={visible.length === 0}
                        onChange={toggleAllVisible}
                        slotProps={{ input: { 'aria-label': '보이는 대상 모두 선택' } }} />
              <Typography variant="body2" color="textSecondary" sx={{ mr: 1 }}>
                {visibleSelected.length > 0 ? `${visibleSelected.length}개 선택` : '전체 선택'}
              </Typography>
              <Button variant="outlined" color="error" startIcon={<DeleteOutlineIcon />}
                      disabled={visibleSelected.length === 0}
                      onClick={() => removeMany(visibleSelected)}>
                선택 삭제
              </Button>
            </Stack>
          </Stack>

          {visible.length === 0 ? (
            <Paper variant="outlined">
              <Typography variant="body2" color="textSecondary" sx={{ py: 4, textAlign: 'center' }}>
                “{query}” 에 맞는 대상이 없습니다
              </Typography>
            </Paper>
          ) : (
            <Box sx={{
              display: 'grid', gap: 1.5,
              gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            }}>
              {visible.map((target) => (
                <TargetCard key={target.id} target={target} subnets={subnets}
                            usedBy={usage[target.id] ?? []}
                            framingLabel={options?.framings.find((o) => o.value === target.framing)?.label}
                            selected={selected.has(target.id)}
                            result={results[target.id]} checkedAt={checkedAt[target.id]}
                            testing={testing === target.id}
                            onSelect={() => toggle(target.id)}
                            onTest={() => test(target)} onEdit={() => setEditing(target)}
                            onDelete={() => removeMany([target])} />
              ))}
            </Box>
          )}
        </>
      )}

      {editing && options && (
        <TargetDialog
          target={editing}
          protocols={protocols}
          options={options}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); toast.ok('저장했습니다'); reload(); }}
          onError={setError}
        />
      )}
      {toastElement}
      {confirmElement}
    </>
  );
}

/**
 * 대상 하나. 주소를 가장 크게 쓰고, 다른 대상과 같은 앞부분(대역)은 흐리게, 구별되는 부분과 포트는 진하게 둔다.
 * 프로토콜은 왼쪽 색 띠와 작은 표시로만 — 같은 IP·포트에 TCP/UDP 를 둘 다 둔 경우를 가려내는 용도다.
 */
function TargetCard({
  target, subnets, usedBy, framingLabel, selected, result, checkedAt, testing, onSelect, onTest, onEdit, onDelete,
}: {
  target: Target;
  subnets: Set<string>;
  usedBy: string[];
  framingLabel?: string;
  selected: boolean;
  result?: TestResult;
  checkedAt?: number;
  testing: boolean;
  onSelect: () => void;
  onTest: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const color = PROTOCOL_COLOR[target.protocol] ?? 'default';
  const udp = target.protocol === 'UDP';
  const health = udp ? { color: 'text.disabled', hollow: true, title: 'UDP 는 비연결형이라 연결을 확인할 수 없습니다' }
    : !result ? { color: 'text.disabled', hollow: false, title: '아직 확인하지 않았습니다' }
      : result.ok ? { color: 'success.main', hollow: false, title: `연결됨 ${result.latencyMs}ms${checkedAt ? ` · ${clock(checkedAt)} 확인` : ''}` }
        : { color: 'error.main', hollow: false, title: `연결 실패${checkedAt ? ` (${clock(checkedAt)})` : ''} — ${result.message}` };
  const details = (isHttp(target.protocol) ? [
    `POST ${target.httpPath}`,
    HTTP_FORMAT_SHORT[target.httpFormat] ?? target.httpFormat,
    target.httpHeaders?.trim() ? '헤더 있음' : null,
  ] : [
    framingLabel?.replace(/\s*\(기본\)/, '') ?? target.framing,
    target.protocol === 'UDP' ? null : `배치 ${(target.batchBytes / 1024).toFixed(0)}KB`,
  ]).filter(Boolean).join(' · ');

  return (
    <Paper variant="outlined" sx={{
      position: 'relative', overflow: 'hidden', pl: 2, pr: 1, pt: 1, pb: 0.75,
      borderColor: selected ? 'primary.main' : 'divider',
      boxShadow: selected ? (theme) => `0 0 0 1px ${theme.palette.primary.main}` : undefined,
      '&::before': {
        content: '""', position: 'absolute', left: 0, top: 0, bottom: 0, width: 4,
        bgcolor: color === 'default' ? 'text.disabled' : `${color}.main`,
      },
    }}>
      <Stack direction="row" sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {/* 식별자 = 주소. 고정폭 큰 글씨로, 같은 대역 앞부분은 흐리게 — TargetAddress */}
          <TargetAddress target={target} subnets={subnets} />
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mt: 0.25, minWidth: 0 }}>
            <Chip label={target.protocol} color={color} variant="outlined"
                  icon={target.protocol === 'TLS' ? <LockOutlinedIcon /> : undefined}
                  sx={{ height: 20, fontSize: '0.7rem', fontWeight: 700 }} />
            <Typography variant="caption" color="textSecondary" noWrap>{details}</Typography>
          </Stack>
        </Box>
        {/* 연결 상태 점: 초록=연결됨, 빨강=실패, 회색=모름/UDP. 자동 확인을 켜 두면 1분마다 갱신된다. */}
        <Tooltip title={testing ? '연결 확인 중' : health.title}>
          <Box sx={{ width: 22, height: 26, display: 'grid', placeItems: 'center', flexShrink: 0 }}>
            {testing ? <CircularProgress size={11} /> : (
              <Box aria-label={health.title} sx={{
                width: 10, height: 10, borderRadius: '50%',
                bgcolor: health.hollow ? 'transparent' : health.color,
                border: health.hollow ? 1.5 : 0, borderColor: health.color,
              }} />
            )}
          </Box>
        </Tooltip>
        <Checkbox size="small" checked={selected} onChange={onSelect} sx={{ mt: -0.5, mr: -0.5 }}
                  slotProps={{ input: { 'aria-label': `${target.host}:${target.port} 선택` } }} />
      </Stack>

      <Stack direction="row" sx={{ alignItems: 'center', mt: 0.75, minHeight: 30 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {/* 평소엔 "어떤 작업이 쓰는지", 연결이 실패했을 때만 이유를 보여 준다(상태는 위의 점). */}
          {result && !result.ok && !udp ? (
            <Stack direction="row" spacing={0.5} title={result.message}
                   sx={{ alignItems: 'center', color: 'error.main', minWidth: 0 }}>
              <CancelIcon sx={{ fontSize: 14 }} />
              <Typography variant="caption" noWrap>{result.message}</Typography>
            </Stack>
          ) : (
            <Typography variant="caption" color={usedBy.length ? 'textPrimary' : 'textSecondary'} noWrap
                        title={usedBy.join(', ')} sx={{ display: 'block' }}>
              {usedBy.length ? `작업 ${usedBy.length}개에서 사용 · ${usedBy[0]}` : '사용하는 작업 없음'}
            </Typography>
          )}
        </Box>
        <Tooltip title="연결 테스트">
          <span>
            <IconButton size="small" aria-label="연결 테스트" disabled={testing} onClick={onTest}>
              <PowerSettingsNewIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title="수정">
          <IconButton size="small" aria-label="수정" onClick={onEdit}>
            <EditOutlinedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title={usedBy.length ? '작업이 쓰고 있어 삭제할 수 없습니다' : '삭제'}>
          <span>
            <IconButton size="small" color="error" aria-label="삭제" disabled={usedBy.length > 0} onClick={onDelete}>
              <DeleteOutlineIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
      </Stack>
    </Paper>
  );
}

function TargetDialog({ target, protocols, options, onClose, onSaved, onError }: {
  target: Partial<Target>;
  protocols: ProtocolInfo[];
  options: MetaOptions;
  onClose: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}) {
  const [form, setForm] = useState<Partial<Target>>(target);
  const [busy, setBusy] = useState(false);
  const set = <K extends keyof Target>(key: K, value: Target[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const isUdp = form.protocol === 'UDP';
  const isTls = form.protocol === 'TLS';
  const http = isHttp(form.protocol);
  const format = (form.httpFormat ?? 'LINES') as HttpFormat;
  const formatOption = options.httpFormats.find((o) => o.value === format);

  /** 형식을 바꾸면 그 수집기의 기본 경로·포트로 맞춘다. 손으로 바꾼 값(기본값과 다른 값)은 그대로 둔다. */
  const changeFormat = (next: HttpFormat) => {
    const prev = formatOption;
    const option = options.httpFormats.find((o) => o.value === next);
    setForm((p) => ({
      ...p,
      httpFormat: next,
      httpPath: !p.httpPath || p.httpPath === prev?.defaultPath ? option?.defaultPath ?? '/' : p.httpPath,
      port: !p.id && option && (!p.port || p.port === prev?.defaultPort) ? option.defaultPort : p.port,
    }));
  };

  const save = async () => {
    setBusy(true);
    try {
      const body = {
        protocol: form.protocol, host: form.host?.trim(), port: form.port,
        framing: form.framing,
        batchBytes: form.batchBytes, sendBufferBytes: form.sendBufferBytes,
        connectTimeoutMs: form.connectTimeoutMs,
        httpPath: form.httpPath, httpFormat: form.httpFormat, httpHeaders: form.httpHeaders,
      };
      if (form.id) await api.updateTarget(form.id, body as Partial<Target>);
      else await api.createTarget(body as Partial<Target>);
      onSaved();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm">
      <DialogTitle>{form.id ? '대상 수정' : '전송 대상 추가'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Stack direction="row" spacing={2}>
            <TextField
              select label="프로토콜" value={form.protocol ?? 'TCP'} sx={{ width: 150, flexShrink: 0 }}
              onChange={(e) => {
                const protocol = e.target.value;
                set('protocol', protocol);
                if (isHttp(protocol)) {
                  // HTTP 는 본문 형식이 포트·경로를 정한다. 묶음도 HTTP 에 맞게 키운다.
                  const option = options.httpFormats.find((o) => o.value === (form.httpFormat ?? 'LINES'));
                  setForm((p) => ({
                    ...p,
                    protocol,
                    httpFormat: p.httpFormat ?? 'LINES',
                    httpPath: p.httpPath || option?.defaultPath || '/',
                    port: !p.id && option ? option.defaultPort : p.port,
                    batchBytes: !p.batchBytes || p.batchBytes === 16384 ? HTTP_BATCH_BYTES : p.batchBytes,
                  }));
                }
                // 새 대상을 만들 때만 포트를 프로토콜 기본값으로 맞춘다. 수정 중엔 건드리지 않는다.
                else if (!form.id && DEFAULT_PORTS[protocol] !== undefined) {
                  set('port', DEFAULT_PORTS[protocol]);
                }
              }}
            >
              {protocols.map((p) => (
                <MenuItem key={p.protocol} value={p.protocol}>{p.protocol}</MenuItem>
              ))}
            </TextField>
            <TextField
              label="호스트" placeholder="10.0.0.10" autoFocus fullWidth
              value={form.host ?? ''}
              onChange={(e) => set('host', e.target.value)}
              slotProps={{ htmlInput: { style: { fontFamily: MONO } } }}
            />
            <TextField
              label="포트" type="number" sx={{ width: 120, flexShrink: 0 }}
              value={form.port ?? ''}
              onChange={(e) => set('port', Number(e.target.value))}
              slotProps={{ htmlInput: { style: { fontFamily: MONO } } }}
            />
          </Stack>

          <Typography variant="caption" color="textSecondary">
            {protocols.find((p) => p.protocol === form.protocol)?.description}
          </Typography>

          {http ? (
            <>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField select label="본문 형식" value={format} sx={{ flex: '1 1 260px' }}
                           helperText={formatOption ? `Content-Type: ${formatOption.contentType}` : ' '}
                           onChange={(e) => changeFormat(e.target.value as HttpFormat)}>
                  {options.httpFormats.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
                </TextField>
                <TextField label="경로" value={form.httpPath ?? '/'} sx={{ flex: '1 1 260px' }}
                           helperText={`${(form.protocol ?? 'HTTP').toLowerCase()}://${form.host?.trim() || '호스트'}:${form.port ?? ''}${form.httpPath ?? '/'}`}
                           onChange={(e) => set('httpPath', e.target.value)}
                           slotProps={{ htmlInput: { style: { fontFamily: MONO } }, formHelperText: { sx: { fontFamily: MONO } } }} />
              </Stack>
              <TextField label="추가 헤더 (선택)" multiline minRows={2} fullWidth
                         value={form.httpHeaders ?? ''} placeholder={HEADER_HINT[format]}
                         helperText={<>한 줄에 “이름: 값”(#으로 시작하면 무시) · 예:{' '}
                           <Box component="span" sx={{ fontFamily: MONO, whiteSpace: 'pre-line' }}>{HEADER_HINT[format].replace(/^예\) /, '')}</Box></>}
                         onChange={(e) => set('httpHeaders', e.target.value)}
                         slotProps={{ htmlInput: { style: { fontFamily: MONO, fontSize: '0.82rem' } } }} />
              <Alert severity="info">
                POST 로 보냅니다. 응답을 기다리는 동안 들어온 로그는 다음 요청에 묶이므로, 속도가 높을수록 요청당 건수가
                늘어납니다. 4xx·5xx 응답을 받은 요청의 로그는 <strong>버림</strong>으로 집계하고 응답 내용을 전송 콘솔에 보여 줍니다.
                {format !== 'LINES' && (
                  <> JSON 형식은 로그 한 건을 문자열로 감싸 보내므로(여러 줄 로그도 한 건), 카테고리 인코딩은
                    UTF-8 이나 Base64 여야 합니다.</>
                )}
              </Alert>
            </>
          ) : (
            <TextField select label="프레이밍" fullWidth value={form.framing ?? 'NEWLINE'}
                       helperText="레코드 구분 방식. 문자 인코딩은 로그 카테고리에서 정합니다."
                       onChange={(e) => set('framing', e.target.value as Target['framing'])}>
              {options.framings.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
            </TextField>
          )}
          {isUdp && (
            <Alert severity="info">
              UDP 는 1건이 1패킷입니다. 1400바이트를 넘는 로그는 단편화되어 유실될 수 있습니다.
            </Alert>
          )}
          {isTls && (
            <Alert severity="success" icon={<LockOutlinedIcon fontSize="small" />}>
              TLS 로 암호화해 전송합니다. 인증서 신뢰 여부는 검증하지 않으므로
              자체 서명 인증서를 쓰는 내부 테스트 서버에도 바로 연결됩니다.
            </Alert>
          )}

          <Accordion variant="outlined" disableGutters
                     sx={{ '&::before': { display: 'none' }, borderRadius: 1 }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Typography variant="body2" color="textSecondary">고급 설정</Typography>
            </AccordionSummary>
            <AccordionDetails>
              <Stack spacing={2}>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  {!isUdp && (
                    <TextField label={http ? '요청당 최대 본문 (B)' : '배치 크기 (B)'} type="number" fullWidth
                               helperText={http ? '이 크기가 차면 응답을 기다리지 않고 바로 보냅니다' : '클수록 syscall 감소'}
                               value={form.batchBytes ?? (http ? HTTP_BATCH_BYTES : 16384)}
                               onChange={(e) => set('batchBytes', Number(e.target.value))} />
                  )}
                  <TextField label="SO_SNDBUF (B)" type="number" fullWidth
                             value={form.sendBufferBytes ?? 4194304}
                             onChange={(e) => set('sendBufferBytes', Number(e.target.value))} />
                  <TextField label="연결 타임아웃 (ms)" type="number" fullWidth
                             value={form.connectTimeoutMs ?? 3000}
                             onChange={(e) => set('connectTimeoutMs', Number(e.target.value))} />
                </Stack>
                <Typography variant="caption" color="textSecondary">
                  {isUdp ? '소켓' : '커넥션'} 수는 전송 시 워커 수에 맞춰 자동으로 결정됩니다.
                </Typography>
              </Stack>
            </AccordionDetails>
          </Accordion>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>취소</Button>
        <Button variant="contained" disabled={busy || !form.host?.trim() || !form.port}
                startIcon={busy ? <CircularProgress size={15} color="inherit" /> : undefined}
                onClick={save}>
          저장
        </Button>
      </DialogActions>
    </Dialog>
  );
}
