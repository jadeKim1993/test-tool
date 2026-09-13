import { useCallback, useEffect, useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Checkbox from '@mui/material/Checkbox';
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
import Typography from '@mui/material/Typography';

import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import HistoryIcon from '@mui/icons-material/History';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import SendOutlinedIcon from '@mui/icons-material/SendOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import StopIcon from '@mui/icons-material/Stop';
import AccountTreeOutlinedIcon from '@mui/icons-material/AccountTreeOutlined';

import { api } from '../api/client';
import type { Category, Job, JobRun, JobStatus, MetaOptions, Target } from '../api/types';
import { useMetrics } from '../App';
import { EmptyState, ErrorAlert, Loading, Metric, StateChip, fmt, useConfirm, useToast } from '../components/common';
import { EpsChart } from '../components/EpsChart';
import { EpsControl } from '../components/EpsControl';
import { MONO } from '../theme';

/**
 * 전송 콘솔 — 이 도구의 중심 화면.
 * <p>작업 카드마다 시작/정지/속도조정이 붙어 있어 상단에 별도 실행 바를 두지 않는다.
 * 상단에는 전체 합계와 "새 작업" 만 남긴다.
 */
export default function SendConsole() {
  const { latest, series } = useMetrics();
  const { toast, element: toastElement } = useToast();
  const { confirm, element: confirmElement } = useConfirm();

  const [jobs, setJobs] = useState<Job[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [targets, setTargets] = useState<Target[]>([]);
  const [options, setOptions] = useState<MetaOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Partial<Job> | null>(null);

  const reload = useCallback(async () => {
    try {
      const [jobList, categoryList, targetList] = await Promise.all([
        api.jobs(), api.categories(), api.targets(),
      ]);
      setJobs(jobList);
      setCategories(categoryList);
      setTargets(targetList);
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

  const newJobDraft = (): Partial<Job> => ({
    categoryId: categories[0]?.id,
    targetId: targets[0]?.id,
    eps: 10000,
    selection: 'ROUND_ROBIN',
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
          <Button variant="contained" size="medium" startIcon={<AddIcon />}
                  disabled={!canCreate} onClick={() => setEditing(newJobDraft())}>
            새 작업
          </Button>
        </Stack>
      </Paper>

      <ErrorAlert error={error} onClose={() => setError(null)} />

      {!canCreate && (
        <Paper variant="outlined">
          <EmptyState
            icon={<AccountTreeOutlinedIcon />}
            title="먼저 카테고리와 전송 대상이 필요합니다"
            hint="템플릿 화면에서 로그를 만들고, 전송 대상 화면에서 보낼 서버를 등록하세요."
          />
        </Paper>
      )}

      {jobs.length === 0 && canCreate && (
        <Paper variant="outlined">
          <EmptyState
            icon={<SendOutlinedIcon />}
            title="전송 작업이 없습니다"
            hint="카테고리와 대상을 묶어 작업을 만들면 바로 보낼 수 있습니다."
            action={<Button variant="contained" startIcon={<AddIcon />}
                            onClick={() => setEditing(newJobDraft())}>작업 만들기</Button>}
          />
        </Paper>
      )}

      <Box sx={{
        display: 'grid',
        gap: 2,
        // 화면 폭이 아니라 실제로 쓸 수 있는 폭에 맞춰 접힌다. 카드가 좁아지면 한 줄로 떨어진다.
        gridTemplateColumns: 'repeat(auto-fill, minmax(min(100%, 560px), 1fr))',
      }}>
        {jobs.map((job) => (
          <JobCard
            key={job.id}
            job={job}
            status={latest[job.id]}
            series={series[job.id] ?? []}
            running={runningIds.has(job.id)}
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
          onError={setError}
        />
      )}
      {toastElement}
      {confirmElement}
    </>
  );
}

function JobCard({ job, status, series, running, onStart, onStop, onEdit, onDelete, onRate }: {
  job: Job;
  status?: JobStatus;
  series: JobStatus[];
  running: boolean;
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

  const accent = status?.state === 'FAILED' ? 'error.main'
    : running ? 'success.main' : 'divider';

  return (
    <Card sx={{ borderLeft: 3, borderLeftColor: accent, display: 'flex', flexDirection: 'column' }}>
      <Box sx={{ p: 2, pb: 1.5 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="subtitle1" noWrap sx={{ fontWeight: 600 }}>{job.name}</Typography>
              <StateChip state={status?.state} connection={status?.connection} />
            </Stack>
            <Typography variant="caption" color="textSecondary" noWrap sx={{ display: 'block' }}>
              {job.categoryName} · 로그 {job.templateCount}종 → {job.endpoint}
              {status && ` · 워커 ${status.workers}개`}
            </Typography>
          </Box>

          <Stack direction="row" spacing={0.5} sx={{ flexShrink: 0 }}>
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
          </Stack>
        </Stack>

        <Stack direction="row" spacing={3} useFlexGap sx={{ flexWrap: 'wrap', mt: 2 }}>
          <Metric label="실측 EPS" value={status ? fmt.int(status.eps) : '–'}
                  tone={accuracy > 0.1 ? 'warn' : undefined}
                  title={accuracy > 0.1 ? '목표 대비 10% 이상 차이' : undefined} />
          <Metric label="목표"
                  value={status
                    ? (status.targetEps === 0 ? '무제한' : fmt.int(status.targetEps))
                    : fmt.int(job.eps)} />
          <Metric label="대역폭" value={status ? status.mbps.toFixed(1) : '–'} unit="Mbps" />
          <Metric label="누적 전송" value={status ? fmt.int(status.sent) : '–'} unit="건" />
          <Metric label="버림" value={status ? fmt.int(status.dropped) : '–'}
                  tone={status && status.dropped > 0 ? 'bad' : undefined} />
          <Metric label="경과" value={status ? fmt.duration(status.elapsedSec) : '–'} />
        </Stack>

        <Stack spacing={1} sx={{ mt: status?.dropped || status?.message ? 1.5 : 0 }}>
          {status && status.dropped > 0 && (
            <Alert severity="warning" sx={{ py: 0.25 }}>
              수신측이 속도를 따라오지 못해 {fmt.int(status.dropped)}건을 버렸습니다.
              속도를 낮추거나 포화 정책을 <strong>대기</strong>로 바꾸세요.
            </Alert>
          )}
          {status?.message && status.state === 'FAILED' && (
            <Alert severity="error" sx={{ py: 0.25 }}>{status.message}</Alert>
          )}
          {status?.message && status.state === 'RUNNING' && (
            <Alert severity="warning" sx={{ py: 0.25 }}>{status.message}</Alert>
          )}
        </Stack>
      </Box>

      {series.length > 1 && (
        <Box sx={{ px: 1 }}>
          <EpsChart series={series} />
        </Box>
      )}

      <Box sx={{ p: 2, pt: series.length > 1 ? 0 : 1, mt: 'auto' }}>
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
    </Card>
  );
}

function JobDialog({ job, categories, targets, options, onClose, onSaved, onError }: {
  job: Partial<Job>;
  categories: Category[];
  targets: Target[];
  options: MetaOptions;
  onClose: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}) {
  const [form, setForm] = useState<Partial<Job>>(job);
  const [busy, setBusy] = useState(false);

  const set = <K extends keyof Job>(key: K, value: Job[K]) => setForm((prev) => ({ ...prev, [key]: value }));

  const category = categories.find((c) => c.id === form.categoryId);
  const target = targets.find((t) => t.id === form.targetId);
  // 이름을 입력받지 않으므로, 서버가 붙일 이름을 미리 보여준다.
  const autoName = category && target ? `${category.name} → ${target.host}:${target.port}` : '';

  const save = async () => {
    setBusy(true);
    try {
      const body = {
        categoryId: form.categoryId,
        targetId: form.targetId,
        templateIds: form.templateIds ?? [],
        eps: form.eps ?? 0,
        selection: form.selection,
        overflowPolicy: form.overflowPolicy,
        durationSec: form.durationSec ?? null,
        totalLimit: form.totalLimit ?? null,
        autostart: form.autostart ?? false,
      };
      if (form.id) await api.updateJob(form.id, body as Partial<Job>);
      else await api.createJob(body as Partial<Job>);
      onSaved();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} maxWidth="md">
      <DialogTitle>{form.id ? '작업 설정' : '새 전송 작업'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField select label="로그 카테고리" fullWidth value={form.categoryId ?? ''}
                       onChange={(e) => set('categoryId', Number(e.target.value))}>
              {categories.map((c) => (
                <MenuItem key={c.id} value={c.id}>{c.name} ({c.templateCount}종)</MenuItem>
              ))}
            </TextField>
            <TextField select label="전송 대상" fullWidth value={form.targetId ?? ''}
                       onChange={(e) => set('targetId', Number(e.target.value))}>
              {targets.map((t) => (
                <MenuItem key={t.id} value={t.id}>{t.protocol} {t.host}:{t.port}</MenuItem>
              ))}
            </TextField>
          </Stack>

          <Typography variant="caption" color="textSecondary">
            작업 이름: <Box component="span" sx={{ fontFamily: MONO }}>{form.name || autoName}</Box>
            {!form.name && ' (자동)'}
          </Typography>

          {/* 인코딩은 대상이 아니라 카테고리에 붙어 있으므로, 어떤 바이트로 나갈지 여기서 알려 준다. */}
          {category && category.encoding !== 'UTF_8' && (
            <Alert severity="warning" sx={{ py: 0.25 }}>
              <strong>{category.name}</strong> 카테고리는{' '}
              {category.encoding === 'BASE64' ? 'Base64' : category.encoding} 로 전송됩니다
              (인코딩은 템플릿 화면의 카테고리 설정에서 바꿉니다).
            </Alert>
          )}

          <Box>
            <Typography variant="body2" sx={{ mb: 1 }}>전송 속도</Typography>
            <EpsControl value={form.eps ?? 0} onChange={(eps) => set('eps', eps)} />
            <Typography variant="caption" color="textSecondary" sx={{ mt: 0.75, display: 'block' }}>
              로그 종류가 몇 개든 <strong>초당 총 전송 건수</strong>입니다.
            </Typography>
          </Box>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField select label="로그 종류 선택 방식" fullWidth value={form.selection ?? 'ROUND_ROBIN'}
                       onChange={(e) => set('selection', e.target.value as Job['selection'])}>
              {options.selections.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
            </TextField>
            <TextField select label="송신 버퍼 포화 시" fullWidth value={form.overflowPolicy ?? 'DROP'}
                       onChange={(e) => set('overflowPolicy', e.target.value as Job['overflowPolicy'])}>
              {options.overflowPolicies.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
            </TextField>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField label="실행 시간 제한 (초)" type="number" fullWidth placeholder="무제한"
                       value={form.durationSec ?? ''}
                       onChange={(e) => set('durationSec', e.target.value ? Number(e.target.value) : null)} />
            <TextField label="총 건수 제한" type="number" fullWidth placeholder="무제한"
                       value={form.totalLimit ?? ''}
                       onChange={(e) => set('totalLimit', e.target.value ? Number(e.target.value) : null)} />
          </Stack>

          <FormControlLabel
            control={<Checkbox size="small" checked={form.autostart ?? false}
                               onChange={(e) => set('autostart', e.target.checked)} />}
            label={<Typography variant="body2">애플리케이션 재시작 시 자동으로 다시 시작</Typography>}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>취소</Button>
        <Button variant="contained" disabled={busy || !form.categoryId || !form.targetId}
                startIcon={busy ? <CircularProgress size={15} color="inherit" /> : undefined}
                onClick={save}>
          저장
        </Button>
      </DialogActions>
    </Dialog>
  );
}
