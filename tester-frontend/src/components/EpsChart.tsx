import { useTheme } from '@mui/material/styles';
import { LineChart } from '@mui/x-charts/LineChart';
import type { JobStatus } from '../api/types';
import { fmt } from './common';

/** 축 라벨은 자리 수가 고정돼야 읽힌다. 로캘 포맷("20시 43분")은 너무 길다. */
const clock = (timestamp: number) => new Date(timestamp).toTimeString().slice(0, 8);

/**
 * 실측 EPS 시계열.
 * <p>1초마다 갱신되므로 애니메이션을 끈다(켜두면 렌더 비용이 표시값보다 커진다).
 * 목표선(점선)을 실측 면적 위에 겹쳐 두어 "따라가고 있는지"를 한눈에 본다.
 */
export function EpsChart({ series, height = 150 }: { series: JobStatus[]; height?: number }) {
  const theme = useTheme();

  const times = series.map((point) => clock(point.timestamp));
  const actual = series.map((point) => Math.round(point.eps));
  // 무제한(0)은 목표선이 없는 것과 같다. 0 으로 그리면 바닥에 붙어 오해를 준다.
  const target = series.map((point) => (point.targetEps > 0 ? point.targetEps : null));

  return (
    <LineChart
      height={height}
      margin={{ top: 10, right: 12, bottom: 4, left: 4 }}
      xAxis={[{
        data: times,
        scaleType: 'point',
        // 초 단위 샘플이라 라벨을 다 찍으면 붙어 버린다. 대여섯 개만 남긴다.
        tickInterval: (_, index) => index % Math.max(1, Math.ceil(times.length / 6)) === 0,
        disableLine: true,
        disableTicks: true,
        tickLabelStyle: { fontSize: 10, fill: theme.palette.text.secondary },
      }]}
      yAxis={[{
        min: 0,
        disableLine: true,
        disableTicks: true,
        valueFormatter: (value: number) => fmt.eps(value),
        tickLabelStyle: { fontSize: 10, fill: theme.palette.text.secondary },
        width: 44,
      }]}
      series={[
        {
          id: 'actual',
          data: actual,
          label: '실측',
          color: theme.palette.success.main,
          area: true,
          showMark: false,
          curve: 'monotoneX',
          valueFormatter: (value) => fmt.int(value ?? 0),
        },
        {
          // 목표선을 뒤에 둬야 면적 위로 그려져 가려지지 않는다.
          id: 'target',
          data: target,
          label: '목표',
          color: theme.palette.text.secondary,
          showMark: false,
          curve: 'monotoneX',
          valueFormatter: (value) => (value === null ? '무제한' : fmt.int(value)),
        },
      ]}
      grid={{ horizontal: true }}
      hideLegend
      skipAnimation
      sx={{
        // 면적은 기본값이 불투명 + brightness 필터라 그대로 두면 판이 통째로 칠해진다.
        '& .MuiLineChart-area': { fillOpacity: 0.14, filter: 'none' },
        '& .MuiLineChart-line[data-series-id="target"]': { strokeWidth: 1.5, strokeDasharray: '5 4' },
        '& .MuiChartsGrid-line': { stroke: theme.palette.divider, strokeDasharray: '3 3' },
      }}
    />
  );
}
