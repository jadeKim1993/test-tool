import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Checkbox from '@mui/material/Checkbox';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Collapse from '@mui/material/Collapse';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';

import AddIcon from '@mui/icons-material/Add';
import UnfoldLessIcon from '@mui/icons-material/UnfoldLess';
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore';
import ViewAgendaOutlinedIcon from '@mui/icons-material/ViewAgendaOutlined';
import ViewListIcon from '@mui/icons-material/ViewList';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import HistoryIcon from '@mui/icons-material/History';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import StopIcon from '@mui/icons-material/Stop';
import SyncIcon from '@mui/icons-material/Sync';

import { api } from '../api/client';
import type { Category, Job, JobRun, JobStatus, LogTemplate, MetaOptions, Target } from '../api/types';
import { useMetrics } from '../App';
import {
  ErrorAlert, Loading, Metric, NOTICE_MS, StateChip, fmt, useConfirm, useToast, useTransient,
} from '../components/common';
import { GettingStarted, JobTable } from '../components/ConsoleParts';
import { useHotkey } from '../hooks/useHotkey';
import { EpsControl } from '../components/EpsControl';
import { JobRoute } from '../components/JobRoute';
import { TargetAddress, sharedSubnets } from '../components/TargetAddress';
import { PROTOCOL_COLOR, compareTargets, isHttp } from '../components/targets';
import { MONO } from '../theme';

/**
 * 전송 콘솔 — 이 도구의 중심 화면.
 * <p>작업 카드마다 시작/정지/속도조정이 붙어 있어 상단에 별도 실행 바를 두지 않는다.
 * 상단에는 전체 합계와 "새 작업" 만 남긴다.
 */
const VIEW_KEY = 'tester.consoleView';
/** 카드 폭(카드 오른쪽 끝을 끌어 조정). 격자는 이 폭을 최소로 해서 한 줄에 들어갈 만큼 놓는다. */
const CARD_WIDTH_KEY = 'tester.cardWidth';
const DEFAULT_CARD_WIDTH = 520;
const MIN_CARD_WIDTH = 380;
/** 카드 사이 간격(theme spacing 2). 한 줄에 몇 개 들어가는지 계산할 때 쓴다. */
const CARD_GAP = 16;

function loadCardWidth(): number {
  try {
    const value = Number(localStorage.getItem(CARD_WIDTH_KEY));
    return value >= MIN_CARD_WIDTH ? value : DEFAULT_CARD_WIDTH;
  } catch {
    return DEFAULT_CARD_WIDTH;
  }
}

/** 접어 둔 작업 카드 id 목록. 작업이 많을 때 지켜볼 것만 펼쳐 두도록 브라우저에 기억한다. */
const COLLAPSED_KEY = 'tester.collapsedJobs';

function loadCollapsed(): Set<number> {
  try {
    const raw = JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? '[]');
    return new Set(Array.isArray(raw) ? raw.filter((v) => typeof v === 'number') : []);
  } catch {
    return new Set();
  }
}

export default function SendConsole() {
  const { latest } = useMetrics();
  const { toast, element: toastElement } = useToast();
  const { confirm, element: confirmElement } = useConfirm();

  const [jobs, setJobs] = useState<Job[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [targets, setTargets] = useState<Target[]>([]);
  const [options, setOptions] = useState<MetaOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Partial<Job> | null>(null);
  // 카드(그래프·이력까지) / 목록(한 줄 요약). 작업이 많으면 목록이 낫다. 브라우저에 기억한다.
  const [view, setView] = useState<'card' | 'list'>(() => {
    try { return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'card'; } catch { return 'card'; }
  });
  const [stateFilter, setStateFilter] = useState<'all' | 'running' | 'stopped'>('all');
  const [sortBy, setSortBy] = useState<'name' | 'eps' | 'dropped'>('name');
  const [collapsed, setCollapsed] = useState<Set<number>>(loadCollapsed);
  const [cardWidth, setCardWidth] = useState(loadCardWidth);
  const [resizing, setResizing] = useState<{ width: number; columns: number } | null>(null);
  const gridRef = useRef<HTMLDivElement>(null);

  const saveCardWidth = (width: number) => {
    setCardWidth(width);
    try { localStorage.setItem(CARD_WIDTH_KEY, String(width)); } catch { /* 기억 못 해도 동작에는 지장 없다 */ }
  };

  /**
   * 카드 오른쪽 끝을 끌어 폭을 바꾼다. 모든 카드가 같은 폭을 쓴다(격자가 들쭉날쭉해지지 않게).
   * 격자는 남는 폭을 카드에 나눠 주므로, 끄는 동안 "한 줄에 몇 개"가 바뀌는 지점에서 카드가 다시 펴진다.
   */
  const startResize = (event: ReactPointerEvent<HTMLElement>) => {
    const handle = event.currentTarget;
    const card = handle.parentElement;
    if (!card) return;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startWidth = card.getBoundingClientRect().width;
    const gridWidth = gridRef.current?.clientWidth ?? window.innerWidth;
    const columnsFor = (w: number) => Math.max(1, Math.floor((gridWidth + CARD_GAP) / (w + CARD_GAP)));
    let width = cardWidth;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const onMove = (e: PointerEvent) => {
      width = Math.round(Math.min(gridWidth, Math.max(MIN_CARD_WIDTH, startWidth + e.clientX - startX)));
      setCardWidth(width);
      setResizing({ width, columns: columnsFor(width) });
    };
    const onUp = () => {
      handle.removeEventListener('pointermove', onMove);
      handle.removeEventListener('pointerup', onUp);
      handle.removeEventListener('pointercancel', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      setResizing(null);
      saveCardWidth(width);
    };
    handle.addEventListener('pointermove', onMove);
    handle.addEventListener('pointerup', onUp);
    handle.addEventListener('pointercancel', onUp);
  };

  const saveCollapsed = (next: Set<number>) => {
    setCollapsed(next);
    try { localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...next])); } catch { /* 기억 못 해도 동작에는 지장 없다 */ }
  };
  const toggleCollapsed = (id: number) => {
    const next = new Set(collapsed);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    saveCollapsed(next);
  };

  const reload = useCallback(async () => {
    try {
      const [jobList, categoryList, targetList] = await Promise.all([
        api.jobs(), api.categories(), api.targets(),
      ]);
      setJobs(jobList);
      setCategories(categoryList);
      // 전송 대상 화면과 같은 주소 순(IP 숫자순 → 포트 → 프로토콜). 서버는 호스트를 문자열로 정렬한다.
      setTargets([...targetList].sort(compareTargets));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
    api.options().then(setOptions).catch(() => undefined);
  }, [reload]);

  // 실행 상태는 SSE 로 들어오므로 목록의 running 플래그보다 그쪽을 우선한다.
  const runningIds = useMemo(
    () => new Set(Object.values(latest).filter((s) => s.state === 'RUNNING').map((s) => s.jobId)),
    [latest],
  );

  const running = Object.values(latest).filter((s) => s.state === 'RUNNING');
  const totalEps = running.reduce((sum, s) => sum + s.eps, 0);
  const totalMbps = running.reduce((sum, s) => sum + s.mbps, 0);
  const totalDropped = running.reduce((sum, s) => sum + s.dropped, 0);

  const canCreate = categories.length > 0 && targets.length > 0;

  const changeView = (next: 'card' | 'list') => {
    setView(next);
    try { localStorage.setItem(VIEW_KEY, next); } catch { /* 기억 못 해도 동작에는 지장 없다 */ }
  };

  const visibleJobs = useMemo(() => {
    const filtered = jobs.filter((job) => stateFilter === 'all'
      || (stateFilter === 'running') === runningIds.has(job.id));
    const eps = (job: Job) => (runningIds.has(job.id) ? latest[job.id]?.eps ?? 0 : -1);
    return [...filtered].sort((a, b) => {
      if (sortBy === 'eps') return eps(b) - eps(a) || a.name.localeCompare(b.name);
      if (sortBy === 'dropped') return (latest[b.id]?.dropped ?? 0) - (latest[a.id]?.dropped ?? 0) || a.name.localeCompare(b.name);
      return a.name.localeCompare(b.name);
    });
  }, [jobs, stateFilter, sortBy, runningIds, latest]);

  // N: 새 작업. 입력 중이거나 대화상자가 열려 있으면 무시한다(useHotkey).
  useHotkey('n', () => { if (canCreate) setEditing(newJobDraft()); });

  // 대상은 미리 고르지 않는다 — 대상이 여럿이면 맨 위 것이 그대로 저장되는 실수가 잦다. 하나뿐이면 그걸 쓴다.
  // 선택 방식은 가중치 랜덤: 템플릿 화면에서 정한 비율이 실제로 적용되는 방식이다(§16.17).
  const newJobDraft = (): Partial<Job> => ({
    categoryId: categories[0]?.id,
    targetId: targets.length === 1 ? targets[0].id : undefined,
    templateIds: [],
    eps: 10000,
    selection: 'WEIGHTED_RANDOM',
    overflowPolicy: 'DROP',
  });

  const start = async (job: Job, epsOverride?: number) => {
    const eps = epsOverride ?? job.eps;
    if (eps === 0) {
      const ok = await confirm({
        title: '무제한 모드로 시작할까요?',
        message: '속도 제한 없이 장비가 낼 수 있는 최대치로 전송합니다. 수신측이 감당하지 못하면 대량으로 버려집니다.',
        confirmLabel: '무제한으로 시작',
        danger: true,
      });
      if (!ok) return;
    } else if (eps > 100_000) {
      const ok = await confirm({
        title: `${fmt.int(eps)} EPS 로 시작할까요?`,
        message: '수신측이 감당할 수 있는 속도인지 확인하세요.',
        confirmLabel: '시작',
      });
      if (!ok) return;
    }
    try {
      await api.startJob(job.id, epsOverride !== undefined ? { eps: epsOverride } : {});
      toast.ok(`${job.name} 전송을 시작했습니다`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const stop = async (job: Job) => {
    try {
      await api.stopJob(job.id);
      toast.ok(`${job.name} 전송을 정지했습니다`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const remove = async (job: Job) => {
    const ok = await confirm({
      title: '전송 작업을 삭제할까요?',
      message: <><strong>{job.name}</strong> 작업과 실행 이력이 함께 삭제됩니다.</>,
      confirmLabel: '삭제',
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteJob(job.id);
      toast.ok('삭제했습니다');
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (loading) return <Loading />;

  return (
    <>
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction="row" spacing={4} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <Metric label="실행 중" value={running.length} unit={`/ ${jobs.length}개`} />
          <Metric label="합계 속도" value={fmt.int(totalEps)} unit="EPS" />
          <Metric label="합계 대역폭" value={totalMbps.toFixed(1)} unit="Mbps" />
          <Metric label="버림" value={fmt.int(totalDropped)} unit="건"
                  tone={totalDropped > 0 ? 'bad' : undefined} />
          <Box sx={{ flex: 1 }} />
          <Tooltip title="새 작업 (N)">
            <span>
              <Button variant="contained" size="medium" startIcon={<AddIcon />}
                      disabled={!canCreate} onClick={() => setEditing(newJobDraft())}>
                새 작업
              </Button>
            </span>
          </Tooltip>
        </Stack>
      </Paper>

      <ErrorAlert error={error} onClose={() => setError(null)} autoHideMs={NOTICE_MS} />

      {jobs.length === 0 && (
        <GettingStarted categories={categories} targets={targets} onCreate={() => setEditing(newJobDraft())} />
      )}

      {jobs.length > 0 && (
        <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1.5 }}>
          <ToggleButtonGroup exclusive value={stateFilter}
                             onChange={(_, v) => { if (v) setStateFilter(v); }}>
            <ToggleButton value="all">전체 {jobs.length}</ToggleButton>
            <ToggleButton value="running">전송 중 {jobs.filter((j) => runningIds.has(j.id)).length}</ToggleButton>
            <ToggleButton value="stopped">정지 {jobs.filter((j) => !runningIds.has(j.id)).length}</ToggleButton>
          </ToggleButtonGroup>
          <TextField select value={sortBy} sx={{ width: 150 }}
                     onChange={(e) => setSortBy(e.target.value as typeof sortBy)}>
            <MenuItem value="name">이름순</MenuItem>
            <MenuItem value="eps">속도 높은 순</MenuItem>
            <MenuItem value="dropped">버림 많은 순</MenuItem>
          </TextField>
          <Box sx={{ flex: 1 }} />
          {view === 'card' && visibleJobs.length > 0 && (() => {
            // 보이는 카드가 전부 접혀 있으면 "모두 펼치기", 하나라도 펼쳐져 있으면 "모두 접기".
            const allCollapsed = visibleJobs.every((j) => collapsed.has(j.id));
            return (
              <Button color="inherit" startIcon={allCollapsed ? <UnfoldMoreIcon /> : <UnfoldLessIcon />}
                      onClick={() => {
                        const next = new Set(collapsed);
                        for (const j of visibleJobs) {
                          if (allCollapsed) next.delete(j.id);
                          else next.add(j.id);
                        }
                        saveCollapsed(next);
                      }}>
                {allCollapsed ? '모두 펼치기' : '모두 접기'}
              </Button>
            );
          })()}
          <ToggleButtonGroup exclusive value={view} onChange={(_, v) => { if (v) changeView(v); }}>
            <ToggleButton value="card" aria-label="카드 보기" title="카드 보기 (그래프·이력)">
              <ViewAgendaOutlinedIcon fontSize="small" />
            </ToggleButton>
            <ToggleButton value="list" aria-label="목록 보기" title="목록 보기 (한 줄 요약)">
              <ViewListIcon fontSize="small" />
            </ToggleButton>
          </ToggleButtonGroup>
        </Stack>
      )}

      {jobs.length > 0 && visibleJobs.length === 0 && (
        <Paper variant="outlined">
          <Typography variant="body2" color="textSecondary" sx={{ py: 4, textAlign: 'center' }}>
            {stateFilter === 'running' ? '전송 중인 작업이 없습니다' : '정지된 작업이 없습니다'}
          </Typography>
        </Paper>
      )}

      {view === 'list' && visibleJobs.length > 0 && (
        <JobTable jobs={visibleJobs} targets={targets} latest={latest} runningIds={runningIds}
                  onStart={(job) => start(job)} onStop={stop} onEdit={(job) => setEditing(job)} onDelete={remove} />
      )}

      {/* 끄는 동안 지금 폭과 한 줄 개수를 보여 준다 */}
      {resizing && (
        <Chip color="primary" label={`카드 폭 ${resizing.width}px · 한 줄에 ${resizing.columns}개`}
              sx={{ position: 'fixed', top: 64, left: '50%', transform: 'translateX(-50%)', zIndex: 1300, boxShadow: 4 }} />
      )}

      <Box ref={gridRef} sx={{
        display: view === 'card' ? 'grid' : 'none',
        gap: 2,
        // 접은 카드가 옆 카드 높이에 맞춰 늘어나지 않도록 각자 내용 높이만큼만.
        alignItems: 'start',
        // 카드 폭은 사용자가 끌어서 정한다(cardWidth). 화면이 그보다 좁으면 한 줄에 하나.
        gridTemplateColumns: `repeat(auto-fill, minmax(min(100%, ${cardWidth}px), 1fr))`,
      }}>
        {view === 'card' && visibleJobs.map((job) => (
          <JobCard
            key={job.id}
            job={job}
            target={targets.find((t) => t.id === job.targetId)}
            options={options}
            status={latest[job.id]}
            running={runningIds.has(job.id)}
            collapsed={collapsed.has(job.id)}
            onToggleCollapse={() => toggleCollapsed(job.id)}
            onResizeStart={startResize}
            onResizeReset={() => saveCardWidth(DEFAULT_CARD_WIDTH)}
            onStart={(eps) => start(job, eps)}
            onStop={() => stop(job)}
            onEdit={() => setEditing(job)}
            onDelete={() => remove(job)}
            onRate={async (eps) => {
              try {
                await api.setRate(job.id, eps);
                toast.ok(`${job.name} 속도를 ${eps === 0 ? '무제한' : `${fmt.int(eps)} EPS`} 로 변경했습니다`);
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          />
        ))}
      </Box>

      {editing && options && (
        <JobDialog
          job={editing}
          categories={categories}
          targets={targets}
          options={options}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); toast.ok('저장했습니다'); reload(); }}
        />
      )}
      {toastElement}
      {confirmElement}
    </>
  );
}

function JobCard({
  job, target, options, status, running, collapsed, onToggleCollapse, onResizeStart, onResizeReset,
  onStart, onStop, onEdit, onDelete, onRate,
}: {
  job: Job;
  target?: Target;
  options: MetaOptions | null;
  status?: JobStatus;
  running: boolean;
  /** 접으면 머리 한 줄(상태·경로·현재 EPS·버튼)만 남긴다. */
  collapsed: boolean;
  onToggleCollapse: () => void;
  /** 카드 오른쪽 끝 손잡이를 잡았을 때(폭 조정 시작). 더블클릭은 기본 폭으로. */
  onResizeStart: (event: ReactPointerEvent<HTMLElement>) => void;
  onResizeReset: () => void;
  /** 카드에 입력된 "시작 속도"로 시작한다. 작업 설정을 고치러 들어갈 필요가 없다. */
  onStart: (eps: number) => void;
  onStop: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onRate: (eps: number) => void;
}) {
  const [showRuns, setShowRuns] = useState(false);
  const [runs, setRuns] = useState<JobRun[]>([]);
  const [rate, setRate] = useState(job.eps);
  // 멈춘 작업의 "마지막 실행" 요약. 실시간 상태는 페이지를 새로 열면 없으므로 실행 이력에서 한 건만 읽는다.
  const [lastRun, setLastRun] = useState<JobRun | null | undefined>(undefined);

  useEffect(() => {
    if (running) return;
    let alive = true;
    api.jobRuns(job.id, 1)
      .then((list) => { if (alive) setLastRun(list[0] ?? null); })
      .catch(() => { if (alive) setLastRun(null); });
    return () => { alive = false; };
  }, [job.id, running]);

  // 실행 중에는 서버가 알려주는 목표 속도를 따라간다. targetEps 가 실제로 바뀔 때만
  // 덮어써야 사용자가 입력하는 도중에 값이 되돌아가지 않는다.
  useEffect(() => {
    if (status) setRate(status.targetEps);
  }, [status?.targetEps]);

  // 멈춰 있을 때 작업 설정에서 EPS 를 바꾸면 "시작 속도" 입력도 따라가야 한다.
  useEffect(() => {
    if (!running) setRate(job.eps);
  }, [job.eps, running]);

  const toggleRuns = async () => {
    if (!showRuns) {
      try {
        setRuns(await api.jobRuns(job.id, 10));
      } catch {
        setRuns([]);
      }
    }
    setShowRuns(!showRuns);
  };

  // 목표 대비 10% 이상 어긋나면 색으로 알린다. 장비 한계인지 설정 문제인지 보는 출발점이다.
  const accuracy = status && status.targetEps > 0
    ? Math.abs(status.eps - status.targetEps) / status.targetEps : 0;

  const reconnecting = running
    && (status?.connection === 'RECONNECTING' || status?.connection === 'FAILED');

  // 아래 알림들은 "무슨 일이 생겼다"를 3초만 알리고 치운다. 예전엔 조건이 남아 있는 한(정지 후에도)
  // 계속 떠 있어 새로고침해야 지워졌다. 지속 상태는 상단 칩(재연결/실패)과 버림 수치가 계속 보여 준다.
  // 키에 실행 번호를 넣어, 같은 실행 안에서 숫자가 늘 때마다 다시 뜨며 깜빡이지 않게 한다.
  const runKey = status ? `${job.id}-${status.runId}` : null;
  const showReconnect = useTransient(reconnecting ? `reconnect-${runKey}` : null);
  const showDropped = useTransient(status && status.dropped > 0 && !reconnecting ? `dropped-${runKey}` : null);
  const showFailed = useTransient(status?.message && status.state === 'FAILED' ? `failed-${runKey}` : null);
  const showWarning = useTransient(status?.message && status.state === 'RUNNING' && !reconnecting
    ? `warning-${runKey}-${status.message}` : null);

  const accent = status?.state === 'FAILED' ? 'error.main'
    : reconnecting ? 'warning.main'
      : running ? 'success.main' : 'divider';

  return (
    <Card sx={{ borderLeft: 3, borderLeftColor: accent, display: 'flex', flexDirection: 'column', position: 'relative' }}>
      {/* 폭 조정 손잡이: 카드 오른쪽 끝. 마우스를 올리면 ↔ 커서와 선이 보인다. */}
      <Box onPointerDown={onResizeStart} onDoubleClick={onResizeReset}
           title="끌어서 카드 폭 조정 · 더블클릭하면 기본 폭"
           aria-label="카드 폭 조정"
           sx={{
             position: 'absolute', top: 0, right: 0, bottom: 0, width: 8, zIndex: 2, cursor: 'col-resize',
             touchAction: 'none',
             '&:hover, &:active': { bgcolor: 'primary.main', opacity: 0.35 },
           }} />
      <Box sx={{ px: 2, pt: 1.5, pb: collapsed ? 1.5 : 0 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
          {/* 작업 이름은 "카테고리 → 주소"로 자동으로 붙으므로 따로 쓰지 않고, 그 내용을 첫 줄에 바로 보여 준다. */}
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0, flex: 1 }} title={job.name}>
            <StateChip state={status?.state} connection={running ? status?.connection : undefined} />
            <JobRoute job={job} target={target} running={running} options={options} />
          </Stack>

          <Stack direction="row" spacing={0.5} sx={{ flexShrink: 0, alignItems: 'center' }}>
            {/* 접혀 있어도 "지금 얼마나 보내는지"는 보이게 */}
            {collapsed && running && status && (
              <Typography variant="body2" sx={{ fontFamily: MONO, fontWeight: 700, mr: 0.5, whiteSpace: 'nowrap' }}
                          title={`목표 ${status.targetEps === 0 ? '무제한' : fmt.int(status.targetEps)} EPS`}>
                {fmt.int(status.eps)}
                <Box component="span" sx={{ color: 'text.secondary', fontWeight: 400, ml: 0.5 }}>EPS</Box>
              </Typography>
            )}
            {running ? (
              <Tooltip title="정지">
                <IconButton size="small" color="error" onClick={onStop}><StopIcon /></IconButton>
              </Tooltip>
            ) : (
              <Tooltip title="시작">
                <IconButton size="small" color="success" onClick={() => onStart(rate)}>
                  <PlayArrowIcon />
                </IconButton>
              </Tooltip>
            )}
            <Tooltip title="설정">
              <IconButton size="small" onClick={onEdit}><SettingsOutlinedIcon fontSize="small" /></IconButton>
            </Tooltip>
            <Tooltip title="실행 이력">
              <IconButton size="small" color={showRuns ? 'primary' : 'default'} onClick={toggleRuns}>
                <HistoryIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={running ? '실행 중에는 삭제할 수 없습니다' : '삭제'}>
              <span>
                <IconButton size="small" color="error" aria-label="삭제"
                            disabled={running} onClick={onDelete}>
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title={collapsed ? '펼치기' : '접기'}>
              <IconButton size="small" aria-label={collapsed ? '펼치기' : '접기'} onClick={onToggleCollapse}>
                {collapsed ? <UnfoldMoreIcon fontSize="small" /> : <UnfoldLessIcon fontSize="small" />}
              </IconButton>
            </Tooltip>
          </Stack>
        </Stack>
      </Box>

      {/* 숫자·속도 조정·이력은 접을 수 있다. 작업이 많을 때 지켜볼 것만 펼쳐 둔다. */}
      <Collapse in={!collapsed}>
      <Box sx={{ px: 2, pb: 1 }}>
        {/* 일정 속도로 보내는 경우가 대부분이라 추이 그래프 대신 "지금 몇 EPS 인가"를 크게, 나머지는 한 줄로.
            멈춰 있으면 목표와 마지막 실행 결과만 흐리게 보여 준다. */}
        {!running ? (
          <Typography variant="body2" color="textSecondary" sx={{ mt: 1.25 }}>
            목표 <Box component="span" sx={{ fontFamily: MONO, fontWeight: 600, color: 'text.primary' }}>
              {job.eps === 0 ? '무제한' : `${fmt.int(job.eps)} EPS`}
            </Box>
            {lastRun ? (
              <> · 마지막 실행 {shortTime(lastRun.startedAt)} · 누적{' '}
                <Box component="span" sx={{ fontFamily: MONO }}>{fmt.int(lastRun.sentCount)}건</Box>
                {' · '}버림{' '}
                <Box component="span" sx={{ fontFamily: MONO, color: lastRun.droppedCount > 0 ? 'error.main' : undefined }}>
                  {fmt.int(lastRun.droppedCount)}
                </Box></>
            ) : lastRun === null ? ' · 아직 실행 전' : ''}
          </Typography>
        ) : (
        <Stack direction="row" useFlexGap spacing={2} sx={{ alignItems: 'baseline', flexWrap: 'wrap', mt: 1.25, rowGap: 0.5 }}>
          <Box title={accuracy > 0.1 ? '목표 대비 10% 이상 차이' : undefined} sx={{ whiteSpace: 'nowrap' }}>
            <Typography component="span" sx={{
              fontFamily: MONO, fontSize: '1.5rem', fontWeight: 700, lineHeight: 1.1,
              color: !running ? 'text.disabled' : accuracy > 0.1 ? 'warning.main' : 'text.primary',
            }}>
              {running && status ? fmt.int(status.eps) : '–'}
            </Typography>
            <Typography component="span" variant="body2" color="textSecondary" sx={{ ml: 0.5 }}>
              / {(status && running ? status.targetEps : job.eps) === 0
                ? '무제한' : fmt.int(status && running ? status.targetEps : job.eps)} EPS
            </Typography>
          </Box>
          <Stack direction="row" spacing={1.75} useFlexGap sx={{ flexWrap: 'wrap', color: 'text.secondary' }}>
            <Stat label="누적" value={status ? `${fmt.int(status.sent)}건` : '–'} />
            <Stat label="버림" value={status ? fmt.int(status.dropped) : '–'}
                  bad={Boolean(status && status.dropped > 0)} />
            <Stat label="경과" value={status ? fmt.duration(status.elapsedSec) : '–'} />
            {running && status && <Stat label="대역폭" value={`${status.mbps.toFixed(1)} Mbps`} />}
          </Stack>
        </Stack>
        )}

        <Stack spacing={1} sx={{ mt: showReconnect || showDropped || showFailed || showWarning ? 1.5 : 0 }}>
          {/* 연결이 끊겨도 작업은 멈추지 않는다. 지금 무슨 일이 일어나는지를 원인과 함께 알린다. */}
          {showReconnect && (
            <Alert severity="info" icon={<SyncIcon fontSize="small" />} sx={{ py: 0.25 }}>
              수신 서버에 연결할 수 없어 <strong>자동으로 다시 연결하는 중</strong>입니다(최대 3초 간격).
              연결되면 바로 이어서 보냅니다.
              {job.overflowPolicy === 'BLOCK'
                ? ' 그동안은 로그 생성을 멈추고 기다립니다.'
                : ' 그동안 만든 로그는 버립니다.'}
              {status?.message && (
                <Typography variant="caption" sx={{ display: 'block', fontFamily: MONO, opacity: 0.8 }}>
                  {status.message}
                </Typography>
              )}
            </Alert>
          )}
          {showDropped && status && (
            <Alert severity="warning" sx={{ py: 0.25 }}>
              지금까지 {fmt.int(status.dropped)}건을 버렸습니다 — 수신 서버가 끊겼었거나 속도를 따라오지 못한
              경우입니다. 계속 늘어나면 속도를 낮추거나 포화 정책을 <strong>대기</strong>로 바꾸세요.
            </Alert>
          )}
          {showFailed && status?.message && (
            <Alert severity="error" sx={{ py: 0.25 }}>{status.message}</Alert>
          )}
          {showWarning && status?.message && (
            <Alert severity="warning" sx={{ py: 0.25 }}>{status.message}</Alert>
          )}
        </Stack>
      </Box>

      <Box sx={{ px: 2, pb: 1.5, pt: 0.5, mt: 'auto' }}>
        <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <Typography variant="caption" color="textSecondary" sx={{ pt: 1.2, flexShrink: 0 }}>
            {running ? '속도 조정' : '시작 속도'}
          </Typography>
          <EpsControl value={rate} onChange={setRate} />
          {running ? (
            <Button variant="outlined" sx={{ mt: 0.25 }}
                    disabled={rate === status?.targetEps}
                    onClick={() => onRate(rate)}>
              적용
            </Button>
          ) : (
            <Button variant="contained" color="success" startIcon={<PlayArrowIcon />}
                    sx={{ mt: 0.25 }} onClick={() => onStart(rate)}>
              시작
            </Button>
          )}
        </Stack>
      </Box>

      <Collapse in={showRuns} unmountOnExit>
        <Divider />
        <Box sx={{ p: 1 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>시작</TableCell>
                <TableCell>상태</TableCell>
                <TableCell align="right">전송</TableCell>
                <TableCell align="right">평균</TableCell>
                <TableCell align="right">최고</TableCell>
                <TableCell align="right">버림</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {runs.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6}>
                    <Typography variant="caption" color="textSecondary">이력이 없습니다</Typography>
                  </TableCell>
                </TableRow>
              )}
              {runs.map((run) => (
                <TableRow key={run.id}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Typography variant="caption">{fmt.time(run.startedAt)}</Typography>
                  </TableCell>
                  <TableCell><StateChip state={run.status} /></TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO }}>{fmt.int(run.sentCount)}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO }}>{fmt.int(run.avgEps)}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO }}>{fmt.int(run.peakEps)}</TableCell>
                  <TableCell align="right"
                             sx={{ fontFamily: MONO, color: run.droppedCount > 0 ? 'error.main' : undefined }}>
                    {fmt.int(run.droppedCount)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      </Collapse>
      </Collapse>
    </Card>
  );
}

/** "10-26 19:35" — 카드 한 줄에 들어가도록 짧게. */
function shortTime(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 작업 카드의 작은 수치 한 개. "누적 3,687건" 처럼 이름과 값을 붙여 쓴다. */
function Stat({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  return (
    <Typography variant="body2" component="span" sx={{ whiteSpace: 'nowrap' }}>
      {label}{' '}
      <Box component="span" sx={{ fontFamily: MONO, fontWeight: 600, color: bad ? 'error.main' : 'text.primary' }}>
        {value}
      </Box>
    </Typography>
  );
}

/**
 * 작업 만들기/설정.
 * <p>"무엇을(카테고리·섞는 방식) → 어디로(대상) → 얼마나(속도·종료 조건)" 순서로 묻는다.
 * 보낼 로그 종류는 템플릿 화면에서 켜 둔 것 전부다(§16.24).
 */
function JobDialog({ job, categories, targets, options, onClose, onSaved }: {
  job: Partial<Job>;
  categories: Category[];
  targets: Target[];
  options: MetaOptions;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<Partial<Job>>(job);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [templates, setTemplates] = useState<LogTemplate[] | null>(null);

  const set = <K extends keyof Job>(key: K, value: Job[K]) => setForm((prev) => ({ ...prev, [key]: value }));

  const category = categories.find((c) => c.id === form.categoryId);
  const target = targets.find((t) => t.id === form.targetId) ?? null;
  const subnets = useMemo(() => sharedSubnets(targets), [targets]);

  useEffect(() => {
    if (!form.categoryId) return;
    let alive = true;
    setTemplates(null);
    api.templates(form.categoryId)
      .then((list) => { if (alive) setTemplates(list); })
      .catch(() => { if (alive) setTemplates([]); });
    return () => { alive = false; };
  }, [form.categoryId]);

  // 무엇을 보낼지는 템플릿 화면에서 정한다(켜 둔 종류 전부). 작업에서 따로 고르지 않는다.
  const chosen = (templates ?? []).filter((t) => t.enabled);
  // 예전에 일부 종류만 골라 둔 작업은 저장하면 "켜진 종류 전부"로 바뀐다. 미리 알린다.
  const hadSubset = Boolean(job.id && job.templateIds?.length);
  const weighted = (form.selection ?? 'WEIGHTED_RANDOM') === 'WEIGHTED_RANDOM';

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = {
        categoryId: form.categoryId,
        targetId: form.targetId,
        templateIds: [],
        eps: form.eps ?? 0,
        selection: form.selection ?? 'WEIGHTED_RANDOM',
        overflowPolicy: form.overflowPolicy,
        durationSec: form.durationSec ?? null,
        totalLimit: form.totalLimit ?? null,
        autostart: form.autostart ?? false,
      };
      if (form.id) await api.updateJob(form.id, body as Partial<Job>);
      else await api.createJob(body as Partial<Job>);
      onSaved();
    } catch (e) {
      // 대화상자가 페이지 오류 배너를 가리므로 오류는 대화상자 안에 띄운다.
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const canSave = Boolean(form.categoryId && form.targetId) && templates !== null && chosen.length > 0;

  return (
    <Dialog open onClose={onClose} maxWidth="md">
      <DialogTitle>{form.id ? '작업 설정' : '새 전송 작업'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={3}>
          {/* ---------- 무엇을 ---------- */}
          <Box>
            <SectionTitle step={1} title="보낼 로그" />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField select label="로그 카테고리" fullWidth value={form.categoryId ?? ''}
                         onChange={(e) => set('categoryId', Number(e.target.value))}>
                {categories.map((c) => (
                  <MenuItem key={c.id} value={c.id}>
                    {c.name}
                    <Box component="span" sx={{ ml: 1, color: 'text.secondary', fontSize: '0.8rem' }}>
                      {/* 전체 수가 아니라 실제로 보낼 수 있는(켜진) 종류 수 */}
                      사용 {c.enabledTemplateCount}종{c.encoding !== 'UTF_8' && ` · ${c.encoding === 'BASE64' ? 'Base64' : c.encoding}`}
                    </Box>
                  </MenuItem>
                ))}
              </TextField>
              <TextField select label="로그 종류 섞는 방식" fullWidth value={form.selection ?? 'WEIGHTED_RANDOM'}
                         helperText={weighted ? '템플릿의 가중치 비율대로 섞습니다' : '가중치와 무관하게 균등하게 보냅니다'}
                         onChange={(e) => set('selection', e.target.value as Job['selection'])}>
                {options.selections.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
              </TextField>
            </Stack>

            {templates === null ? (
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1.5 }}>
                <CircularProgress size={14} />
                <Typography variant="caption" color="textSecondary">로그 종류를 불러오는 중</Typography>
              </Stack>
            ) : chosen.length === 0 && (
              <Alert severity="warning" sx={{ py: 0.25, mt: 1.5 }}>
                <strong>{category?.name}</strong> 에 켜져 있는 로그 종류가 없습니다. 템플릿 화면에서 켜야 보낼 수 있습니다.
              </Alert>
            )}
            {hadSubset && (
              <Alert severity="info" sx={{ py: 0.25, mt: 1.5 }}>
                이 작업은 예전에 로그 종류를 {job.templateIds!.length}개만 골라 두었습니다. 저장하면 템플릿 화면에서
                켜 둔 종류 전부를 보냅니다.
              </Alert>
            )}

            {/* 인코딩은 대상이 아니라 카테고리에 붙어 있으므로, 어떤 바이트로 나갈지 여기서 알려 준다. */}
            {category && category.encoding !== 'UTF_8' && (
              <Alert severity="warning" sx={{ py: 0.25, mt: 1.5 }}>
                <strong>{category.name}</strong> 카테고리는{' '}
                {category.encoding === 'BASE64' ? 'Base64' : category.encoding} 로 전송됩니다
                (인코딩은 템플릿 화면의 카테고리 설정에서 바꿉니다).
              </Alert>
            )}
          </Box>

          {/* ---------- 어디로 ---------- */}
          <Box>
            <SectionTitle step={2} title="받을 곳" />
            <Autocomplete
              options={targets}
              value={target}
              onChange={(_, t) => set('targetId', t?.id as number)}
              getOptionLabel={(t) => `${t.host}:${t.port}${isHttp(t.protocol) ? t.httpPath : ''} ${t.protocol}`}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              noOptionsText="맞는 대상이 없습니다 — 전송 대상 화면에서 추가하세요"
              slotProps={{ paper: { sx: { maxHeight: 420 } } }}
              renderOption={(props, t) => {
                const { key, ...rest } = props as typeof props & { key: string };
                return (
                  <Box component="li" key={key} {...rest} sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <TargetAddress target={t} subnets={subnets} fontSize="0.95rem" />
                      {isHttp(t.protocol) && (
                        <Typography variant="caption" color="textSecondary" sx={{ fontFamily: MONO }}>
                          {t.httpPath}
                        </Typography>
                      )}
                    </Box>
                    <Chip label={t.protocol} variant="outlined" color={PROTOCOL_COLOR[t.protocol] ?? 'default'}
                          sx={{ height: 20, fontSize: '0.7rem', fontWeight: 700 }} />
                  </Box>
                );
              }}
              renderInput={(params) => (
                <TextField {...params} label="전송 대상"
                           placeholder="IP·포트·프로토콜로 찾기 (예: .110, :514, udp)"
                           slotProps={{ ...params.slotProps, htmlInput: { ...params.slotProps?.htmlInput, style: { fontFamily: MONO } } }} />
              )}
            />
          </Box>

          {/* ---------- 얼마나 ---------- */}
          <Box>
            <SectionTitle step={3} title="속도와 종료 조건" />
            <EpsControl value={form.eps ?? 0} onChange={(eps) => set('eps', eps)} />
            <Typography variant="caption" color="textSecondary" sx={{ mt: 0.75, mb: 2, display: 'block' }}>
              로그 종류가 몇 개든 <strong>초당 총 전송 건수</strong>입니다.
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField label="실행 시간 제한 (초)" type="number" fullWidth placeholder="무제한"
                         value={form.durationSec ?? ''}
                         onChange={(e) => set('durationSec', e.target.value ? Number(e.target.value) : null)} />
              <TextField label="총 건수 제한" type="number" fullWidth placeholder="무제한"
                         value={form.totalLimit ?? ''}
                         onChange={(e) => set('totalLimit', e.target.value ? Number(e.target.value) : null)} />
              <TextField select label="송신 버퍼 포화 시" fullWidth value={form.overflowPolicy ?? 'DROP'}
                         onChange={(e) => set('overflowPolicy', e.target.value as Job['overflowPolicy'])}>
                {options.overflowPolicies.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
              </TextField>
            </Stack>
            <FormControlLabel sx={{ mt: 1 }}
              control={<Checkbox size="small" checked={form.autostart ?? false}
                                 onChange={(e) => set('autostart', e.target.checked)} />}
              label={<Typography variant="body2">애플리케이션 재시작 시 자동으로 다시 시작</Typography>}
            />
          </Box>


          {error && <ErrorAlert error={error} onClose={() => setError(null)} autoHideMs={NOTICE_MS} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>취소</Button>
        <Button variant="contained" disabled={busy || !canSave}
                startIcon={busy ? <CircularProgress size={15} color="inherit" /> : undefined}
                onClick={save}>
          {form.id ? '저장' : '작업 만들기'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function SectionTitle({ step, title }: { step: number; title: string }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.25 }}>
      <Box sx={{
        width: 20, height: 20, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText',
        fontSize: '0.72rem', fontWeight: 700, display: 'grid', placeItems: 'center',
      }}>
        {step}
      </Box>
      <Typography variant="subtitle2">{title}</Typography>
    </Stack>
  );
}
