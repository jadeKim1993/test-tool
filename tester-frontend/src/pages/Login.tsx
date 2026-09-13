import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Avatar from '@mui/material/Avatar';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import InputAdornment from '@mui/material/InputAdornment';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

import GraphicEqIcon from '@mui/icons-material/GraphicEq';
import AlternateEmailIcon from '@mui/icons-material/AlternateEmail';
import LoginIcon from '@mui/icons-material/Login';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import PersonAddAltIcon from '@mui/icons-material/PersonAddAlt';
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined';

import { api } from '../api/client';
import { useAuth } from '../App';

/**
 * 아이디 한 칸으로 끝나는 로그인.
 * <p>입력 → [확인] 이면 계정이 있으면 바로 로그인, 없으면 가입 버튼으로 바뀐다.
 * 비밀번호가 없으므로 화면을 둘로 나눌 이유가 없다.
 */
export default function Login() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState('');
  const [checked, setChecked] = useState<{ username: string; exists: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 백엔드 AuthDtos.USERNAME_PATTERN 과 같은 규칙 — 한쪽만 고치면 안 된다.
  const trimmed = username.trim();
  const valid = trimmed.length >= 3 && trimmed.length <= 100
    && /^[a-zA-Z0-9._%+-]+(@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,})?$/.test(trimmed);
  const isEmail = trimmed.includes('@');
  const isSignup = checked !== null && !checked.exists;

  const check = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await api.exists(trimmed.toLowerCase());
      setChecked(result);
      // 이미 있는 계정이면 한 번 더 누르게 하지 않고 바로 로그인시킨다.
      if (result.exists) await submit(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const submit = async (exists: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const name = trimmed.toLowerCase();
      const response = exists ? await api.login(name) : await api.signup(name);
      signIn(response.user, response.accessToken);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', p: 2,
    }}>
      <Box sx={{ width: '100%', maxWidth: 400 }}>
        <Stack spacing={1} sx={{ alignItems: 'center', mb: 3 }}>
          <Avatar sx={{ bgcolor: 'primary.main', width: 48, height: 48 }}>
            <GraphicEqIcon />
          </Avatar>
          <Typography variant="h6" sx={{ fontWeight: 700 }}>테스트 도구</Typography>
          <Typography variant="body2" color="textSecondary">수집 파이프라인 부하 테스트</Typography>
        </Stack>

        {/* form 으로 감싸야 입력 칸에서 Enter 를 눌렀을 때 브라우저가 제출로 처리한다. */}
        <Paper variant="outlined" component="form" sx={{ p: 2.5 }}
               onSubmit={(e: React.FormEvent) => {
                 e.preventDefault();
                 if (!valid || busy) return;
                 if (isSignup) submit(false);
                 else check();
               }}>
          <Stack spacing={2}>
            {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

            <TextField
              label="아이디 또는 이메일"
              placeholder="아이디 또는 이메일 입력"
              autoFocus
              autoComplete="off"
              fullWidth
              value={username}
              error={username.length > 0 && !valid}
              helperText={
                username.length > 0 && !valid
                  ? (isEmail
                    ? '이메일 형식이 올바르지 않습니다.'
                    : '영문·숫자와 . _ % + - 를 사용해 3자 이상 입력하세요.')
                  : ' '
              }
              onChange={(e) => {
                setUsername(e.target.value);
                setChecked(null);
              }}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      {isEmail ? <AlternateEmailIcon fontSize="small" /> : <PersonOutlineIcon fontSize="small" />}
                    </InputAdornment>
                  ),
                },
              }}
            />

            {isSignup && (
              <Alert severity="info" icon={<PersonAddAltIcon fontSize="small" />}>
                <strong>{checked.username}</strong> 은(는) 사용 가능한 아이디입니다. 가입하고 바로 시작하세요.
              </Alert>
            )}

            <Button
              type="submit"
              variant="contained"
              size="large"
              fullWidth
              disabled={!valid || busy}
              startIcon={busy
                ? <CircularProgress size={16} color="inherit" />
                : isSignup ? <PersonAddAltIcon /> : <LoginIcon />}
            >
              {isSignup ? '가입하고 시작' : '확인'}
            </Button>
          </Stack>
        </Paper>

        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', justifyContent: 'center', mt: 2 }}>
          <ShieldOutlinedIcon sx={{ fontSize: 15, color: 'text.secondary' }} />
          <Typography variant="caption" color="textSecondary">
            비밀번호가 없는 내부망 전용 도구입니다.
          </Typography>
        </Stack>
      </Box>
    </Box>
  );
}
