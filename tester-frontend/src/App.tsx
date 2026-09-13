import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';

import AppBar from '@mui/material/AppBar';
import Avatar from '@mui/material/Avatar';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';

import DarkModeIcon from '@mui/icons-material/DarkModeOutlined';
import GraphicEqIcon from '@mui/icons-material/GraphicEq';
import LightModeIcon from '@mui/icons-material/LightModeOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import WifiIcon from '@mui/icons-material/Wifi';
import WifiOffIcon from '@mui/icons-material/WifiOff';

import { api, getToken, setToken, setUnauthorizedHandler } from './api/client';
import type { User } from './api/types';
import { useMetricsStream } from './hooks/useMetricsStream';
import { PulseDot, fmt } from './components/common';
import { useColorMode } from './theme';
import Login from './pages/Login';
import SendConsole from './pages/SendConsole';
import Templates from './pages/Templates';
import Targets from './pages/Targets';

interface AuthContextValue {
  user: User | null;
  signIn: (user: User, token: string) => void;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue>({ user: null, signIn: () => {}, signOut: () => {} });
export const useAuth = () => useContext(AuthContext);

/** 전체 실행 상태를 헤더 배지와 각 화면이 공유한다. */
const MetricsContext = createContext<ReturnType<typeof useMetricsStream>>({
  latest: {}, series: {}, connected: false,
});
export const useMetrics = () => useContext(MetricsContext);

const NAV = [
  { path: '/send', label: '전송 콘솔' },
  { path: '/templates', label: '템플릿' },
  { path: '/targets', label: '전송 대상' },
];

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [booting, setBooting] = useState(true);
  const navigate = useNavigate();

  const signOut = useCallback(() => {
    setToken(null);
    setUser(null);
    navigate('/login', { replace: true });
  }, [navigate]);

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null));
  }, []);

  // 새로고침해도 세션이 유지되도록 토큰으로 사용자 정보를 복원한다.
  useEffect(() => {
    if (!getToken()) {
      setBooting(false);
      return;
    }
    api.me()
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setBooting(false));
  }, []);

  const signIn = useCallback((next: User, token: string) => {
    setToken(token);
    setUser(next);
  }, []);

  const metrics = useMetricsStream(user !== null);
  const auth = useMemo(() => ({ user, signIn, signOut }), [user, signIn, signOut]);

  if (booting) {
    return (
      <Stack direction="row" spacing={1.5}
             sx={{ alignItems: 'center', justifyContent: 'center', height: '100vh', color: 'text.secondary' }}>
        <CircularProgress size={18} />
        <Typography variant="body2">세션 확인 중</Typography>
      </Stack>
    );
  }

  return (
    <AuthContext.Provider value={auth}>
      <MetricsContext.Provider value={metrics}>
        {user ? <Shell /> : (
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        )}
      </MetricsContext.Provider>
    </AuthContext.Provider>
  );
}

function Shell() {
  const location = useLocation();
  const current = NAV.find((item) => location.pathname.startsWith(item.path))?.path ?? '/send';

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="sticky" color="default" elevation={0}
              sx={{ bgcolor: 'background.paper', borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar variant="dense" sx={{ gap: { xs: 1, md: 2 }, minHeight: 52 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
            <GraphicEqIcon color="primary" />
            <Typography variant="subtitle1"
                        sx={{ fontWeight: 700, display: { xs: 'none', sm: 'block' } }}>
              테스트 도구
            </Typography>
          </Stack>

          <Tabs value={current} variant="scrollable" scrollButtons={false} sx={{ minHeight: 52 }}>
            {NAV.map((item) => (
              <Tab key={item.path} value={item.path} label={item.label}
                   component={Link} to={item.path} sx={{ minHeight: 52 }} />
            ))}
          </Tabs>

          <Box sx={{ flex: 1 }} />
          <LiveStatus />
          <UserMenu />
        </Toolbar>
      </AppBar>

      <Box component="main" sx={{ flex: 1, px: { xs: 1.5, md: 2.5 }, py: 2, pb: 6 }}>
        <Routes>
          <Route path="/send" element={<SendConsole />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/targets" element={<Targets />} />
          <Route path="*" element={<Navigate to="/send" replace />} />
        </Routes>
      </Box>
    </Box>
  );
}

/** 어느 화면에 있든 "지금 얼마나 보내고 있는지"가 보여야 한다. */
function LiveStatus() {
  const { latest, connected } = useMetrics();
  const running = Object.values(latest).filter((status) => status.state === 'RUNNING');
  const totalEps = running.reduce((sum, status) => sum + status.eps, 0);
  const totalDropped = running.reduce((sum, status) => sum + status.dropped, 0);

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      {running.length > 0 && (
        <Chip
          color="success"
          sx={{ display: { xs: 'none', md: 'flex' } }}
          label={
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
              <PulseDot />
              <span>{running.length}개 · {fmt.int(totalEps)} EPS</span>
            </Stack>
          }
        />
      )}
      {totalDropped > 0 && (
        <Tooltip title="수신측이 속도를 못 따라가 버린 건수">
          <Chip color="warning" variant="outlined" label={`버림 ${fmt.int(totalDropped)}`}
                sx={{ display: { xs: 'none', md: 'flex' } }} />
        </Tooltip>
      )}
      <Tooltip title={connected ? '실시간 스트림 연결됨' : '실시간 스트림 끊김 (자동 재연결 중)'}>
        <Box sx={{ display: 'flex', color: connected ? 'text.secondary' : 'warning.main' }}>
          {connected ? <WifiIcon fontSize="small" /> : <WifiOffIcon fontSize="small" />}
        </Box>
      </Tooltip>
      <ThemeToggle />
    </Stack>
  );
}

function ThemeToggle() {
  const { mode, toggle } = useColorMode();
  return (
    <Tooltip title={mode === 'dark' ? '라이트 모드로' : '다크 모드로'}>
      <IconButton size="small" onClick={toggle}>
        {mode === 'dark' ? <DarkModeIcon fontSize="small" /> : <LightModeIcon fontSize="small" />}
      </IconButton>
    </Tooltip>
  );
}

function UserMenu() {
  const { user, signOut } = useAuth();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  return (
    <>
      {/* 이메일로 가입하면 주소 전체가 길어지므로 표시명(@ 앞부분)을 쓴다. */}
      <Tooltip title={user?.username ?? ''}>
        <IconButton size="small" onClick={(e) => setAnchor(e.currentTarget)}>
          <Avatar sx={{ width: 26, height: 26, fontSize: '0.78rem', bgcolor: 'primary.main' }}>
            {(user?.displayName || user?.username || '?').charAt(0).toUpperCase()}
          </Avatar>
        </IconButton>
      </Tooltip>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            transformOrigin={{ vertical: 'top', horizontal: 'right' }}>
        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="subtitle2">{user?.displayName}</Typography>
          <Typography variant="caption" color="textSecondary">{user?.username}</Typography>
        </Box>
        <Divider />
        <MenuItem onClick={() => { setAnchor(null); signOut(); }}>
          <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>
          로그아웃
        </MenuItem>
      </Menu>
    </>
  );
}
