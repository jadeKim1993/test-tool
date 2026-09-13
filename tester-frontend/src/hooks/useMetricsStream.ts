import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { JobStatus } from '../api/types';

/** 작업별 최근 샘플 보관 개수(그래프 5분치). */
const MAX_POINTS = 300;

export interface MetricsState {
  /** 작업별 최신 상태. */
  latest: Record<number, JobStatus>;
  /** 작업별 시계열(그래프용). */
  series: Record<number, JobStatus[]>;
  connected: boolean;
}

/**
 * 전송 상태 SSE 구독.
 * <p>EventSource 는 헤더를 못 보내므로 먼저 티켓을 받아 쿼리로 붙인다.
 * 끊기면 지수 백오프로 재구독한다.
 */
export function useMetricsStream(enabled: boolean): MetricsState {
  const [state, setState] = useState<MetricsState>({ latest: {}, series: {}, connected: false });
  const sourceRef = useRef<EventSource | null>(null);
  const retryRef = useRef(1000);
  const timerRef = useRef<number | null>(null);
  const closedRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;
    closedRef.current = false;

    const apply = (jobs: JobStatus[]) => {
      if (jobs.length === 0) return;
      setState((prev) => {
        const latest = { ...prev.latest };
        const series = { ...prev.series };
        for (const job of jobs) {
          latest[job.jobId] = job;
          const points = series[job.jobId] ? [...series[job.jobId], job] : [job];
          series[job.jobId] = points.length > MAX_POINTS ? points.slice(-MAX_POINTS) : points;
        }
        return { latest, series, connected: true };
      });
    };

    const connect = async () => {
      if (closedRef.current) return;
      try {
        const { ticket } = await api.streamTicket();
        const source = new EventSource(`/api/stream/metrics?ticket=${encodeURIComponent(ticket)}`);
        sourceRef.current = source;

        source.addEventListener('backfill', (event) => {
          const payload = JSON.parse((event as MessageEvent).data);
          apply(payload.jobs ?? []);
          retryRef.current = 1000;
        });
        source.addEventListener('metrics', (event) => {
          const payload = JSON.parse((event as MessageEvent).data);
          apply(payload.jobs ?? []);
        });
        source.onopen = () => {
          retryRef.current = 1000;
          setState((prev) => ({ ...prev, connected: true }));
        };
        source.onerror = () => {
          source.close();
          sourceRef.current = null;
          setState((prev) => ({ ...prev, connected: false }));
          if (!closedRef.current) {
            timerRef.current = window.setTimeout(connect, retryRef.current);
            retryRef.current = Math.min(retryRef.current * 2, 15000);
          }
        };
      } catch {
        if (!closedRef.current) {
          timerRef.current = window.setTimeout(connect, retryRef.current);
          retryRef.current = Math.min(retryRef.current * 2, 15000);
        }
      }
    };

    connect();
    return () => {
      closedRef.current = true;
      if (timerRef.current) window.clearTimeout(timerRef.current);
      sourceRef.current?.close();
      sourceRef.current = null;
    };
  }, [enabled]);

  return state;
}
