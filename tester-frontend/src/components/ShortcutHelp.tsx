import Box from '@mui/material/Box';
import Dialog from '@mui/material/Dialog';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

import { MONO } from '../theme';

/** 단축키 목록. 입력칸에 글자를 치는 중이거나 대화상자가 열려 있으면 한 글자 단축키는 동작하지 않는다. */
const GROUPS: { title: string; keys: [string, string][] }[] = [
  { title: '어디서나', keys: [['1 · 2 · 3 · 4', '전송 콘솔 · 템플릿 · 전송 대상 · 라이센스로 이동'], ['?', '이 목록 열기']] },
  { title: '전송 콘솔', keys: [['N', '새 전송 작업']] },
  { title: '템플릿', keys: [['N', '새 로그 종류'], ['Ctrl / ⌘ + S', '저장'], ['${', '함수 자동완성 (편집기 안에서)']] },
  { title: '전송 대상', keys: [['N', '대상 추가'], ['/', '검색칸으로'], ['Esc', '검색어 지우기 (검색칸에서)']] },
];

export function ShortcutHelp({ onClose }: { onClose: () => void }) {
  return (
    <Dialog open onClose={onClose} maxWidth="xs">
      <DialogTitle>단축키</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {GROUPS.map((group) => (
            <Box key={group.title}>
              <Typography variant="caption" color="textSecondary" sx={{ fontWeight: 700 }}>{group.title}</Typography>
              {group.keys.map(([key, desc]) => (
                <Stack key={key} direction="row" spacing={2} sx={{ alignItems: 'center', py: 0.4 }}>
                  <Box component="kbd" sx={{
                    fontFamily: MONO, fontSize: '0.75rem', px: 0.75, py: 0.1, borderRadius: 0.75,
                    border: 1, borderColor: 'divider', bgcolor: 'action.hover', minWidth: 104, textAlign: 'center',
                  }}>
                    {key}
                  </Box>
                  <Typography variant="body2">{desc}</Typography>
                </Stack>
              ))}
            </Box>
          ))}
          <Typography variant="caption" color="textSecondary">
            글자를 입력하는 중이거나 대화상자가 열려 있을 때는 한 글자 단축키가 동작하지 않습니다.
          </Typography>
        </Stack>
      </DialogContent>
    </Dialog>
  );
}
