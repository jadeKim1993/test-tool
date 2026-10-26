테스트 도구 — 오프라인 설치본 (리눅스 x64, 인터넷·Docker 불필요)
==================================================================

이 설치본 하나에 필요한 것이 모두 들어 있습니다. 서버에서 아무것도 내려받지 않습니다.
  - Java 21 (Temurin JRE)  → 백엔드 실행
  - Node.js 24 (npm 포함)  → 화면 서버 실행
  - 테스트 도구 애플리케이션

1. 설치 (원클릭)
----------------
    tar xzf tester-offline-<버전>-linux-x64.tar.gz
    cd tester-offline-<버전>-linux-x64
    sudo ./install.sh

  끝나면 접속 주소가 표시됩니다 (기본 http://<서버IP>).
  화면 포트를 바꾸려면:  sudo ./install.sh --web-port 8000

2. 관리
-------
    testerctl status          상태와 접속 주소
    testerctl logs            로그 (testerctl logs api / testerctl logs web)
    sudo testerctl restart    재시작 (설정을 바꾼 뒤)

  설정 파일: /opt/tester/conf/tester.conf (포트, JVM 옵션)
  서버가 재부팅되면 자동으로 다시 뜹니다(systemd 서비스 tester-api, tester-web).

3. 업그레이드
-------------
  새 설치본을 풀고 같은 방법으로 sudo ./install.sh 를 실행합니다.
  설정과 데이터(/opt/tester/conf, /opt/tester/data)는 그대로 유지됩니다.

  설치돼 있는지 확인만 하려면:  sudo ./install.sh --check
  (systemd 에 없어도 실행 중인 프로세스·testerctl·환경변수 TESTER_HOME·설정 파일로 찾습니다)

4. 제거
-------
    sudo ./install.sh --uninstall           설정·데이터는 남김
    sudo ./install.sh --uninstall --purge   모두 제거

5. 요구 사항
------------
  - 리눅스 x64, glibc 2.28 이상 (RHEL/Rocky/Alma 8+, Ubuntu 20.04+, Debian 10+)
    CentOS 7 은 Node.js 24 를 실행할 수 없어 지원하지 않습니다.
  - root 권한(sudo)
  - 포트: 화면 80(변경 가능), 백엔드 8080(이 서버 안에서만 열림)

6. 설치되는 위치
----------------
    /opt/tester/jre      Java          /opt/tester/node   Node.js + npm
    /opt/tester/app      애플리케이션   /opt/tester/web    화면 파일
    /opt/tester/conf     설정          /opt/tester/data   데이터베이스
    /usr/local/bin/testerctl
    /etc/profile.d/tester-runtime.sh   java·node·npm 을 PATH 에 추가(원치 않으면 --no-path)

  ※ 비밀번호 없이 아이디만으로 로그인하는 내부망 전용 도구입니다.
     방화벽으로 접근 대역을 제한하세요.
