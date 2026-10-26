import Box from '@mui/material/Box';

import type { Target } from '../api/types';
import { MONO } from '../theme';
import { PROTOCOL_COLOR } from './targets';

function octets(host: string): string[] | null {
  const parts = host.split('.');
  return parts.length === 4 && parts.every((p) => /^\d{1,3}$/.test(p)) ? parts : null;
}

/**
 * 다른 대상과 같은 /24 대역(앞 세 옥텟)을 쓰는 대역 목록.
 * 192.168.70.x 가 여럿이면 그 앞부분은 읽을 필요가 없다 — 흐리게 두고 마지막 옥텟과 포트만 진하게 보여 준다.
 * 대역이 섞여 있어도(127.0.0.1 하나가 끼어 있어도) 겹치는 대역끼리는 그대로 적용된다.
 */
export function sharedSubnets(targets: Target[]): Set<string> {
  const counts = new Map<string, number>();
  for (const t of targets) {
    const o = octets(t.host);
    if (!o) continue;
    const subnet = o.slice(0, 3).join('.');
    counts.set(subnet, (counts.get(subnet) ?? 0) + 1);
  }
  return new Set([...counts].filter(([, n]) => n > 1).map(([subnet]) => subnet));
}

/**
 * 대상 주소 표기. 전송 대상 화면의 카드와 작업 만들기의 대상 고르기가 같은 모양을 쓴다.
 * 같은 대역 앞부분은 흐리게, 구별되는 부분은 진하게, 포트는 프로토콜 색. 긴 호스트명은 호스트만 줄이고 포트는 늘 보인다.
 */
export function TargetAddress({ target, subnets, fontSize = '1.2rem' }: {
  target: Target;
  subnets?: Set<string>;
  fontSize?: string;
}) {
  const color = PROTOCOL_COLOR[target.protocol] ?? 'default';
  const parts = octets(target.host);
  const subnet = parts?.slice(0, 3).join('.');
  const dim = subnet && subnets?.has(subnet) ? subnet + '.' : '';
  const strong = dim ? parts![3] : target.host;
  return (
    <Box component="span" title={`${target.protocol} ${target.host}:${target.port}`} sx={{
      display: 'flex', minWidth: 0, whiteSpace: 'nowrap',
      fontFamily: MONO, fontSize, lineHeight: 1.35, fontWeight: 700,
    }}>
      <Box component="span" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>
        {dim && <Box component="span" sx={{ color: 'text.disabled', fontWeight: 400 }}>{dim}</Box>}
        {strong}
      </Box>
      <Box component="span" sx={{ flexShrink: 0 }}>
        <Box component="span" sx={{ color: 'text.disabled', fontWeight: 400 }}>:</Box>
        <Box component="span" sx={{ color: color === 'default' ? 'text.primary' : `${color}.main` }}>
          {target.port}
        </Box>
      </Box>
    </Box>
  );
}
