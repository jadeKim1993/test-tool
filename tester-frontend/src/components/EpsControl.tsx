import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { MONO } from '../theme';
import { fmt } from './common';

const PRESETS = [1_000, 10_000, 50_000, 100_000, 500_000];

/**
 * 속도 입력. 프리셋 버튼과 직접 입력을 나란히 둔다.
 * <p>부하 테스트에서 가장 자주 만지는 값이라 한 번에 보이는 게 중요하다. 0 은 "무제한"이다.
 */
export function EpsControl({ value, onChange, disabled }: {
  value: number;
  onChange: (eps: number) => void;
  disabled?: boolean;
}) {
  return (
    <Stack spacing={0.75} sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField
          type="number"
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(Math.max(0, Number(e.target.value) || 0))}
          slotProps={{
            input: { startAdornment: <InputAdornment position="start">EPS</InputAdornment> },
            htmlInput: { min: 0, style: { textAlign: 'right', fontFamily: MONO } },
          }}
          sx={{ width: 170, flexShrink: 0 }}
        />

        <ToggleButtonGroup
          exclusive
          disabled={disabled}
          value={value}
          onChange={(_, next) => {
            // 이미 선택된 버튼을 다시 누르면 null 이 온다. 속도를 지울 수는 없으므로 무시한다.
            if (next !== null) onChange(next as number);
          }}
        >
          {PRESETS.map((preset) => (
            <ToggleButton key={preset} value={preset}>{fmt.eps(preset)}</ToggleButton>
          ))}
          <ToggleButton value={0} color="error" title="속도 제한 없이 낼 수 있는 최대치로 전송합니다">
            MAX
          </ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      {value === 0 && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', color: 'error.main' }}>
          <WarningAmberIcon sx={{ fontSize: 15 }} />
          <Typography variant="caption">
            무제한 모드: 장비가 낼 수 있는 최대 속도로 전송합니다.
          </Typography>
        </Stack>
      )}
    </Stack>
  );
}
