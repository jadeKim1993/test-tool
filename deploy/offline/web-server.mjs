// 테스트 도구 화면 서버 (오프라인 설치용).
//
// Docker 배포에서 nginx(tester-frontend/nginx.conf)가 하던 일을 Node 로 한다:
//   - 화면 정적 파일 제공. 해시가 붙은 /assets/ 는 1년 캐시, 나머지는 no-cache
//   - 없는 경로는 index.html (SPA 라우터가 처리 — /templates 에서 새로고침해도 열린다)
//   - /api/ · /actuator/ 는 백엔드(127.0.0.1:TESTER_API_PORT)로 프록시. SSE 는 버퍼링 없이 흘려보낸다
//   - /health 는 이 서버 자체의 상태 확인용
//
// npm 패키지를 하나도 쓰지 않는다 — 인터넷이 없는 서버에서 npm install 없이 바로 돈다.
//
// 환경변수 (설치 시 /opt/tester/conf/tester.conf 에서 읽힌다)
//   TESTER_WEB_PORT  기본 80
//   TESTER_WEB_BIND  기본 0.0.0.0
//   TESTER_API_PORT  기본 8080
//   TESTER_WEB_ROOT  기본 이 파일 기준 ../web

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const PORT = Number(process.env.TESTER_WEB_PORT || 80);
const BIND = process.env.TESTER_WEB_BIND || '0.0.0.0';
const API_PORT = Number(process.env.TESTER_API_PORT || 8080);
const ROOT = path.resolve(process.env.TESTER_WEB_ROOT
  || path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web'));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
};
const COMPRESSIBLE = new Set(['.html', '.js', '.mjs', '.css', '.json', '.svg', '.txt', '.map']);
const HOP_BY_HOP = ['connection', 'keep-alive', 'proxy-connection', 'upgrade'];

// 백엔드 커넥션을 재사용한다. 화면이 API 를 자주 부르므로 매번 TCP 연결을 새로 맺지 않는다.
const upstreamAgent = new http.Agent({ keepAlive: true, maxSockets: 64 });

function proxy(req, res) {
  const headers = { ...req.headers };
  for (const name of HOP_BY_HOP) delete headers[name];
  const clientIp = req.socket.remoteAddress ?? '';
  headers['x-real-ip'] = clientIp;
  headers['x-forwarded-for'] = headers['x-forwarded-for'] ? `${headers['x-forwarded-for']}, ${clientIp}` : clientIp;

  const upstream = http.request({
    host: '127.0.0.1', port: API_PORT, method: req.method, path: req.url, headers, agent: upstreamAgent,
  }, (upstreamRes) => {
    const responseHeaders = { ...upstreamRes.headers };
    for (const name of HOP_BY_HOP) delete responseHeaders[name];
    res.writeHead(upstreamRes.statusCode ?? 502, responseHeaders);
    // SSE(/api/stream/*) 도 같은 경로다. pipe 는 받는 즉시 쓰므로 nginx 의 proxy_buffering off 와 같다.
    upstreamRes.pipe(res);
  });

  upstream.on('error', (error) => {
    if (res.headersSent) {
      res.destroy();
      return;
    }
    res.writeHead(502, { 'content-type': 'application/problem+json; charset=utf-8' });
    res.end(JSON.stringify({
      status: 502,
      title: 'Bad Gateway',
      detail: `백엔드(127.0.0.1:${API_PORT})에 연결할 수 없습니다: ${error.code ?? error.message}`,
    }));
  });
  // 브라우저가 SSE 를 닫으면 백엔드 쪽 연결도 끊어 스트림을 정리한다.
  res.on('close', () => upstream.destroy());
  req.pipe(upstream);
}

/** 요청 경로를 ROOT 안의 실제 파일로 바꾼다. ROOT 밖을 가리키면(../) null. */
function resolveFile(urlPath) {
  let decoded;
  try {
    decoded = decodeURIComponent(urlPath);
  } catch {
    return null;
  }
  const file = path.resolve(ROOT, '.' + path.posix.normalize('/' + decoded));
  if (file !== ROOT && !file.startsWith(ROOT + path.sep)) return null;
  try {
    return fs.statSync(file).isFile() ? file : null;
  } catch {
    return null;
  }
}

function sendFile(req, res, file, cacheControl) {
  const ext = path.extname(file).toLowerCase();
  const size = fs.statSync(file).size;
  const headers = {
    'content-type': MIME[ext] ?? 'application/octet-stream',
    'cache-control': cacheControl,
    'x-content-type-options': 'nosniff',
  };
  const gzip = COMPRESSIBLE.has(ext) && size > 1024 && /\bgzip\b/.test(req.headers['accept-encoding'] ?? '');
  if (COMPRESSIBLE.has(ext)) headers.vary = 'Accept-Encoding';
  if (gzip) headers['content-encoding'] = 'gzip';
  else headers['content-length'] = size;

  res.writeHead(200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  const stream = fs.createReadStream(file);
  stream.on('error', () => res.destroy());
  (gzip ? stream.pipe(zlib.createGzip()) : stream).pipe(res);
}

function serveStatic(req, res, urlPath) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { allow: 'GET, HEAD' });
    res.end();
    return;
  }
  const isAsset = urlPath.startsWith('/assets/');
  const file = resolveFile(urlPath === '/' ? '/index.html' : urlPath);
  if (file) {
    sendFile(req, res, file, isAsset ? 'public, max-age=31536000, immutable' : 'no-cache');
    return;
  }
  // 해시 자산이 없으면 배포가 섞인 것이다. index.html 을 주면 브라우저가 JS 자리에 HTML 을 받아 깨진다.
  if (isAsset) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Not Found');
    return;
  }
  const index = resolveFile('/index.html');
  if (!index) {
    res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`화면 파일이 없습니다: ${ROOT}/index.html`);
    return;
  }
  sendFile(req, res, index, 'no-cache');
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url ?? '/').split('?', 1)[0];
  if (urlPath.startsWith('/api/') || urlPath.startsWith('/actuator/')) {
    proxy(req, res);
  } else if (urlPath === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
    res.end('ok');
  } else {
    serveStatic(req, res, urlPath);
  }
});

// SSE 는 몇 시간씩 열려 있다. 응답 쪽 타임아웃으로 끊기지 않게 한다.
server.requestTimeout = 0;
server.timeout = 0;
server.keepAliveTimeout = 65_000;

server.on('error', (error) => {
  if (error.code === 'EADDRINUSE') console.error(`포트 ${PORT} 를 이미 다른 프로그램이 쓰고 있습니다.`);
  else if (error.code === 'EACCES') console.error(`포트 ${PORT} 를 열 권한이 없습니다(1024 미만은 권한 필요).`);
  else console.error(error);
  process.exit(1);
});

server.listen(PORT, BIND, () => {
  console.log(`테스트 도구 화면 서버: http://${BIND}:${PORT}  (화면 ${ROOT}, API → 127.0.0.1:${API_PORT})`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    // 열린 SSE 때문에 close 가 끝나지 않을 수 있다. 오래 기다리지 않는다.
    setTimeout(() => process.exit(0), 3000).unref();
  });
}
