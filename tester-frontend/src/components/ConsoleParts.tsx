import { Link } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';

import AddIcon from '@mui/icons-material/Add';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import StopIcon from '@mui/icons-material/Stop';

import type { Category, Job, JobStatus, Target } from '../api/types';
import { MONO } from '../theme';
import { StateChip, fmt } from './common';
import { TargetAddress } from './TargetAddress';
import { PROTOCOL_COLOR } from './targets';

/**
 * 처음 쓰는 사람 안내. 작업이 하나도 없을 때 "템플릿 → 대상 → 작업" 세 단계를 보여 주고,
 * 이미 된 단계는 체크로 표시한다. 빈 화면에서 무엇부터 해야 하는지 헤매지 않게 한다.
 */
export function GettingStarted({ categories, targets, onCreate }: {
  categories: Category[];
  targets: Target[];
  onCreate: () => void;
}) {
  const hasTemplates = categories.some((c) => c.enabledTemplateCount > 0);
  const hasTargets = targets.length > 0;
  const steps = [
    {
      done: hasTemplates,
      title: '보낼 로그 준비',
      hint: hasTemplates
        ? `카테고리 ${categories.length}개에 기본 템플릿이 들어 있습니다.`
        : '템플릿 화면에서 로그 종류를 켜거나 새로 만드세요.',
      action: <Button component={Link} to="/templates" size="small">템플릿 화면</Button>,
    },
    {
      done: hasTargets,
      title: '받을 서버 등록',
      hint: hasTargets
        ? `전송 대상 ${targets.length}개가 등록돼 있습니다.`
        : '로그를 받을 수집 서버의 프로토콜·IP·포트를 등록하세요(TCP·UDP·TLS·HTTP).',
      action: <Button component={Link} to="/targets" size="small">전송 대상 화면</Button>,
    },
    {
      done: false,
      title: '전송 작업 만들고 시작',
      hint: '로그 카테고리와 받을 서버를 고르고 속도(EPS)를 정한 뒤 ▶ 시작을 누르면 됩니다.',
      action: (
        <Button size="small" variant="contained" startIcon={<AddIcon />}
                disabled={!hasTemplates || !hasTargets} onClick={onCreate}>
          작업 만들기 (N)
        </Button>
      ),
    },
  ];
  const current = steps.findIndex((s) => !s.done);

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6">시작하기</Typography>
      <Typography variant="body2" color="textSecondary" sx={{ mb: 2 }}>
        세 단계면 첫 로그를 보낼 수 있습니다.
      </Typography>
      <Stack spacing={1.5}>
        {steps.map((step, i) => (
          <Stack key={step.title} direction="row" spacing={1.5} sx={{
            alignItems: 'flex-start', p: 1.5, borderRadius: 1, border: 1,
            borderColor: i === current ? 'primary.main' : 'divider',
            bgcolor: i === current ? 'action.hover' : undefined,
          }}>
            {step.done
              ? <CheckCircleIcon color="success" sx={{ mt: 0.25 }} />
              : <RadioButtonUncheckedIcon color={i === current ? 'primary' : 'disabled'} sx={{ mt: 0.25 }} />}
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle2" sx={{ color: step.done ? 'text.secondary' : 'text.primary' }}>
                {i + 1}. {step.title}
              </Typography>
              <Typography variant="body2" color="textSecondary">{step.hint}</Typography>
            </Box>
            <Box sx={{ flexShrink: 0 }}>{step.action}</Box>
          </Stack>
        ))}
      </Stack>
    </Paper>
  );
}

/**
 * 목록 보기. 작업이 많을 때 카드 대신 한 줄씩 촘촘히 본다(그래프·이력은 카드 보기에서).
 * 시작/정지·설정·삭제는 카드와 같은 동작을 그대로 쓴다.
 */
export function JobTable({ jobs, targets, latest, runningIds, onStart, onStop, onEdit, onDelete }: {
  jobs: Job[];
  targets: Target[];
  latest: Record<number, JobStatus>;
  runningIds: Set<number>;
  onStart: (job: Job) => void;
  onStop: (job: Job) => void;
  onEdit: (job: Job) => void;
  onDelete: (job: Job) => void;
}) {
  return (
    <Paper variant="outlined">
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={{ width: 110 }}>상태</TableCell>
              <TableCell>작업</TableCell>
              <TableCell>받는 곳</TableCell>
              <TableCell align="right">실측 / 목표 EPS</TableCell>
              <TableCell align="right">누적 전송</TableCell>
              <TableCell align="right">버림</TableCell>
              <TableCell align="right">경과</TableCell>
              <TableCell align="right" sx={{ width: 120 }} />
            </TableRow>
          </TableHead>
          <TableBody>
            {jobs.map((job) => {
              const status = latest[job.id];
              const running = runningIds.has(job.id);
              const target = targets.find((t) => t.id === job.targetId);
              return (
                <TableRow key={job.id} hover>
                  <TableCell><StateChip state={status?.state} connection={running ? status?.connection : undefined} /></TableCell>
                  <TableCell>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600, maxWidth: 260 }} title={job.name}>
                      {job.name}
                    </Typography>
                    <Typography variant="caption" color="textSecondary">
                      {job.categoryName} · 사용 중 {job.templateCount}종
                    </Typography>
                  </TableCell>
                  <TableCell>
                    {target ? (
                      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
                        <Chip label={target.protocol} variant="outlined" color={PROTOCOL_COLOR[target.protocol] ?? 'default'}
                              sx={{ height: 20, fontSize: '0.7rem', fontWeight: 700 }} />
                        <TargetAddress target={target} fontSize="0.85rem" />
                      </Stack>
                    ) : <Typography variant="body2" sx={{ fontFamily: MONO }}>{job.endpoint}</Typography>}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO, whiteSpace: 'nowrap' }}>
                    {running && status ? fmt.int(status.eps) : '–'}
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      {' / '}{(status?.targetEps ?? job.eps) === 0 ? '무제한' : fmt.int(status?.targetEps ?? job.eps)}
                    </Box>
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO }}>{status ? fmt.int(status.sent) : '–'}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: MONO, color: status && status.dropped > 0 ? 'error.main' : undefined }}>
                    {status ? fmt.int(status.dropped) : '–'}
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{status ? fmt.duration(status.elapsedSec) : '–'}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {running ? (
                      <Tooltip title="정지">
                        <IconButton size="small" color="error" aria-label="정지" onClick={() => onStop(job)}>
                          <StopIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    ) : (
                      <Tooltip title="시작">
                        <IconButton size="small" color="success" aria-label="시작" onClick={() => onStart(job)}>
                          <PlayArrowIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    )}
                    <Tooltip title="설정">
                      <IconButton size="small" aria-label="설정" onClick={() => onEdit(job)}>
                        <SettingsOutlinedIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={running ? '실행 중에는 삭제할 수 없습니다' : '삭제'}>
                      <span>
                        <IconButton size="small" color="error" aria-label="삭제" disabled={running} onClick={() => onDelete(job)}>
                          <DeleteOutlineIcon fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Paper>
  );
}
