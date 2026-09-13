import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider, createTheme, alpha, type PaletteMode, type Theme } from '@mui/material/styles';

/** 숫자·로그·주소는 전부 고정폭으로 읽는다. 자릿수가 흔들리면 비교가 안 된다. */
export const MONO =
  'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace';

/**
 * 본문 글꼴은 번들에 폰트를 넣지 않고 OS 글꼴을 쓴다.
 * 폐쇄망이라 웹폰트 CDN 을 쓸 수 없고, 한글은 OS 기본 글꼴이 가장 잘 읽힌다.
 */
const SANS = [
  '-apple-system', 'BlinkMacSystemFont', '"Apple SD Gothic Neo"', '"Segoe UI"',
  '"Malgun Gothic"', '"Noto Sans KR"', 'Roboto', '"Helvetica Neue"', 'Arial', 'sans-serif',
].join(',');

const STORAGE_KEY = 'tester.theme';

function buildTheme(mode: PaletteMode): Theme {
  const dark = mode === 'dark';

  const theme = createTheme({
    palette: {
      mode,
      primary: { main: dark ? '#5b9dff' : '#1f6feb' },
      success: { main: dark ? '#3fb950' : '#1a7f37' },
      warning: { main: dark ? '#e3b341' : '#9a6700' },
      error: { main: dark ? '#f85149' : '#cf222e' },
      info: { main: dark ? '#58b8f0' : '#0969da' },
      background: {
        default: dark ? '#0d1117' : '#f4f6f8',
        paper: dark ? '#161b22' : '#ffffff',
      },
      divider: dark ? 'rgba(240,246,252,0.12)' : 'rgba(31,35,40,0.14)',
      text: {
        primary: dark ? '#e6edf3' : '#1f2328',
        secondary: dark ? '#9198a1' : '#59636e',
      },
    },
    shape: { borderRadius: 8 },
    typography: {
      fontFamily: SANS,
      fontSize: 13.5,
      h6: { fontSize: '1rem', fontWeight: 600 },
      subtitle2: { fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
      caption: { fontSize: '0.72rem' },
    },
  });

  return createTheme(theme, {
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          // 값이 긴 표/로그를 자주 스크롤하므로 스크롤바를 얇게 눌러 둔다.
          '*::-webkit-scrollbar': { width: 10, height: 10 },
          '*::-webkit-scrollbar-thumb': {
            backgroundColor: alpha(theme.palette.text.primary, 0.22),
            borderRadius: 8,
            border: `2px solid ${theme.palette.background.default}`,
          },
          '*::-webkit-scrollbar-thumb:hover': {
            backgroundColor: alpha(theme.palette.text.primary, 0.35),
          },
          '*::-webkit-scrollbar-track': { backgroundColor: 'transparent' },
          body: { overflowY: 'scroll' },
        },
      },
      MuiPaper: {
        styleOverrides: {
          // 다크 모드의 elevation 오버레이는 카드가 많아지면 탁해 보인다. 테두리로 구분한다.
          root: { backgroundImage: 'none' },
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
      },
      MuiButton: {
        defaultProps: { disableElevation: true, size: 'small' },
      },
      MuiButtonGroup: {
        defaultProps: { disableElevation: true, size: 'small' },
      },
      MuiTextField: {
        defaultProps: { size: 'small' },
      },
      MuiFormControl: {
        defaultProps: { size: 'small' },
      },
      MuiToggleButtonGroup: {
        defaultProps: { size: 'small' },
      },
      MuiToggleButton: {
        styleOverrides: {
          root: { textTransform: 'none', fontFamily: MONO, paddingInline: 10 },
        },
      },
      MuiChip: {
        defaultProps: { size: 'small' },
        styleOverrides: { root: { fontWeight: 600 } },
      },
      MuiTooltip: {
        defaultProps: { arrow: true, enterDelay: 400 },
      },
      MuiTableCell: {
        styleOverrides: {
          root: { paddingBlock: 6, paddingInline: 10 },
          head: {
            fontSize: '0.72rem',
            fontWeight: 600,
            color: theme.palette.text.secondary,
            whiteSpace: 'nowrap',
          },
        },
      },
      MuiDialog: {
        defaultProps: { fullWidth: true },
      },
      MuiAlert: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: { root: { alignItems: 'center' } },
      },
      MuiListItemButton: {
        styleOverrides: { root: { borderRadius: 6 } },
      },
      MuiTab: {
        styleOverrides: { root: { textTransform: 'none', fontWeight: 600, minHeight: 44 } },
      },
    },
  });
}

interface ColorModeValue {
  mode: PaletteMode;
  toggle: () => void;
}

const ColorModeContext = createContext<ColorModeValue>({ mode: 'dark', toggle: () => {} });
export const useColorMode = () => useContext(ColorModeContext);

/** 야간 운영을 고려해 다크를 기본값으로 두고, 선택은 브라우저에 저장한다. */
export function AppThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<PaletteMode>(() => {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
    } catch {
      return 'dark';
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      /* 저장 실패는 무시한다. 테마는 편의 기능이다. */
    }
    document.documentElement.style.colorScheme = mode;
  }, [mode]);

  const value = useMemo<ColorModeValue>(
    () => ({ mode, toggle: () => setMode((prev) => (prev === 'dark' ? 'light' : 'dark')) }),
    [mode],
  );
  const theme = useMemo(() => buildTheme(mode), [mode]);

  return (
    <ColorModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  );
}
