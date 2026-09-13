import { useEffect, useState } from 'react';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';

import AddIcon from '@mui/icons-material/Add';
import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import PowerSettingsNewIcon from '@mui/icons-material/PowerSettingsNew';

import { api } from '../api/client';
import type { MetaOptions, ProtocolInfo, Target, TestResult } from '../api/types';
import { EmptyState, ErrorAlert, Loading, useConfirm, useToast } from '../components/common';
import { MONO } from '../theme';

/** 프로토콜별 기본 포트. 새 대상을 추가하거나 프로토콜을 바꿀 때 채워 넣는다. */
const DEFAULT_PORTS: Record<string, number> = { TCP: 7720, UDP: 514, TLS: 7721 };

const PROTOCOL_COLOR: Record<string, 'primary' | 'info' | 'success' | 'default'> = {
  TCP: 'primary', UDP: 'info', TLS: 'success',
};

/**
 * 전송 대상(수집 서버) 관리.
 * <p>대상은 <b>프로토콜 + 호스트 + 포트</b>로 구분한다. 이름이나 커넥션 수는 받지 않는다
 * (이름은 주소에서 파생되고, 커넥션 수는 워커 수에 맞춰 엔진이 잡는다).
 */
export default function Targets() {
  const { toast, element: toastElement } = useToast();
  const { confirm, element: confirmElement } = useConfirm();

  const [targets, setTargets] = useState<Target[]>([]);
  const [protocols, setProtocols] = useState<ProtocolInfo[]>([]);
  const [options, setOptions] = useState<MetaOptions | null>(null);
  const [editing, setEditing] = useState<Partial<Target> | null>(null);
  const [results, setResults] = useState<Record<number, TestResult>>({});
  const [testing, setTesting] = useState<number | null>(null);
  const [testingAll, setTestingAll] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = async () => {
    try {
      setTargets(await api.targets());
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

  const test = async (target: Target) => {
    setTesting(target.id);
    try {
      const result = await api.testTarget(target.id);
      setResults((prev) => ({ ...prev, [target.id]: result }));
      return result;
    } catch (e) {
      setError((e as Error).message);
      return null;
    } finally {
      setTesting(null);
    }
  };

  /** 등록된 대상을 위에서부터 차례로 찔러 본다. 수집 서버가 여러 대일 때 한 번에 확인한다. */
  const testAll = async () => {
    setTestingAll(true);
    let ok = 0;
    for (const target of targets) {
      const result = await test(target);
      if (result?.ok) ok += 1;
    }
    setTestingAll(false);
    toast.ok(`${targets.length}개 중 ${ok}개 연결 성공`);
  };

  const remove = async (target: Target) => {
    const ok = await confirm({
      title: '전송 대상을 삭제할까요?',
      message: <>
        <Box component="span" sx={{ fontFamily: MONO }}>
          {target.protocol} {target.host}:{target.port}
        </Box>
        {' '}대상이 목록에서 사라집니다. 이 대상을 쓰는 전송 작업이 있으면 삭제되지 않습니다.
      </>,
      confirmLabel: '삭제',
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteTarget(target.id);
      toast.ok('삭제했습니다');
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const openNew = () => {
    const protocol = protocols[0]?.protocol ?? 'TCP';
    setEditing({
      protocol, host: '', port: DEFAULT_PORTS[protocol] ?? 7720, framing: 'NEWLINE',
    });
  };

  if (loading) return <Loading />;

  return (
    <>
      <Stack direction="row"
             spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography variant="h6">전송 대상</Typography>
          <Typography variant="body2" color="textSecondary">
            로그를 받을 수집 서버. 프로토콜·호스트·포트 조합으로 구분합니다.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          {targets.length > 0 && (
            <Button variant="outlined" color="inherit" disabled={testingAll}
                    startIcon={testingAll
                      ? <CircularProgress size={15} color="inherit" />
                      : <PowerSettingsNewIcon />}
                    onClick={testAll}>
              전체 연결 테스트
            </Button>
          )}
          <Button variant="contained" startIcon={<AddIcon />} onClick={openNew}>
            대상 추가
          </Button>
        </Stack>
      </Stack>

      <ErrorAlert error={error} onClose={() => setError(null)} />

      {targets.length === 0 ? (
        <Paper variant="outlined">
          <EmptyState
            icon={<HubOutlinedIcon />}
            title="등록된 전송 대상이 없습니다"
            hint="Fluent-bit, Logstash 등 로그를 받을 서버의 주소를 등록하세요."
            action={<Button variant="contained" startIcon={<AddIcon />} onClick={openNew}>대상 추가</Button>}
          />
        </Paper>
      ) : (
        <Paper variant="outlined">
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>프로토콜</TableCell>
                  <TableCell>호스트</TableCell>
                  <TableCell align="right">포트</TableCell>
                  <TableCell>프레이밍</TableCell>
                  <TableCell align="right">배치</TableCell>
                  <TableCell>연결 테스트</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {targets.map((target) => {
                  const result = results[target.id];
                  return (
                    <TableRow key={target.id} hover>
                      <TableCell>
                        <Chip label={target.protocol} variant="outlined"
                              color={PROTOCOL_COLOR[target.protocol] ?? 'default'} />
                      </TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontWeight: 600 }}>{target.host}</TableCell>
                      <TableCell align="right" sx={{ fontFamily: MONO }}>{target.port}</TableCell>
                      <TableCell>
                        <Typography variant="caption" color="textSecondary">{target.framing}</Typography>
                      </TableCell>
                      <TableCell align="right" sx={{ fontFamily: MONO, color: 'text.secondary' }}>
                        {target.protocol === 'UDP' ? '–' : `${(target.batchBytes / 1024).toFixed(0)}KB`}
                      </TableCell>
                      <TableCell>
                        {testing === target.id ? (
                          <CircularProgress size={14} />
                        ) : result ? (
                          <Tooltip title={result.message}>
                            <Stack direction="row" spacing={0.5}
                                   sx={{ alignItems: 'center', color: result.ok ? 'success.main' : 'error.main' }}>
                              {result.ok
                                ? <CheckCircleIcon sx={{ fontSize: 15 }} />
                                : <CancelIcon sx={{ fontSize: 15 }} />}
                              <Typography variant="caption" sx={{ fontFamily: MONO }}>
                                {result.ok ? `${result.latencyMs}ms` : '실패'}
                              </Typography>
                            </Stack>
                          </Tooltip>
                        ) : (
                          <Typography variant="caption" color="textSecondary">–</Typography>
                        )}
                      </TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                          <Tooltip title="연결 테스트">
                            <span>
                              <IconButton size="small" aria-label="연결 테스트"
                                          disabled={testing === target.id}
                                          onClick={() => test(target)}>
                                <PowerSettingsNewIcon fontSize="small" />
                              </IconButton>
                            </span>
                          </Tooltip>
                          <Tooltip title="수정">
                            <IconButton size="small" onClick={() => setEditing(target)}>
                              <EditOutlinedIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title="삭제">
                            <IconButton size="small" color="error" onClick={() => remove(target)}>
                              <DeleteOutlineIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        </Stack>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>

          {Object.values(results).some((r) => !r.ok) && (
            <Box sx={{ p: 1.5, borderTop: 1, borderColor: 'divider' }}>
              {Object.entries(results).filter(([, r]) => !r.ok).map(([id, r]) => (
                <Typography key={id} variant="caption" color="error" sx={{ display: 'block' }}>
                  {r.message}
                </Typography>
              ))}
            </Box>
          )}
        </Paper>
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

  const save = async () => {
    setBusy(true);
    try {
      const body = {
        protocol: form.protocol, host: form.host?.trim(), port: form.port,
        framing: form.framing,
        batchBytes: form.batchBytes, sendBufferBytes: form.sendBufferBytes,
        connectTimeoutMs: form.connectTimeoutMs,
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
              select label="프로토콜" value={form.protocol ?? 'TCP'} sx={{ width: 130 }}
              onChange={(e) => {
                const protocol = e.target.value;
                set('protocol', protocol);
                // 새 대상을 만들 때만 포트를 프로토콜 기본값으로 맞춘다. 수정 중엔 건드리지 않는다.
                if (!form.id && DEFAULT_PORTS[protocol] !== undefined) {
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
              label="포트" type="number" sx={{ width: 132 }}
              value={form.port ?? ''}
              onChange={(e) => set('port', Number(e.target.value))}
              slotProps={{ htmlInput: { style: { fontFamily: MONO } } }}
            />
          </Stack>

          <Typography variant="caption" color="textSecondary">
            {protocols.find((p) => p.protocol === form.protocol)?.description}
          </Typography>

          <TextField select label="프레이밍" fullWidth value={form.framing ?? 'NEWLINE'}
                     helperText="레코드 구분 방식. 문자 인코딩은 로그 카테고리에서 정합니다."
                     onChange={(e) => set('framing', e.target.value as Target['framing'])}>
            {options.framings.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
          </TextField>
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
                    <TextField label="배치 크기 (B)" type="number" fullWidth
                               helperText="클수록 syscall 감소"
                               value={form.batchBytes ?? 16384}
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
