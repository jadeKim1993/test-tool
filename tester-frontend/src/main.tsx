import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';

import App from './App';
import { AppThemeProvider } from './theme';

// 폐쇄망 전제: 글꼴·아이콘까지 전부 번들에 들어간다. 외부 CDN 을 참조하지 않는다.
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppThemeProvider>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </AppThemeProvider>
  </React.StrictMode>,
);
