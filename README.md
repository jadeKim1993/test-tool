# 테스트 도구 (Test Tool)

수집 파이프라인(Fluent-bit / Logstash / Kafka / OpenSearch 등) 부하 테스트용 웹 도구.
사용자가 정의한 템플릿으로 로그를 생성해 TCP/UDP/TLS 로 전송하고, 전송 상태를 실시간으로 보여준다.
로그 전송은 첫 번째 테스트 기능이며, 이후 다른 종류의 테스트 기능도 이 도구 위에 추가될 예정이다.

설계 문서: [docs/design.md](docs/design.md)

## 실측 성능 (Apple M-series, 8코어, 루프백)

| 항목 | 결과 |
|---|---|
| TCP 10만 EPS | 100,042 EPS · 수신 = 전송 · 유실 0 · 버림 0 |
| UDP 10만 EPS | 100,050 EPS · 유실률 0.00% |
| TCP 최대 속도 | **1,145만 EPS (15.1 Gbps)**, 워커 7개 |
| 레이트 정확도 | 100→100.3 / 10,000→10,000 / 50,000→50,000 (오차 ±0.3%) |
| 렌더링 | 262 ns/건 (단일 스레드 382만 EPS), 로그 219 B |

직접 재보려면:

```bash
./gradlew perf
```

## 빌드

```bash
./gradlew build          # 백엔드 jar + 프론트엔드 dist/ + 테스트
```

성능 벤치마크는 `build` 에 포함되지 않는다. 처리량을 벽시계로 재는 테스트라
병렬 빌드에서 다른 모듈과 CPU 를 나눠 쓰면 실제 성능과 무관하게 실패하기 때문이다.
따로 돌린다:

```bash
./gradlew perf
```

산출물
- `tester-api/build/libs/tester-api.jar` — 백엔드 실행 파일 (Java 21+)
- `tester-frontend/build/distributions/frontend.zip` — nginx 정적 배포본
- `deploy/nginx.conf` — 리버스 프록시 설정 (직접 설치용. Docker 는 아래 참고)

> 프론트엔드 빌드는 시스템에 설치된 `npm` 을 사용한다(빌드 장비에 Node 22+ 필요).
> 폐쇄망에서는 사내 npm 미러를 쓰거나 `node_modules` 를 미리 반입한 뒤 빌드한다.
> Gradle/npm 설치 없이 바로 띄우려면 [Docker 배포](#docker-배포)를 쓴다.

## 실행

```bash
export TESTER_JWT_SECRET='32자-이상의-임의-문자열을-넣으세요'
java -jar tester-api/build/libs/tester-api.jar
```

- 기본 포트 8080, DB 는 `./data/tester.mv.db` (H2 파일). 재시작해도 설정이 유지된다.
- PostgreSQL 로 바꾸려면 `--spring.profiles.active=postgres` 와 datasource 설정을 준다.
- `TESTER_JWT_SECRET` 을 주지 않으면 기동할 때마다 임시 키를 만든다(재시작 시 로그인 무효화).

### 개발 모드

백엔드와 프론트엔드를 한 번에 띄운다. 브라우저는 **http://localhost:5173** 으로 접속한다
(`/api` 는 Vite 가 8080 으로 프록시한다). Ctrl+C 로 둘 다 멈춘다.

```bash
./gradlew dev
```

따로 띄우고 싶을 때:

```bash
./gradlew backend
```

```bash
./gradlew frontend
```

| 태스크 | 하는 일 |
|---|---|
| `dev` | 백엔드(:8080) + 프론트엔드(:5173) 동시 실행 |
| `backend` | 백엔드만 (`:tester-api:bootRun`) |
| `frontend` | Vite 개발 서버만 (`:tester-frontend:npmDev`) |

> `dev` 는 끝나지 않는 프로세스 둘을 동시에 돌리므로 Gradle 병렬 실행이 필요하다.
> `gradle.properties` 에 `org.gradle.parallel=true` 를 넣어 두었으니 그냥 실행하면 된다.
> 프론트엔드만 고치는 중이라면 `backend` 를 한 번 띄워 두고 `frontend` 만 재시작하는 편이 빠르다.

## 배포 방법

| 환경 | 방법 |
|---|---|
| **인터넷망 (기본)** | Docker — 아래 §Docker 배포 |
| **내부망, 인터넷·Docker 없음** | 오프라인 원클릭 설치본 — 아래 §내부망 오프라인 설치 |
| 내부망, 사내 미러·레지스트리·Docker 있음 | 내부 레지스트리(192.168.70.114) 이미지 — 아래 §내부망 개발·Docker 배포 |

## Docker 배포

백엔드와 프론트엔드를 각각 이미지로 빌드해 컨테이너 둘로 띄운다. 프론트엔드 컨테이너의 nginx 가
정적 파일을 서빙하면서 `/api/*` 요청을 백엔드 컨테이너로 프록시한다(브라우저는 프론트엔드
포트 하나만 접근하면 된다).

```bash
cp .env.example .env
# .env 를 열어 TESTER_JWT_SECRET 을 채운다 (예: openssl rand -base64 48)

./gradlew build              # 이미지에 담을 jar / dist 를 호스트에서 먼저 만든다
docker compose up -d --build
```

> **이미지 안에서 빌드하지 않는다.** 호스트에서 만든 `tester-api.jar` 와 `dist/` 를 담기만 한다.
> 그래서 이미지 빌드가 몇 초로 끝나고, 폐쇄망에서도 이미지 빌드용 Maven/npm 미러를 따로
> 챙길 필요가 없다(호스트 빌드가 이미 그 설정을 쓴다). 테스트한 산출물이 그대로 배포된다.

→ **http://localhost** 접속(포트는 `.env` 의 `FRONTEND_PORT` 로 바꿀 수 있다).
백엔드 API 를 직접 호출/디버깅하고 싶으면 `http://localhost:8080` 도 함께 열려 있다
(`BACKEND_PORT`). 실제 서비스 트래픽에는 필요 없는 포트라 막아도 앱은 정상 동작한다.

```bash
docker compose down          # 컨테이너만 제거, tester-data 볼륨(H2 DB)은 남는다
docker compose down -v       # 볼륨까지 삭제 — 데이터를 완전히 지울 때만
```

| 파일 | 역할 |
|---|---|
| `tester-api/Dockerfile` | 백엔드. 호스트에서 만든 `build/libs/tester-api.jar` 를 JRE 이미지에 담는다. 컨텍스트는 `tester-api/` |
| `tester-frontend/Dockerfile` | 프론트엔드. 호스트에서 만든 `dist/` 를 nginx 이미지에 담는다. 컨텍스트는 `tester-frontend/` |
| `tester-frontend/nginx.conf` | Docker 전용 nginx 설정. 백엔드를 compose 서비스명(`backend:8080`)으로 프록시한다. 컨테이너 없이 한 장비에 직접 설치할 때는 `deploy/nginx.conf`(백엔드가 `127.0.0.1:8080`)를 대신 쓴다 |
| `docker-compose.yml` | 두 이미지를 빌드해 함께 띄운다. `backend` 가 healthy 해질 때까지 `frontend` 시작을 미룬다 |
| `.env.example` | `TESTER_JWT_SECRET`, 포트 설정 템플릿. `.env` 로 복사해서 쓴다(`.gitignore` 에 이미 포함) |

**직접 이미지만 빌드하고 싶을 때**(compose 없이):

```bash
./gradlew :tester-api:bootJar :tester-frontend:npmBuild      # 산출물 먼저
docker build -t tester-api tester-api/
docker build -t tester-frontend tester-frontend/
```

**데이터 유지**: H2 파일 DB(`/app/data`)는 `tester-data` 라는 named volume 에 저장된다.
`docker compose down` 후 `up` 을 다시 해도(이미지를 다시 빌드해도) 카테고리·템플릿·전송
대상·작업 설정과 실행 이력이 그대로 남는다 — 컨테이너를 지우고 새로 만들어도 마찬가지다.
완전히 초기화하려면 `docker compose down -v` 로 볼륨까지 지운다.

**시간대**: 컨테이너 기본값은 UTC 라 그대로면 만든 로그의 시각이 9시간 늦게 찍힌다. compose 파일이 `TZ=Asia/Seoul`
을 기본으로 넣는다(다른 시간대는 `.env` 에 `TZ=...`).

**TESTER_JWT_SECRET 을 비워 두면**: 컨테이너가 뜰 때마다 임시 키를 새로 만든다는 경고와 함께
기동은 되지만, 재시작할 때마다 기존 로그인 세션이 전부 무효화된다. 운영에서는 반드시 채울 것.

> 이 도구는 비밀번호 없이 아이디만으로 로그인한다(§로그인). 컨테이너를 인터넷에 노출할
> 계획이라면 `tester-frontend/nginx.conf` 에 IP 제한을 추가하거나 앞단에 별도 인증을 두어야 한다.

## 내부망 오프라인 설치 (인터넷·Docker 없음)

인터넷이 되는 빌드 PC 에서 설치본을 만들어 내부망 리눅스 서버로 옮긴 뒤, 스크립트 한 번으로
**Java(JRE)·Node.js(npm 포함)를 호스트에 설치하고 애플리케이션을 서비스로 띄운다.** 서버에서는
아무것도 내려받지 않는다.

```bash
./gradlew offlineBundle      # 빌드 PC (인터넷 필요 — JRE·Node 를 처음 한 번 받는다)
# → build/distributions/tester-offline-<버전>-linux-x64.tar.gz  (약 170MB)
```

내부망 서버에서:

```bash
tar xzf tester-offline-0.0.1-SNAPSHOT-linux-x64.tar.gz
cd tester-offline-0.0.1-SNAPSHOT-linux-x64
sudo ./install.sh            # → http://<서버IP>  (화면 포트 변경: --web-port 8000)
```

| 구성 | Docker 배포 | 오프라인 설치 |
|---|---|---|
| 백엔드 | `tester-api` 컨테이너 | `tester-api` systemd 서비스 (`/opt/tester/jre` 의 Java) |
| 화면 | nginx 컨테이너 | `tester-web` systemd 서비스 (`/opt/tester/node` 의 Node.js, `web-server.mjs`) |
| `/api` 프록시 | nginx | 화면 서버가 백엔드(127.0.0.1:8080)로 넘김, SSE 포함 |

- 관리: `testerctl status` · `testerctl logs [api|web]` · `sudo testerctl restart`
- 설정 `/opt/tester/conf/tester.conf`(포트, JVM 옵션). JWT 서명키는 설치할 때 자동으로 만든다.
- 업그레이드는 새 설치본으로 `sudo ./install.sh` 를 다시 실행 — 설정과 데이터(`/opt/tester/data`)는 유지.
  제거는 `--uninstall`(데이터 남김) / `--uninstall --purge`.
- **설치 여부 확인**: `sudo ./install.sh --check`. systemd 에 등록돼 있지 않아도 찾는다 — systemd 서비스 →
  실행 중인 프로세스(`/proc` 의 `<경로>/app/tester-api.jar`·`web-server.mjs`) → `testerctl` 링크 →
  환경변수 `TESTER_HOME`(`/etc/profile.d`) → 설정 파일 순으로 보고 찾은 근거를 보여 준다. 다른 경로에 설치돼
  있으면 `install.sh` 가 그 경로를 업그레이드하고, `--prefix` 로 두 번째 설치를 시도하면 거부한다.
- `java`·`node`·`npm` 을 `/etc/profile.d` 로 PATH 에 추가한다(기존 설치가 있으면 그쪽이 우선, `--no-path` 로 끔).
- 요구 사항: 리눅스 x64, glibc 2.28 이상(RHEL/Rocky 8+, Ubuntu 20.04+). **CentOS 7 은 Node 24 가 돌지 않아 미지원.**
  systemd 가 없으면 `testerctl` 이 직접 프로세스를 띄운다(재부팅 시 자동 시작은 안 됨).
- 빌드 PC 도 인터넷이 안 되면 `-PjreUrl=` / `-PnodeUrl=` 로 내부 미러를 지정하거나({version} {os} {arch}
  치환, 로컬 파일 경로 가능), 인터넷망에서 받은 파일을 `.runtime-cache/` 에 같은 이름으로 넣어 둔다.
  버전은 `jreVersion=jdk-21.0.13+9`, `nodeVersion=24.22.1` 로 고정돼 있다. ARM 서버는 `-PbundleArch=aarch64`.
- 설치본 안의 `README.txt` 에 서버 담당자용 안내가 있다.

## 내부망 개발·Docker 배포 (사내 Maven/npm 미러·레지스트리가 있는 경우)

### 로컬 개발(`./gradlew dev`)을 폐쇄망에서

한 번에 전환한다(되돌리기는 `--revert`):

```bash
./deploy/use-internal-mirror.sh
./gradlew dev
```

스크립트가 `gradle.properties` 의 `testerRepoUrl`(Maven)과 `npmRegistry`(npm)를 채운다.
기본값은 `https://test.com/repository/...` 이고 환경변수로 덮어쓸 수 있다
(`TESTER_REPO_URL`, `NPM_REGISTRY`, `GRADLE_DIST_URL`).

`dev` 가 네트워크를 쓰는 지점은 셋뿐이고, 각각 이렇게 해결한다:

| 무엇 | 기본 동작 | 폐쇄망에서 |
|---|---|---|
| Gradle 의존성 | Maven Central | `testerRepoUrl` 이 있으면 **그 미러만** 본다 |
| npm 의존성 | registry.npmjs.org | `npmRegistry` 를 `npm ci --registry=` 로 넘긴다 |
| Gradle 배포본 | services.gradle.org | `gradleDistUrl`(또는 `TESTER_GRADLE_DIST_URL`)이 있으면 `gradlew` 가 그 주소에서 받는다 |

```bash
./deploy/use-internal-mirror.sh --with-gradle-dist   # Gradle 배포본까지 사내 미러에서
./gradlew dev --offline                              # 이미 다 받아 둔 상태 — 네트워크 접근 0
```

- **Gradle 배포본 분기**: wrapper 는 `distributionUrl` 을 파일에서만 읽고 환경변수 오버라이드가
  없다. 그래서 `gradlew`/`gradlew.bat` 이 실행 시점에 ① `TESTER_GRADLE_DIST_URL` → ②
  `gradle.properties` 의 `gradleDistUrl` → ③ 없으면 커밋된 값(공개) 순으로 고르고, 값이 있을 때만
  `build/wrapper/` 에 wrapper 사본과 수정된 properties 를 만들어 그쪽을 실행한다.
  **커밋된 `gradle/wrapper/gradle-wrapper.properties` 는 건드리지 않는다.**

  ```bash
  TESTER_GRADLE_DIST_URL=https://test.com/repository/gradle-dist/gradle-9.7.1-bin.zip ./gradlew dev
  ```
- `--offline` 은 `node_modules` 가 이미 있으면 **npm 설치를 건너뛴다**. `npm ci` 는 `node_modules`
  를 지우고 다시 받으므로, 의존성을 반입해 둔 장비에서는 이 플래그가 없으면 오히려 깨진다.
- **주의**: 미러를 바꾼 직후 첫 빌드는 미러에 실제로 닿아야 한다. Gradle 캐시는 저장소별로
  묶이기 때문에, 인터넷에서 받아 둔 캐시가 있어도 `--offline` 만으로는 새 미러 기준 해석이 안 된다.
  `--with-gradle-dist` 를 쓰면 wrapper 캐시도 무효화되므로 미러가 그 zip 을 갖고 있어야 한다.

### 의존성 저장소 (직접 지정)

| 지정 방법 | 예 |
|---|---|
| 커맨드라인 | `-PtesterRepoUrl=... -PtesterRepoUser=... -PtesterRepoPassword=... -PnpmRegistry=...` |
| 환경변수 | `TESTER_REPO_URL` / `TESTER_REPO_USER` / `TESTER_REPO_PASSWORD` / `NPM_REGISTRY` |
| 파일 | `gradle.properties` 의 `testerRepoUrl` · `npmRegistry` · `gradleDistUrl` |

```bash
./gradlew build -PtesterRepoUrl=https://test.com/repository/maven-public/
```

`http://` 미러도 그대로 쓸 수 있다(Gradle 7+ 가 요구하는 `allowInsecureProtocol` 을 자동으로 켠다).

### 설정은 한 곳에서

`./deploy/use-internal-mirror.sh` 를 한 번 돌리면 **그 뒤로는 아무 값도 다시 입력하지 않는다.**
기록된 값을 `gradlew`, `./gradlew dev`, `deploy/push-internal.sh` 가 모두 같은 경로로 읽는다
(`deploy/internal-config.sh`).

| 값 | 저장되는 곳 | 읽는 쪽 |
|---|---|---|
| `testerRepoUrl` / `npmRegistry` / `gradleDistUrl` | `gradle.properties` | Gradle, `gradlew`, push 스크립트 |
| `REGISTRY` / `TAG` | `.env` | `docker-compose.internal.yml`, push 스크립트 |

```bash
./deploy/use-internal-mirror.sh -r 192.168.70.114:5000   # 미러 + 레지스트리를 한 번에 기록
```

### 이미지 빌드 → 내부 레지스트리 푸시

```bash
./deploy/push-internal.sh                # 호스트 빌드 → 이미지 → 푸시 (인자 불필요)
./deploy/push-internal.sh --no-push      # 이미지까지만
./deploy/push-internal.sh --skip-build   # 이미 빌드한 jar/dist 를 그대로 사용
TAG=1.2.0 ./deploy/push-internal.sh      # 일회성 덮어쓰기
```

내부망 모드면 베이스 이미지도 레지스트리에서 받는다(`JRE_IMAGE` / `NGINX_IMAGE` 빌드 인자를
스크립트가 자동으로 붙인다). Harbor 처럼 프로젝트 경로가 붙는 형태면
`-r 192.168.70.114/tester` 처럼 통째로 지정하면 된다.

이미지 안에서 빌드하지 않으므로 **이미지 빌드 단계에는 Maven/npm 미러가 필요 없다** —
의존성은 호스트 빌드에서 이미 해결된다.

### 내부망 서버에서 실행

`docker-compose.internal.yml` 은 **빌드하지 않고 레지스트리에서 받아서만** 띄운다.

```bash
cp .env.example .env     # TESTER_JWT_SECRET, REGISTRY, TAG 확인
docker compose -f docker-compose.internal.yml pull
docker compose -f docker-compose.internal.yml up -d
```

> 레지스트리가 https 가 아니면 각 도커 호스트의 `/etc/docker/daemon.json` 에
> `{ "insecure-registries": ["192.168.70.114:5000"] }` 를 넣고 도커를 재시작해야 pull 이 된다.

컨테이너 이미지에는 **curl 이 들어 있지 않다.** 폐쇄망에서 apt 미러까지 준비하지 않아도 되도록
헬스체크를 JDK 내장 HTTP 클라이언트로 바꿨다(`deploy/HealthCheck.java`, 빌드 단계에서 컴파일).

## 로그 수신 테스트 서버 (log-sink)

테스트 도구가 보낸 TCP/UDP/HTTP 트래픽이 **실제로 맞게 도착하는지 눈으로 확인**하기 위한
테스트 전용 수신 서버. `tools/log-sink/` 에 있으며 외부 의존성 없는 순수 JDK 코드 하나로
되어 있다 — 대용량 트래픽을 흘려보는 용도가 아니라 "보낸 그대로 잘 가는가"를 확인하는 도구다
(초당 수만~수십만 건 처리량 검증은 `./gradlew perf` 가 이미 다룬다).

### 왜 바로 쓸 수 있는가

이 sink 는 `tools/log-sink/` 의 기본 설정 그대로 `TCP 9000` / `UDP 5514` / `HTTP 9880`(수신) 으로 뜬다.
HTTP 는 경로를 가리지 않고 받으며 본문을 형식(텍스트 줄·JSON Lines·JSON 배열·Splunk HEC·ES _bulk·Loki)에 맞게
건별로 나눠 보여 준다 — 수집기를 설치하지 않고도 HTTP 전송을 확인할 수 있다.
가입해도 전송 대상은 비어 있으므로(기본 대상을 미리 넣지 않는다), sink 로 테스트하려면 **전송 대상**에서
`UDP 127.0.0.1:5514` (또는 `TCP 127.0.0.1:9000`)를 한 번 추가해 두면 된다.

### 띄우기

```bash
cd tools/log-sink
docker compose up -d --build
```

→ 대시보드: **http://localhost:8090** (1초마다 자동 새로고침, 수신 건수·바이트·최근 로그를 보여준다)

```bash
docker compose down     # 종료
```

### 테스트하는 방법

**1) 앱에서 실제로 보내보기** (가장 흔히 쓸 방법)

```bash
./gradlew dev            # 백엔드 :8080 + 프론트엔드 :5173
```

1. http://localhost:5173 에서 아무 아이디로 로그인(가입)
2. **전송 대상** → `+ 대상 추가` → `UDP 127.0.0.1:5514`(또는 `TCP 127.0.0.1:9000`) 등록
3. **전송 콘솔** → `+ 새 작업` → 카테고리는 아무거나(예: `tomcat`), 방금 만든 대상 선택
4. EPS 는 낮게 (예: 10~100 — 어차피 대량 트래픽 테스트가 아니다) → 저장 → 시작
   작업 카드에서 **보내는 로그 → 받는 곳**, 로그 종류별 **실제 전송 비율**, `예시 보기` 로 렌더 결과를 바로 확인할 수 있다
   (라운드로빈/순차는 가중치와 무관하게 균등 — 가중치대로 섞으려면 선택 방식을 가중치 랜덤으로)
5. http://localhost:8090 을 열어서 로그가 실제로 들어오는지, 내용이 의도한 대로인지 확인

템플릿 카테고리의 인코딩을 CP949/Base64 로 바꿔서 보내면(§전송 인코딩), 대시보드가 UTF-8/CP949/
Base64 세 가지로 동시에 미리보기를 보여주므로 **인코딩이 실제로 맞게 나가는지**도 바로
확인된다 — 엉뚱한 인코딩으로 보내면 UTF-8 칸이 깨져 보이고 맞는 칸만 정상으로 보인다.

**2) 앱 없이 sink 자체만 확인** (연결 점검용)

```bash
printf '테스트 로그 한 줄\n' | nc localhost 9000        # TCP
printf 'udp 테스트\n' | nc -u -w1 localhost 5514         # UDP

curl -s localhost:8090/stats | python3 -m json.tool      # 누적 건수/바이트
curl -s "localhost:8090/recent?n=5" | python3 -m json.tool  # 최근 5건 (UTF-8/CP949/Base64 미리보기)
```

### 알아 둘 점

- `/recent` 의 "최근 로그" 미리보기는 TCP 를 **개행(`\n`) 기준으로 자른다.** 테스트 도구의
  기본 프레이밍(NEWLINE)에서는 정확하지만, `LENGTH_PREFIX`/`OCTET_COUNTING`/`NONE` 프레이밍을
  쓰면 미리보기가 깨져 보일 수 있다 — 그래도 건수·바이트 누적은 프레이밍과 무관하게 항상 정확하다.
- 기본 포트가 이미 다른 데 쓰이고 있다면 `tools/log-sink/.env.example` 을 `.env` 로 복사해
  `SINK_TCP_PORT`/`SINK_UDP_PORT`/`SINK_HTTP_PORT` 를 바꾼다(컨테이너 내부 포트는 그대로 두고
  호스트 쪽 매핑만 바뀐다).
- 메인 앱을 로컬이 아니라 [Docker 배포](#docker-배포)로 띄운 경우, 백엔드 컨테이너에서는
  `127.0.0.1:9000` 이 이 sink 를 가리키지 않는다(컨테이너마다 네트워크 네임스페이스가 다르다).
  이때는 대상 호스트를 `host.docker.internal` 로 바꿔야 한다(Docker Desktop 기준. 리눅스에서는
  `docker-compose.yml` 에 `extra_hosts: ["host.docker.internal:host-gateway"]` 를 추가해야 한다).

## 로그인

아이디만 입력하면 된다(비밀번호 없음). 처음 보는 아이디면 가입, 있으면 바로 로그인된다.

- **일반 아이디**와 **이메일**을 모두 쓸 수 있다 — `gtkim`, `svc_batch-01`, `gtkim@example.com`
- 3~100자, 영문·숫자와 `. _ % + -` 사용 가능
- 대소문자를 구분하지 않는다 (`GTKim@Example.com` = `gtkim@example.com`)
- 이메일로 가입하면 화면 표시명은 `@` 앞부분만 쓴다(로그인 ID 는 입력한 값 그대로)

## 모듈 구성

```
tester-common      공용 enum/예외 (Framing, LogFormat, Protocol, SelectionStrategy ...)
tester-domain      JPA 엔티티 + Repository
tester-generator   템플릿 컴파일러 / 랜덤 값 생성기      ← Spring 비의존
tester-transport   Netty TCP·UDP 송신기 / 레이트 스케줄러 ← Spring 비의존
tester-api         REST API, JWT, SSE, 실행 오케스트레이션 (bootJar)
tester-frontend    React SPA (Vite + MUI)
```

`generator` 와 `transport` 를 Spring 에서 떼어 둔 이유는 JMH 벤치마크, 단독 CLI 부하기,
향후 분산 에이전트에 그대로 재사용하기 위해서다.

## 템플릿 문법

로그 본문에 `${함수}` 를 넣으면 전송할 때마다 랜덤 값으로 치환된다.

```
${ip as cip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] "${choice(GET:70,POST:30)} /api/v1/${choice(users,orders)}/${int(1,99999)} HTTP/1.1" ${choice(200:85,404:15)} ${int(120,48000)}
```

- `${ip(10.0.0.0/8)}` CIDR 범위 내 IPv4 · `${int(1,100,step=5)}` · `${str(8)}` · `${uuid}` · `${hex(16)}`
- `${choice(200:70,404:30)}` 가중치 선택 · `${dict(user_agents)}` 사전 · `${gauss(120,40)}` 정규분포
- `${ts(패턴,로케일)}` 시각 · `${seq}` 순번 · `${host(web,20)}` 호스트명
- `${ip as cip}` 로 이름을 붙이고 `${ref(cip)}` 로 같은 로그 안에서 재사용
- 리터럴 `${` 는 `\${` 로 쓴다

카테고리 안에 로그 종류를 여러 개 두어도 **EPS 는 초당 총 전송 건수**다.
종류별 비중은 각 로그 종류의 가중치로 정한다(템플릿 화면에 % 로 표시된다).

전체 목록은 실행 후 `GET /api/meta/functions` 또는 템플릿 화면의 "함수" 탭에서 확인한다.

템플릿 화면에서는:
- 카테고리와 로그 종류를 **끌어서 순서를 바꾼다**(순차/라운드로빈은 이 순서로 보낸다).
- `${` 를 치면 커서 위치에 함수 목록이 뜬다. Enter/Tab 으로 넣으면 인자 부분이 선택돼 바로 고쳐 쓴다.
- `${...}` 가 함수 종류별 색으로 칠해지고, 커서를 올린 함수의 설명·예시가 편집기 아래에 나온다.
- **렌더 결과**의 색칠된 값이 매번 바뀌는 부분이다. 마우스를 올리면 원문의 같은 자리가 강조되고,
  누르면 그 `${...}` 가 선택된다. **사용된 값** 탭은 쓰인 함수와 실제로 나온 값들을 표로 보여 준다.
- 함수 인자 안에 `${...}` 를 넣는 것(`${choice(a,${dict(x)})}`)은 지원하지 않는다 — 저장할 때 오류로 알려 준다.

## HTTP 전송

전송 대상의 프로토콜을 **HTTP / HTTPS** 로 고르면 로그를 모아 POST 요청으로 보낸다. 본문 형식을 고르면 그 수집기의
기본 경로·포트가 채워진다.

| 본문 형식 | 받는 쪽 예 | 기본 경로 · 포트 | 본문 |
|---|---|---|---|
| 텍스트 줄 | Logstash http(line), Vector | `/` · 8080 | 로그를 줄바꿈으로 이어 붙임 |
| JSON Lines | Vector, Logstash json_lines | `/` · 8080 | 줄마다 `{"message":"…"}` |
| JSON 배열 | Fluent Bit http 입력 | `/` · 9880 | `[{"message":"…"}, …]` |
| Splunk HEC | Splunk | `/services/collector/event` · 8088 | `{"event":"…"}` 를 이어 붙임 |
| ES / OpenSearch _bulk | Elasticsearch, OpenSearch | `/logs-tester/_bulk` · 9200 | 액션 줄 + `{"@timestamp","message"}` |
| Grafana Loki | Loki | `/loki/api/v1/push` · 3100 | `{"streams":[{"stream":{"job":"tester"},"values":[…]}]}` |

- 인증은 **추가 헤더**에 한 줄씩 적는다: `Authorization: Splunk <토큰>`, `Authorization: ApiKey <키>`, `X-Scope-OrgID: <테넌트>` 등.
- 커넥션마다 keep-alive 로 요청을 하나씩 보내고, 응답을 기다리는 동안 들어온 로그는 다음 요청에 묶는다. 그래서 속도가
  높을수록 요청당 건수가 저절로 늘어난다(요청당 최대 본문은 고급 설정, 기본 64KB).
- **4xx/5xx 응답**을 받은 요청의 로그는 전송이 아니라 **버림**으로 집계하고, 상태 코드와 응답 내용(예: `HTTP 403 Forbidden — Invalid token`)을 전송 콘솔에 보여 준다.
- **연결 테스트**는 같은 경로로 GET 을 보내 HTTP 서버가 응답하는지 본다(대개 405 — 정상). 401/403 이면 인증 실패로 알린다.
- JSON 형식은 로그 한 건을 문자열로 감싸므로 여러 줄 로그(스택트레이스)도 한 건으로 간다. JSON 은 UTF-8 이어야 하므로
  CP949 카테고리는 텍스트 줄 형식으로 보내거나 Base64 로 쓴다.
- HTTPS 는 TLS 와 같이 인증서를 검증하지 않는다(자체 서명 인증서를 쓰는 내부 수집기용).
- 대상은 프로토콜+호스트+포트로 구분하므로, 같은 서버·포트의 다른 경로(예: 인덱스 두 개)는 따로 등록할 수 없다.
- 로컬에서는 log-sink 의 `HTTP 127.0.0.1:9880` 으로 바로 시험할 수 있다(§로그 수신 테스트 서버).

## 전송 인코딩 (카테고리별)

수신 서버가 기대하는 바이트 형식을 **전송 대상**에서 고른다(프레이밍 바로 옆).
로그 본문은 그대로 두고 전송할 때의 변환만 달라지므로, 같은 템플릿을 인코딩만 바꿔 보낼 수 있다.

| 값 | 동작 | 비고 |
|---|---|---|
| `UTF_8` (기본) | UTF-8 바이트로 전송 | 대부분의 수집기가 기대하는 형식 |
| `CP949` | MS949(한글 Windows) 바이트로 전송 | 한글이 UTF-8 대비 약 2/3 크기. 매핑에 없는 문자는 `?` |
| `BASE64` | UTF-8 로 만든 뒤 전체를 Base64 로 감쌈 | 전송량 약 1.33배. 출력이 ASCII 라 개행 프레이밍과 잘 맞는다 |

> `CP949` 는 JDK 의 `Charset.forName("CP949")`(= `x-IBM949`, IBM 변형)가 아니라
> **`x-windows-949`(MS949)** 로 고정했다. 국내에서 CP949 라고 하면 사실상 Windows 코드페이지를
> 뜻하는데 둘은 일부 문자 매핑이 다르다.

고정 문자열뿐 아니라 `${choice(...)}`, `${dict(...)}` 같은 동적 값도 같은 인코딩으로 나간다.

## 확장

| 확장 대상 | 구현할 인터페이스 | 방법 |
|---|---|---|
| 랜덤 값 함수 | `ValueWriterFactory` | 빈으로 등록 → 문법·도움말에 자동 반영 |
| 전송 프로토콜 | `TransportFactory` + `LogSender` | 빈으로 등록 → 대상 화면 프로토콜 목록에 자동 추가 |
| 로그 포맷 | `LogFormat` enum | 헤더 템플릿 문자열만 추가 |
| 설정 항목 | `TesterProperties` | 필드 추가 시 `tester.*` 로 자동 바인딩 |

기존 코드를 수정하지 않고 추가만으로 동작하도록 설계했다.

## 운영 시 주의

**비밀번호가 없다.** 아이디만 알면 누구나 로그인할 수 있으므로 반드시 내부망에서만 운영하고,
외부에 노출된다면 `deploy/nginx.conf` 의 `allow/deny` 주석을 풀어 접근 대역을 제한할 것.

**TCP/TLS 수신 서버가 꺼져도 전송 작업은 멈추지 않는다.** 시작할 때 꺼져 있어도 시작하고, 실행 중에
꺼지면 작업 카드에 "자동으로 다시 연결하는 중"이 뜬 채 계속 시도한다(최대 3초 간격). 서버가 뜨면
바로 이어서 보낸다. 그동안 만든 로그는 포화 정책이 **버림**이면 버리고(버림 건수에 잡힌다), **대기**면
생성을 멈추고 기다린다. 대상 주소가 맞는지 확인하려면 전송 대상 화면의 **연결 테스트**를 쓴다 —
이쪽은 지금도 연결이 안 되면 실패로 알려 준다.

고부하(10만 EPS 이상) 실행 전 권장 커널 설정:

```bash
ulimit -n 65535
sysctl -w net.core.wmem_max=16777216
sysctl -w net.core.rmem_max=16777216   # UDP 수신측도 함께 올릴 것
```

Linux 에서는 epoll 네이티브 전송이 자동으로 선택된다(기동 로그의 `transport=epoll(native)` 확인).
