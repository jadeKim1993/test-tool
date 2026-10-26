import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined';

import { api } from '../api/client';
import type { Job, JobContent, MetaOptions, Target } from '../api/types';
import { MONO } from '../theme';
import { HTTP_FORMAT_SHORT, PROTOCOL_COLOR, isHttp } from './targets';

const ENCODING_LABEL: Record<string, string> = { UTF_8: 'UTF-8', CP949: 'CP949', BASE64: 'Base64' };

/** 실행 중에는 템플릿이 바뀌면 엔진에 즉시 반영되므로, 화면도 그만큼 자주 다시 읽는다. */
const REFRESH_MILLIS = 15_000;

/**
 * 작업 카드의 "무엇을 → 어디로" 한 줄.
 * <p>보내는 로그(카테고리·인코딩·사용 중 종류 수·선택 방식)와 받는 곳(프로토콜·주소·프레이밍)을 화살표로 잇는다.
 * 로그 종류별 비율·예시는 템플릿 화면에서 본다(§16.24). 켜진 종류가 하나도 없을 때만 경고한다.
 */
export function JobRoute({ job, target, running, options }: {
  job: Job;
  target?: Target;
  running: boolean;
  options: MetaOptions | null;
}) {
  const [content, setContent] = useState<JobContent | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => api.jobContent(job.id).then((c) => { if (alive) setContent(c); }).catch(() => undefined);
    load();
    const timer = running ? window.setInterval(load, REFRESH_MILLIS) : undefined;
    return () => {
      alive = false;
      if (timer) window.clearInterval(timer);
    };
  }, [job.id, job.updatedAt, job.categoryId, running]);

  const selectionLabel = options?.selections.find((o) => o.value === (content?.selection ?? job.selection))?.label
    ?? job.selection;
  const framingLabel = (options?.framings.find((o) => o.value === target?.framing)?.label ?? target?.framing ?? '')
    .replace(/\s*\(기본\)/, '');
  const encoding = content?.encoding ?? 'UTF_8';
  const templates = content?.templates ?? [];

  const detail = target
    ? isHttp(target.protocol)
      ? `POST ${target.httpPath} · ${HTTP_FORMAT_SHORT[target.httpFormat] ?? target.httpFormat}`
      : target.protocol === 'UDP' ? framingLabel : `${framingLabel}${target.protocol === 'TLS' ? ' · 암호화' : ''}`
    : '';

  return (
    <Box sx={{ minWidth: 0 }}>
      {/* 한 줄: 무엇을(카테고리·종류 수) → 어디로(프로토콜·주소). 넓으면 한 줄, 좁으면 화살표에서 접힌다. */}
      <Stack direction="row" useFlexGap spacing={0.75} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
        {/* 카테고리 쪽은 짧으니 줄이지 않는다("nginx …" 처럼 잘리면 종류 수가 안 보인다). 긴 쪽은 주소다. */}
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', flexShrink: 0 }}
               title={`${job.categoryName} · ${selectionLabel}`}>
          <FolderOutlinedIcon sx={{ fontSize: 17, color: 'text.secondary' }} />
          <Typography variant="body2" noWrap sx={{ fontWeight: 700 }}>{job.categoryName}</Typography>
          {encoding !== 'UTF_8' && (
            <Chip variant="outlined" color="warning" label={ENCODING_LABEL[encoding] ?? encoding}
                  sx={{ height: 20, fontSize: '0.7rem' }} />
          )}
          <Typography variant="caption" color="textSecondary" noWrap>
            {/* 카테고리 전체가 아니라 실제로 보내는 종류(켜진 것) */}
            {content ? `사용 중 ${templates.length}종` : '…'}
          </Typography>
        </Stack>
        <ArrowForwardIcon sx={{ fontSize: 18, color: running ? 'success.main' : 'text.disabled' }} />
        {target ? (
          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0 }} title={detail}>
            <Chip label={target.protocol} color={PROTOCOL_COLOR[target.protocol] ?? 'default'}
                  sx={{ height: 20, fontSize: '0.7rem', fontWeight: 700 }} />
            <Typography variant="body2" noWrap sx={{ fontFamily: MONO, fontWeight: 700 }}>
              {target.host}
              <Box component="span" sx={{ color: 'text.secondary' }}>:</Box>
              {target.port}
            </Typography>
            <Typography variant="caption" color="textSecondary" noWrap>{detail}</Typography>
          </Stack>
        ) : (
          <Typography variant="body2" sx={{ fontFamily: MONO }} noWrap>{job.endpoint}</Typography>
        )}
      </Stack>

      {/* 켜진 로그 종류가 없으면 시작해도 보낼 것이 없다. 그때만 알린다(종류·비율은 템플릿 화면에서 본다). */}
      {content && templates.length === 0 && (
        <Alert severity="warning" sx={{ py: 0.25, mt: 1 }}>
          <strong>{job.categoryName}</strong> 에 사용 중인 로그 종류가 없습니다. 템플릿 화면에서 켜야 보낼 수 있습니다.
        </Alert>
      )}
    </Box>
  );
}
