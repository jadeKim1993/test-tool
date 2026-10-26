import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';

import AddIcon from '@mui/icons-material/Add';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import DoNotDisturbOnOutlinedIcon from '@mui/icons-material/DoNotDisturbOnOutlined';
import DownloadIcon from '@mui/icons-material/Download';
import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import NoteAddOutlinedIcon from '@mui/icons-material/NoteAddOutlined';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import SaveOutlinedIcon from '@mui/icons-material/SaveOutlined';
import SearchIcon from '@mui/icons-material/Search';
import ShareOutlinedIcon from '@mui/icons-material/ShareOutlined';
import TuneIcon from '@mui/icons-material/Tune';
import UndoIcon from '@mui/icons-material/Undo';
import UploadIcon from '@mui/icons-material/Upload';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';

import { api } from '../api/client';
import type {
  Category, FunctionSpec, LogFormat, LogTemplate, MetaOptions, PreviewResponse, WireEncoding,
} from '../api/types';
import { EmptyState, ErrorAlert, Loading, useConfirm, useToast } from '../components/common';
import { useDragReorder } from '../components/dragReorder';
import { useHotkey } from '../hooks/useHotkey';
import { TemplateEditor } from '../components/TemplateEditor';
import { colorOf, specOf } from '../components/templateSyntax';
import { MONO } from '../theme';

/**
 * 템플릿 스튜디오.
 * <p>좌: 카테고리 / 중앙: 그 안의 로그 종류 목록 / 우: 편집기 + 렌더 결과.
 * 로그 종류를 배지로 욱여넣지 않고 독립 목록으로 뺀 이유는, 종류가 늘어나면
 * "무엇이 켜져 있고 비중이 얼마인지"를 한눈에 보는 게 더 중요해지기 때문이다.
 */
export default function Templates() {
  const { toast, element: toastElement } = useToast();
  const { confirm, element: confirmElement } = useConfirm();

  const [categories, setCategories] = useState<Category[]>([]);
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [templates, setTemplates] = useState<LogTemplate[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [draft, setDraft] = useState<Partial<LogTemplate>>({});
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [previewCount, setPreviewCount] = useState(5);
  const [functions, setFunctions] = useState<FunctionSpec[]>([]);
  const [options, setOptions] = useState<MetaOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [bottomTab, setBottomTab] = useState<'preview' | 'values' | 'help'>('preview');
  /** 렌더 결과/값 목록에서 마우스를 올린 플레이스홀더. 편집기 원문의 같은 부분을 진하게 칠한다. */
  const [activePh, setActivePh] = useState<number | null>(null);
  const editorRef = useRef<HTMLTextAreaElement>(null);
  const specs = useMemo(() => new Map(functions.map((f) => [f.name, f])), [functions]);

  const currentCategory = categories.find((c) => c.id === categoryId) ?? null;
  const readOnly = currentCategory ? !currentCategory.owned : true;
  const selected = templates.find((t) => t.id === selectedId) ?? null;
  const isNew = selectedId === null && draft.content !== undefined;

  useEffect(() => {
    Promise.all([api.categories(), api.functions(), api.options()])
      .then(([categoryList, functionList, meta]) => {
        setCategories(categoryList);
        setFunctions(functionList);
        setOptions(meta);
        if (categoryList.length > 0) setCategoryId(categoryList[0].id);
      })
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  const loadTemplates = useCallback(async (id: number, keepSelection = false) => {
    try {
      const list = await api.templates(id);
      setTemplates(list);
      if (!keepSelection) {
        const first = list[0] ?? null;
        setSelectedId(first?.id ?? null);
        setDraft(first ? { ...first } : {});
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    if (categoryId !== null) loadTemplates(categoryId);
  }, [categoryId, loadTemplates]);

  // 편집 중 실시간 미리보기 (300ms 디바운스)
  useEffect(() => {
    if (!draft.content) {
      setPreview(null);
      return;
    }
    const timer = window.setTimeout(() => {
      api.preview(draft.content!, draft.format ?? 'RAW', previewCount)
        .then(setPreview)
        .catch(() => undefined);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [draft.content, draft.format, previewCount]);

  const dirty = useMemo(() => {
    if (!selected) return Boolean(draft.content);
    return draft.name !== selected.name || draft.content !== selected.content
      || draft.format !== selected.format || draft.weight !== selected.weight;
  }, [draft, selected]);

  /** 저장 안 한 수정이 있으면 다른 종류/카테고리로 옮기기 전에 묻는다. 예전엔 말없이 버려졌다. */
  const confirmDiscard = useCallback(async () => !dirty || confirm({
    title: '저장하지 않은 변경이 있습니다',
    message: <><strong>{draft.name || '새 로그 종류'}</strong> 의 수정 내용을 버리고 이동할까요?</>,
    confirmLabel: '버리고 이동',
    danger: true,
  }), [dirty, confirm, draft.name]);

  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  const save = useCallback(async () => {
    if (!categoryId || !draft.content || !draft.name || readOnly) return;
    try {
      const body = {
        name: draft.name, content: draft.content, format: draft.format ?? 'RAW',
        weight: draft.weight ?? 1, enabled: draft.enabled ?? true,
        sortOrder: draft.sortOrder,
      };
      const saved = selectedId
        ? await api.updateTemplate(selectedId, body)
        : await api.createTemplate(categoryId, body);
      toast.ok('저장했습니다. 실행 중인 전송에도 즉시 반영됩니다.');
      await loadTemplates(categoryId, true);
      setSelectedId(saved.id);
      setDraft({ ...saved });
      setCategories(await api.categories());
    } catch (e) {
      setError((e as Error).message);
    }
  }, [categoryId, draft, readOnly, selectedId, loadTemplates, toast]);

  // Ctrl+S 저장
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        save();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [save]);

  const select = async (template: LogTemplate) => {
    if (template.id === selectedId || !(await confirmDiscard())) return;
    setSelectedId(template.id);
    setDraft({ ...template });
  };

  const selectCategory = async (id: number) => {
    if (id === categoryId || !(await confirmDiscard())) return;
    setCategoryId(id);
  };

  const startNew = async () => {
    if (!(await confirmDiscard())) return;
    setSelectedId(null);
    setDraft({ name: '', content: '', format: 'RAW', weight: 1, enabled: true });
    requestAnimationFrame(() => editorRef.current?.focus());
  };

  /** 지금 편집기 내용(저장 전 수정 포함)으로 새 종류를 만든다. 비슷한 로그를 하나 더 만들 때 가장 빠르다. */
  const duplicateTemplate = () => {
    setSelectedId(null);
    setDraft((prev) => ({ ...prev, id: undefined, name: `${prev.name ?? ''}-copy`, sortOrder: undefined }));
    toast.ok('복제본을 만들었습니다. 이름을 확인하고 저장하세요.');
  };

  const revert = () => {
    if (selected) setDraft({ ...selected });
  };

  /**
   * 끌어서 바꾼 로그 종류 순서. 화면을 먼저 바꾸고 저장한다(실패하면 다시 읽는다).
   * 편집 중인 종류의 sortOrder 도 맞춰 둔다 — 안 그러면 다음 저장 때 옛 위치로 되돌아간다.
   */
  const reorderTemplates = async (ids: number[]) => {
    if (!categoryId) return;
    const byId = new Map(templates.map((t) => [t.id, t]));
    setTemplates(ids.map((id, index) => ({ ...byId.get(id)!, sortOrder: index })));
    if (selectedId !== null && ids.includes(selectedId)) {
      setDraft((prev) => ({ ...prev, sortOrder: ids.indexOf(selectedId) }));
    }
    try {
      await api.reorderTemplates(categoryId, ids);
    } catch (e) {
      setError((e as Error).message);
      loadTemplates(categoryId, true);
    }
  };
  const templateDrag = useDragReorder(templates.map((t) => t.id), reorderTemplates);

  /** 끌어서 바꾼 카테고리 순서. 내 카테고리만 옮길 수 있고 공유 카테고리는 늘 그 뒤에 둔다. */
  const reorderCategories = async (ids: number[]) => {
    const byId = new Map(categories.map((c) => [c.id, c]));
    setCategories([...ids.map((id) => byId.get(id)!), ...categories.filter((c) => !c.owned)]);
    try {
      await api.reorderCategories(ids);
    } catch (e) {
      setError((e as Error).message);
      setCategories(await api.categories());
    }
  };

  /** 렌더 결과에서 값을 누르면 그 값을 만든 원문 플레이스홀더를 편집기에서 선택한다. */
  const pickPlaceholder = (index: number) => {
    const ph = preview?.placeholders[index];
    const editor = editorRef.current;
    // 미리보기는 300ms 늦게 오므로 그 사이 원문이 바뀌었으면 위치가 맞지 않는다.
    if (!ph || !editor || (draft.content ?? '').slice(ph.start, ph.end) !== ph.expression) return;
    editor.blur();
    editor.setSelectionRange(ph.start, ph.end);
    editor.focus();
  };

  /** 목록에서 바로 켜고 끈다. 편집기를 열 필요가 없다. */
  const toggleEnabled = async (template: LogTemplate) => {
    try {
      await api.updateTemplate(template.id, {
        name: template.name, content: template.content, format: template.format,
        weight: template.weight, enabled: !template.enabled, sortOrder: template.sortOrder,
      });
      if (categoryId) await loadTemplates(categoryId, true);
      if (selectedId === template.id) {
        setDraft((prev) => ({ ...prev, enabled: !template.enabled }));
      }
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const setAllEnabled = async (enabled: boolean) => {
    if (!categoryId) return;
    try {
      const result = await api.setAllTemplatesEnabled(categoryId, enabled);
      await loadTemplates(categoryId, true);
      if (selectedId) setDraft((prev) => ({ ...prev, enabled }));
      toast.ok(result.changed === 0
        ? '변경할 항목이 없습니다'
        : `${result.changed}개를 ${enabled ? '사용' : '미사용'}으로 바꿨습니다`);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const remove = async () => {
    if (!selectedId) return;
    const ok = await confirm({
      title: '로그 종류를 삭제할까요?',
      message: <><strong>{selected?.name}</strong> 템플릿이 삭제됩니다.</>,
      confirmLabel: '삭제',
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteTemplate(selectedId);
      toast.ok('삭제했습니다');
      if (categoryId) await loadTemplates(categoryId);
      setCategories(await api.categories());
    } catch (e) {
      setError((e as Error).message);
    }
  };

  /** 도움말에서 함수를 클릭하면 커서 위치에 삽입한다. */
  const insert = (snippet: string) => {
    const editor = editorRef.current;
    if (!editor || readOnly) return;
    const start = editor.selectionStart;
    const end = editor.selectionEnd;
    const content = draft.content ?? '';
    const next = content.slice(0, start) + snippet + content.slice(end);
    setDraft((prev) => ({ ...prev, content: next }));
    requestAnimationFrame(() => {
      editor.focus();
      editor.setSelectionRange(start + snippet.length, start + snippet.length);
    });
  };

  useHotkey('n', () => { if (!readOnly && currentCategory) startNew(); });

  const enabledCount = templates.filter((t) => t.enabled).length;
  const totalWeight = templates.filter((t) => t.enabled).reduce((sum, t) => sum + t.weight, 0);
  // 편집 중인 가중치를 반영한 비율. 저장 전에 "이 값이면 몇 %인지" 바로 보이게 한다.
  const otherWeight = templates.filter((t) => t.enabled && t.id !== selectedId).reduce((sum, t) => sum + t.weight, 0);
  const draftWeight = Math.max(0, draft.weight ?? 0);
  const draftShare = (draft.enabled ?? true) && otherWeight + draftWeight > 0
    ? Math.round(draftWeight / (otherWeight + draftWeight) * 100) : null;

  if (loading) return <Loading />;

  return (
    <>
      <ErrorAlert error={error} onClose={() => setError(null)} />

      <Box sx={{
        display: 'grid',
        gap: 2,
        alignItems: 'start',
        // minmax(0, …) 가 없으면 고정폭 로그 내용이 열을 밀어 가로 스크롤이 생긴다.
        gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '190px 270px minmax(0, 1fr)' },
      }}>
        {/* ---------- 좌: 카테고리 ---------- */}
        <CategoryPanel
          categories={categories}
          selectedId={categoryId}
          options={options}
          onSelect={selectCategory}
          onReorder={reorderCategories}
          onChanged={async () => setCategories(await api.categories())}
          onError={setError}
          toast={toast}
          confirm={confirm}
        />

        {/* ---------- 중앙: 로그 종류 목록 ---------- */}
        <Paper variant="outlined" sx={{ display: 'flex', flexDirection: 'column' }}>
          <Box sx={{ p: 1.5, pb: 1 }}>
            <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <Typography variant="subtitle2">
                로그 종류 {templates.length}개
                {templates.length > 0 && (
                  <Box component="span" sx={{
                    ml: 0.5, color: enabledCount === 0 ? 'error.main' : 'text.secondary', fontWeight: 400,
                  }}>
                    (사용 {enabledCount})
                  </Box>
                )}
              </Typography>
              <Tooltip title="로그 종류 추가 (N)">
                <span>
                  <IconButton size="small" color="primary" aria-label="로그 종류 추가"
                              disabled={readOnly} onClick={startNew}>
                    <AddIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </Stack>

            {templates.length > 1 && !readOnly && (
              <Typography variant="caption" color="textSecondary" sx={{ display: 'block', mt: 0.25 }}>
                끌어서 순서를 바꿉니다 · 순차/라운드로빈은 이 순서로 보냅니다
              </Typography>
            )}
            {templates.length > 0 && (
              <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                <Button fullWidth variant="outlined" color="success" startIcon={<PlaylistAddCheckIcon />}
                        disabled={readOnly || enabledCount === templates.length}
                        onClick={() => setAllEnabled(true)}>
                  전체 사용
                </Button>
                <Button fullWidth variant="outlined" color="error" startIcon={<DoNotDisturbOnOutlinedIcon />}
                        disabled={readOnly || enabledCount === 0}
                        onClick={() => setAllEnabled(false)}>
                  전체 해제
                </Button>
              </Stack>
            )}
          </Box>
          <Divider />

          <Box sx={{ overflowY: 'auto', maxHeight: 'calc(100vh - 220px)' }}>
            {isNew && (
              <Box sx={{
                px: 1.5, py: 1, borderLeft: 3, borderColor: 'primary.main',
                bgcolor: 'action.selected',
              }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <NoteAddOutlinedIcon fontSize="small" color="primary" />
                  <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
                    {draft.name || '새 로그 종류'}
                  </Typography>
                </Stack>
                <Typography variant="caption" color="textSecondary">
                  저장 전 · 오른쪽에서 이름과 내용을 입력하세요
                </Typography>
              </Box>
            )}

            {templates.length === 0 && !isNew && (
              <EmptyState icon={<NoteAddOutlinedIcon />} title="로그 종류가 없습니다"
                          hint="+ 버튼으로 추가하세요" />
            )}

            {templates.map((template) => {
              const share = template.enabled && totalWeight > 0
                ? Math.round(template.weight / totalWeight * 100) : null;
              return (
                <Box
                  key={template.id}
                  onClick={() => select(template)}
                  {...templateDrag.itemProps(template.id, !readOnly)}
                  sx={{
                    px: 1.5, py: 1, cursor: 'pointer',
                    borderLeft: 3,
                    borderColor: selectedId === template.id ? 'primary.main' : 'transparent',
                    borderBottom: 1, borderBottomColor: 'divider',
                    bgcolor: selectedId === template.id ? 'action.selected' : undefined,
                    opacity: template.enabled ? 1 : 0.5,
                    '&:hover': { bgcolor: 'action.hover' },
                    '&:hover .drag-handle': { opacity: 1 },
                    ...templateDrag.itemSx(template.id),
                  }}
                >
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    {!readOnly && (
                      <DragIndicatorIcon className="drag-handle" titleAccess="끌어서 순서 바꾸기"
                                         sx={{ fontSize: 16, ml: -1, mr: -0.5, color: 'text.disabled', cursor: 'grab', opacity: 0.5 }} />
                    )}
                    <Tooltip title={template.enabled ? '전송에 포함됨' : '전송에서 제외됨'}>
                      <Switch
                        size="small" checked={template.enabled} disabled={readOnly}
                        onClick={(e) => e.stopPropagation()}
                        onChange={() => toggleEnabled(template)}
                      />
                    </Tooltip>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600, flex: 1, minWidth: 0 }}>
                      {template.name}
                      {selectedId === template.id && dirty && (
                        <Box component="span" title="저장하지 않은 변경이 있습니다"
                             sx={{ ml: 0.75, color: 'warning.main', fontSize: '0.7rem', fontWeight: 600 }}>
                          ● 수정 중
                        </Box>
                      )}
                    </Typography>
                    <Chip variant="outlined" label={share !== null ? `${share}%` : template.weight}
                          title={share !== null ? `전체의 약 ${share}%` : '가중치'} />
                  </Stack>
                  <Typography variant="caption" noWrap
                              sx={{ display: 'block', fontFamily: MONO, color: 'text.secondary', mt: 0.25 }}>
                    {template.content.split('\n')[0]}
                  </Typography>
                  {template.format !== 'RAW' && (
                    <Typography variant="caption" sx={{ color: 'info.main' }}>{template.format}</Typography>
                  )}
                </Box>
              );
            })}
          </Box>
        </Paper>

        {/* ---------- 우: 편집기 + 렌더 결과 ---------- */}
        {!currentCategory ? (
          <Paper variant="outlined">
            <EmptyState icon={<FolderOutlinedIcon />} title="카테고리를 선택하세요" />
          </Paper>
        ) : !selected && !isNew ? (
          <Paper variant="outlined">
            <EmptyState icon={<NoteAddOutlinedIcon />} title="편집할 로그 종류를 선택하세요"
                        hint="가운데 목록에서 고르거나 + 로 새로 만드세요" />
          </Paper>
        ) : (
          <Paper variant="outlined">
            <Box sx={{ p: 2 }}>
              {readOnly && (
                <Alert severity="info" sx={{ mb: 2 }}>
                  공유된 카테고리입니다. 수정하려면 복제하세요.
                </Alert>
              )}

              {/* 편집기 폭은 화면 폭과 따로 논다. 브레이크포인트 대신 flex-wrap 으로 접는다. */}
              <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', mb: 2 }}>
                <TextField label="이름" placeholder="예: access-200" disabled={readOnly}
                           sx={{ flex: '2 1 180px' }}
                           value={draft.name ?? ''}
                           onChange={(e) => setDraft((p) => ({ ...p, name: e.target.value }))} />
                <TextField select label="포맷" disabled={readOnly} sx={{ flex: '1 1 160px' }}
                           value={draft.format ?? 'RAW'}
                           onChange={(e) => setDraft((p) => ({ ...p, format: e.target.value as LogFormat }))}>
                  {options?.formats.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
                </TextField>
                <TextField label="가중치" type="number" disabled={readOnly}
                           sx={{ flex: '0 0 96px' }}
                           title="같은 카테고리 안에서 이 종류가 뽑힐 상대 비중입니다 (가중치 랜덤 작업에만 적용)"
                           value={draft.weight ?? 1}
                           onChange={(e) => setDraft((p) => ({ ...p, weight: Number(e.target.value) }))} />
              </Stack>

              <TemplateEditor
                value={draft.content ?? ''}
                onChange={(content) => setDraft((p) => ({ ...p, content }))}
                functions={functions}
                disabled={readOnly}
                error={draft.content ? preview?.error : null}
                errorPosition={preview?.position}
                activeIndex={activePh}
                inputRef={editorRef}
                placeholder={'로그 한 줄을 그대로 붙여 넣고, 바뀌어야 할 자리를 ${ip} ${int(1,100)} 같은 함수로 바꾸세요'}
              />

              <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Tooltip title="끄면 이 종류는 전송에서 제외됩니다">
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                    <Switch size="small" checked={draft.enabled ?? true} disabled={readOnly}
                            onChange={(e) => setDraft((p) => ({ ...p, enabled: e.target.checked }))} />
                    <Typography variant="body2">전송에 사용</Typography>
                  </Stack>
                </Tooltip>
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="textSecondary">
                  {draftShare !== null && (
                    <span title="이 카테고리에서 사용 중인 종류들의 가중치 합 대비. 라운드로빈/순차 작업은 가중치와 무관하게 균등합니다">
                      가중치 랜덤 시 약 <strong>{draftShare}%</strong>
                    </span>
                  )}
                  {draftShare !== null && preview && !preview.error && ' · '}
                  {preview && !preview.error && (
                    <Box component="span" sx={{ fontFamily: MONO }}>평균 {preview.averageBytes} B/건</Box>
                  )}
                </Typography>
                {selectedId && dirty && !readOnly && (
                  <Tooltip title="저장된 내용으로 되돌리기">
                    <IconButton size="small" onClick={revert} aria-label="되돌리기">
                      <UndoIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
                {selectedId && !readOnly && (
                  <Tooltip title="이 내용으로 새 로그 종류 만들기">
                    <IconButton size="small" onClick={duplicateTemplate} aria-label="복제">
                      <ContentCopyIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
                {selectedId && !readOnly && (
                  <Tooltip title="삭제">
                    <IconButton size="small" color="error" onClick={remove} aria-label="삭제">
                      <DeleteOutlineIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
                <Button variant="contained" startIcon={<SaveOutlinedIcon />}
                        disabled={readOnly || !dirty || !draft.name || Boolean(preview?.error)}
                        onClick={save}>
                  저장 (Ctrl+S)
                </Button>
              </Stack>
            </Box>

            <Divider />
            <Stack direction="row" sx={{ alignItems: 'center', px: 1.5 }}>
              <Tabs value={bottomTab} onChange={(_, value) => setBottomTab(value)} sx={{ flex: 1, minHeight: 42 }}>
                <Tab value="preview" label="렌더 결과" sx={{ minHeight: 42 }} />
                <Tab value="values" label={`사용된 값 ${preview?.placeholders.length ?? 0}개`} sx={{ minHeight: 42 }} />
                <Tab value="help" label={`함수 ${functions.length}개`} sx={{ minHeight: 42 }} />
              </Tabs>
              {bottomTab === 'preview' && (
                <TextField select value={previewCount} sx={{ width: 96 }}
                           onChange={(e) => setPreviewCount(Number(e.target.value))}>
                  {[1, 3, 5, 10, 20].map((n) => <MenuItem key={n} value={n}>{n}건</MenuItem>)}
                </TextField>
              )}
            </Stack>
            <Divider />

            <Box sx={{ p: 1.5 }}>
              {bottomTab === 'preview' && (
                <PreviewPane preview={preview} count={previewCount} specs={specs} activeIndex={activePh}
                             onHover={setActivePh} onPick={pickPlaceholder} />
              )}
              {bottomTab === 'values' && (
                <PlaceholderTable preview={preview} specs={specs} activeIndex={activePh}
                                  onHover={setActivePh} onPick={pickPlaceholder} />
              )}
              {bottomTab === 'help' && <FunctionHelp functions={functions} onInsert={insert} />}
            </Box>
          </Paper>
        )}
      </Box>

      {toastElement}
      {confirmElement}
    </>
  );
}

interface LinkProps {
  specs: Map<string, FunctionSpec>;
  activeIndex: number | null;
  onHover: (index: number | null) => void;
  onPick: (index: number) => void;
}

/**
 * 렌더 결과. 값마다 그것을 만든 플레이스홀더의 색을 입히고, 마우스를 올리면 원문의 같은 자리를,
 * 누르면 편집기에서 그 플레이스홀더를 선택한다 — "이 숫자는 어디서 나왔지?"를 바로 찾게 한다.
 */
function PreviewPane({ preview, count, specs, activeIndex, onHover, onPick }: LinkProps & {
  preview: PreviewResponse | null;
  count: number;
}) {
  const theme = useTheme();
  if (preview?.error) {
    return <Alert severity="error">{preview.error}</Alert>;
  }
  if (!preview || preview.lines.length === 0) {
    return <EmptyState icon={<VisibilityOutlinedIcon />} title="템플릿을 입력하면"
                       hint="실제 전송될 로그가 여기 표시됩니다" />;
  }
  return (
    <>
      <Box sx={{
        maxHeight: 300, overflowY: 'auto', borderRadius: 1,
        bgcolor: 'action.hover', border: 1, borderColor: 'divider',
      }}>
        {preview.lines.map((line, index) => (
          <Stack key={index} direction="row" spacing={1.5}
                 sx={{
                   px: 1, py: 0.6, borderBottom: index === preview.lines.length - 1 ? 0 : 1,
                   borderColor: 'divider',
                 }}>
            <Typography variant="caption" sx={{ color: 'text.secondary', minWidth: 20, textAlign: 'right' }}>
              {index + 1}
            </Typography>
            <Typography component="pre" sx={{
              fontFamily: MONO, fontSize: '0.76rem', lineHeight: 1.55, m: 0,
              whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1,
            }}>
              {(preview.parts[index] ?? [{ text: line, source: -1 }]).map((part, i) => {
                if (part.source === -1) return <span key={i}>{part.text}</span>;
                if (part.source === -2) {
                  return (
                    <Box key={i} component="span" title="포맷(syslog)이 자동으로 붙인 헤더"
                         sx={{ color: 'text.secondary', textDecoration: 'underline dotted', textUnderlineOffset: '3px' }}>
                      {part.text}
                    </Box>
                  );
                }
                const ph = preview.placeholders[part.source];
                const main = theme.palette[colorOf(ph?.function, specs)].main;
                const active = activeIndex === part.source;
                return (
                  <Box key={i} component="span" title={`${ph?.expression ?? ''} — 누르면 원문에서 선택`}
                       onMouseEnter={() => onHover(part.source)} onMouseLeave={() => onHover(null)}
                       onClick={() => onPick(part.source)}
                       sx={{
                         color: main, cursor: 'pointer', borderRadius: '3px',
                         bgcolor: alpha(main, active ? 0.3 : 0.1),
                         boxShadow: active ? `0 0 0 1px ${main}` : undefined,
                       }}>
                    {part.text}
                  </Box>
                );
              })}
            </Typography>
          </Stack>
        ))}
      </Box>
      <Typography variant="caption" color="textSecondary" sx={{ mt: 1, display: 'block' }}>
        같은 템플릿을 {count}번 렌더링한 결과입니다 — 색칠된 부분이 매번 바뀌는 값이고, 누르면 원문에서 찾아 줍니다.
        전송 건수는 여기 표시된 개수와 무관하며 작업의 EPS 설정을 따릅니다.
      </Typography>
    </>
  );
}

/** 이 템플릿에 쓰인 플레이스홀더 목록. 원문을 해석하지 않아도 "어떤 값이 어떤 규칙으로" 들어가는지 표로 본다. */
function PlaceholderTable({ preview, specs, activeIndex, onHover, onPick }: LinkProps & {
  preview: PreviewResponse | null;
}) {
  if (preview?.error) {
    return <Alert severity="error">{preview.error}</Alert>;
  }
  if (!preview || preview.placeholders.length === 0) {
    return <EmptyState icon={<VisibilityOutlinedIcon />} title="사용된 함수가 없습니다"
                       hint="고정 문자열만 있으면 매번 같은 로그가 나갑니다" />;
  }
  // 렌더 결과 여러 줄에서 실제로 나온 값을 모아, 값이 어떻게 흔들리는지 보여 준다.
  const samples = preview.placeholders.map((_, index) => {
    const values = preview.parts.flatMap((line) => line.filter((p) => p.source === index).map((p) => p.text));
    return [...new Set(values)].slice(0, 4);
  });
  return (
    <Box sx={{ maxHeight: 300, overflowY: 'auto' }}>
      {preview.placeholders.map((ph, index) => {
        const spec = specOf(ph.function, specs);
        return (
          <Box key={index}
               onMouseEnter={() => onHover(index)} onMouseLeave={() => onHover(null)}
               onClick={() => onPick(index)}
               title="누르면 원문에서 선택"
               sx={{
                 display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 3fr)', gap: 1.5,
                 px: 1, py: 0.75, borderRadius: 1, cursor: 'pointer',
                 bgcolor: activeIndex === index ? 'action.selected' : undefined,
                 '&:hover': { bgcolor: 'action.hover' },
               }}>
            <Typography noWrap sx={{
              fontFamily: MONO, fontSize: '0.78rem', color: `${colorOf(ph.function, specs)}.main`,
            }}>
              {ph.expression}
            </Typography>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="caption" color="textSecondary" noWrap sx={{ display: 'block' }}>
                {spec?.description ?? ph.function}
              </Typography>
              <Typography variant="caption" noWrap sx={{ display: 'block', fontFamily: MONO }}>
                {samples[index].map((v) => JSON.stringify(v).slice(1, -1)).join('  ·  ')}
              </Typography>
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}

/** 함수가 20개를 넘어가면 눈으로 찾기 어렵다. 이름·설명·예시를 한 번에 건다. */
function FunctionHelp({ functions, onInsert }: {
  functions: FunctionSpec[];
  onInsert: (snippet: string) => void;
}) {
  const [query, setQuery] = useState('');

  const groups = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const matched = needle
      ? functions.filter((spec) =>
        spec.name.toLowerCase().includes(needle)
        || spec.description.toLowerCase().includes(needle)
        || spec.signature.toLowerCase().includes(needle))
      : functions;
    const map = new Map<string, FunctionSpec[]>();
    for (const spec of matched) {
      const list = map.get(spec.group) ?? [];
      list.push(spec);
      map.set(spec.group, list);
    }
    return [...map.entries()];
  }, [functions, query]);

  return (
    <>
      <TextField
        fullWidth placeholder="함수 검색 (예: ip, 시간, 랜덤)"
        value={query} onChange={(e) => setQuery(e.target.value)}
        slotProps={{
          input: {
            startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>,
          },
        }}
        sx={{ mb: 1 }}
      />
      <Box sx={{ maxHeight: 300, overflowY: 'auto' }}>
        {groups.length === 0 && (
          <Typography variant="body2" color="textSecondary" sx={{ p: 2, textAlign: 'center' }}>
            검색 결과가 없습니다
          </Typography>
        )}
        {groups.map(([group, specs]) => (
          <Box key={group} sx={{ mb: 1 }}>
            <Typography variant="caption" sx={{ px: 1, fontWeight: 700, color: 'text.secondary' }}>
              {group}
            </Typography>
            {specs.map((spec) => (
              <Box
                key={spec.name}
                onClick={() => onInsert(spec.example)}
                title="클릭하면 커서 위치에 삽입됩니다"
                sx={{
                  px: 1, py: 0.75, borderRadius: 1, cursor: 'pointer',
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Typography sx={{ fontFamily: MONO, fontSize: '0.78rem', color: 'info.main' }}>
                  {spec.signature}
                </Typography>
                <Typography variant="caption" color="textSecondary" sx={{ display: 'block' }}>
                  {spec.description}
                </Typography>
                <Typography variant="caption" sx={{ fontFamily: MONO }}>
                  {spec.example}
                  <Box component="span" sx={{ color: 'text.secondary' }}> → {spec.sample}</Box>
                </Typography>
              </Box>
            ))}
          </Box>
        ))}
      </Box>
      <Divider sx={{ my: 1 }} />
      <Typography variant="caption" color="textSecondary" sx={{ display: 'block' }}>
        <Box component="code" sx={{ fontFamily: MONO }}>{'${ip as cip}'}</Box> 로 이름을 붙이고{' '}
        <Box component="code" sx={{ fontFamily: MONO }}>{'${ref(cip)}'}</Box> 로 같은 값을 재사용할 수 있습니다.
        리터럴 <Box component="code" sx={{ fontFamily: MONO }}>{'${'}</Box> 는{' '}
        <Box component="code" sx={{ fontFamily: MONO }}>{'\\${'}</Box> 로 씁니다.
      </Typography>
    </>
  );
}

function CategoryPanel({ categories, selectedId, options, onSelect, onReorder, onChanged, onError, toast, confirm }: {
  categories: Category[];
  selectedId: number | null;
  options: MetaOptions | null;
  onSelect: (id: number) => void;
  onReorder: (ids: number[]) => void;
  onChanged: () => Promise<void>;
  onError: (message: string) => void;
  toast: { ok: (text: string) => void };
  confirm: (options: { title: string; message?: React.ReactNode; confirmLabel?: string; danger?: boolean })
    => Promise<boolean>;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [menu, setMenu] = useState<{ anchor: HTMLElement; category: Category } | null>(null);
  const [settings, setSettings] = useState<Category | null>(null);
  const drag = useDragReorder(categories.filter((c) => c.owned).map((c) => c.id), onReorder);

  const create = async () => {
    if (!name.trim()) return;
    try {
      const created = await api.createCategory({ name: name.trim(), shared: false });
      setName('');
      setAdding(false);
      await onChanged();
      onSelect(created.id);
      toast.ok('카테고리를 만들었습니다');
    } catch (e) {
      onError((e as Error).message);
    }
  };

  const duplicate = async (category: Category) => {
    try {
      const copy = await api.duplicateCategory(category.id);
      await onChanged();
      onSelect(copy.id);
      toast.ok(`${copy.name} 으로 복제했습니다`);
    } catch (e) {
      onError((e as Error).message);
    }
  };

  const remove = async (category: Category) => {
    const ok = await confirm({
      title: '카테고리를 삭제할까요?',
      message: <><strong>{category.name}</strong> 카테고리와 그 안의 로그 종류가 모두 삭제됩니다.</>,
      confirmLabel: '삭제',
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteCategory(category.id);
      await onChanged();
      toast.ok('삭제했습니다');
    } catch (e) {
      onError((e as Error).message);
    }
  };

  const exportOne = async (category: Category) => {
    try {
      const payload = await api.exportCategory(category.id);
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${category.name}.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      onError((e as Error).message);
    }
  };

  const importFile = async (file: File) => {
    try {
      const created = await api.importCategory(JSON.parse(await file.text()));
      await onChanged();
      onSelect(created.id);
      toast.ok(`${created.name} 을(를) 가져왔습니다`);
    } catch (e) {
      onError((e as Error).message);
    }
  };

  return (
    <Paper variant="outlined">
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', p: 1.5, pb: 1 }}>
        <Typography variant="subtitle2">카테고리</Typography>
        <Stack direction="row" spacing={0.5}>
          <Tooltip title="JSON 가져오기">
            <IconButton size="small" component="label">
              <UploadIcon fontSize="small" />
              <input type="file" accept="application/json" hidden
                     onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])} />
            </IconButton>
          </Tooltip>
          <Tooltip title="새 카테고리">
            <IconButton size="small" color="primary" onClick={() => setAdding(true)}>
              <AddIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        </Stack>
      </Stack>
      <Divider />

      <List dense disablePadding sx={{ p: 0.75 }}>
        {adding && (
          <Box sx={{ px: 0.5, pb: 1 }}>
            <TextField
              fullWidth autoFocus placeholder="이름 (예: kafka)"
              value={name} onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') create();
                if (e.key === 'Escape') { setAdding(false); setName(''); }
              }}
              onBlur={() => { if (!name.trim()) setAdding(false); }}
            />
          </Box>
        )}

        {categories.map((category) => (
          <ListItemButton
            key={category.id}
            selected={selectedId === category.id}
            onClick={() => onSelect(category.id)}
            {...drag.itemProps(category.id, category.owned)}
            sx={{ pr: 0.5, pl: 0.5, '&:hover .drag-handle': { opacity: 1 }, ...drag.itemSx(category.id) }}
          >
            {/* 공유 카테고리는 순서를 바꿀 수 없다(늘 내 카테고리 뒤). 자리만 맞춘다. */}
            <DragIndicatorIcon className="drag-handle" titleAccess={category.owned ? '끌어서 순서 바꾸기' : undefined}
                               sx={{
                                 fontSize: 15, mr: 0.25, color: 'text.disabled', opacity: 0.5,
                                 cursor: 'grab', visibility: category.owned ? 'visible' : 'hidden',
                               }} />
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>{category.name}</Typography>
                {!category.owned && (
                  <Tooltip title="공유됨">
                    <ShareOutlinedIcon sx={{ fontSize: 13, color: 'text.secondary' }} />
                  </Tooltip>
                )}
              </Stack>
              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                <Typography variant="caption" color="textSecondary">{category.templateCount}종</Typography>
                {category.encoding !== 'UTF_8' && (
                  <Typography variant="caption" sx={{ color: 'warning.main', fontWeight: 600 }}>
                    · {category.encoding === 'BASE64' ? 'Base64' : category.encoding}
                  </Typography>
                )}
              </Stack>
            </Box>
            <IconButton size="small"
                        onClick={(e) => { e.stopPropagation(); setMenu({ anchor: e.currentTarget, category }); }}>
              <MoreVertIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </ListItemButton>
        ))}
      </List>

      <Menu anchorEl={menu?.anchor} open={menu !== null} onClose={() => setMenu(null)}>
        {menu?.category.owned && (
          <MenuItem onClick={() => { if (menu) setSettings(menu.category); setMenu(null); }}>
            <ListItemIcon><TuneIcon fontSize="small" /></ListItemIcon>설정
          </MenuItem>
        )}
        <MenuItem onClick={() => { if (menu) duplicate(menu.category); setMenu(null); }}>
          <ListItemIcon><ContentCopyIcon fontSize="small" /></ListItemIcon>복제
        </MenuItem>
        <MenuItem onClick={() => { if (menu) exportOne(menu.category); setMenu(null); }}>
          <ListItemIcon><DownloadIcon fontSize="small" /></ListItemIcon>내보내기
        </MenuItem>
        {menu?.category.owned && (
          <MenuItem onClick={() => { if (menu) remove(menu.category); setMenu(null); }}>
            <ListItemIcon><DeleteOutlineIcon fontSize="small" color="error" /></ListItemIcon>
            <Typography color="error" variant="body2">삭제</Typography>
          </MenuItem>
        )}
      </Menu>

      {settings && options && (
        <CategoryDialog
          category={settings}
          options={options}
          onClose={() => setSettings(null)}
          onSaved={async () => { setSettings(null); await onChanged(); toast.ok('저장했습니다'); }}
          onError={onError}
        />
      )}
    </Paper>
  );
}

/**
 * 카테고리 설정. 이름/설명과 함께 <b>전송 인코딩</b>을 여기서 정한다.
 * <p>인코딩은 "이 로그가 어떤 문자셋으로 쓰인 로그인가"의 문제라 수집 서버가 아니라
 * 로그 쪽에 속한다. 같은 서버로 보내도 카테고리마다 다른 인코딩을 쓸 수 있다(§16.14).
 */
function CategoryDialog({ category, options, onClose, onSaved, onError }: {
  category: Category;
  options: MetaOptions;
  onClose: () => void;
  onSaved: () => void;
  onError: (message: string) => void;
}) {
  const [name, setName] = useState(category.name);
  const [description, setDescription] = useState(category.description ?? '');
  const [encoding, setEncoding] = useState<WireEncoding>(category.encoding);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setBusy(true);
    try {
      await api.updateCategory(category.id, {
        name: name.trim(),
        description: description.trim() || undefined,
        shared: category.shared,
        encoding,
      });
      onSaved();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm">
      <DialogTitle>카테고리 설정</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <TextField label="이름" fullWidth autoFocus value={name}
                     onChange={(e) => setName(e.target.value)} />
          <TextField label="설명" fullWidth value={description} placeholder="선택 사항"
                     onChange={(e) => setDescription(e.target.value)} />
          <TextField select label="전송 인코딩" fullWidth value={encoding}
                     helperText="이 카테고리의 로그를 회선에 실을 때의 바이트 형식입니다."
                     onChange={(e) => setEncoding(e.target.value as WireEncoding)}>
            {options.encodings.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
          </TextField>

          {encoding === 'CP949' && (
            <Alert severity="warning">
              CP949(MS949)로 변환합니다. 한글은 UTF-8 대비 약 2/3 크기로 줄어들며,
              MS949 에 없는 문자는 <code>?</code> 로 치환됩니다.
            </Alert>
          )}
          {encoding === 'BASE64' && (
            <Alert severity="warning">
              UTF-8 로 만든 뒤 전체를 Base64 로 감쌉니다. 전송량이 약 1.33배로 늘고,
              수신측에서 디코딩해야 원문을 볼 수 있습니다.
            </Alert>
          )}
          <Typography variant="caption" color="textSecondary">
            실행 중인 작업에는 즉시 반영되지 않습니다 — 인코딩을 바꾼 뒤에는 정지 후 다시 시작하세요.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>취소</Button>
        <Button variant="contained" disabled={busy || !name.trim()}
                startIcon={busy ? <CircularProgress size={15} color="inherit" /> : undefined}
                onClick={save}>
          저장
        </Button>
      </DialogActions>
    </Dialog>
  );
}
