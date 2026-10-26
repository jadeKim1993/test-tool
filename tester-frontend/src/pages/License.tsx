import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';

import VerifiedUserOutlinedIcon from '@mui/icons-material/VerifiedUserOutlined';

import { EmptyState } from '../components/common';

/** 라이센스 관리. 메뉴 자리만 먼저 잡아 두었다 — 기능은 아직 구현하지 않았다. */
export default function License() {
  return (
    <>
      <Typography variant="h6" sx={{ mb: 2 }}>라이센스</Typography>
      <Paper variant="outlined">
        <EmptyState icon={<VerifiedUserOutlinedIcon />} title="준비 중입니다"
                    hint="라이센스 관리 기능은 곧 제공될 예정입니다." />
      </Paper>
    </>
  );
}
