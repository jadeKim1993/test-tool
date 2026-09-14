#!/usr/bin/env bash
# 이 저장소를 내부망(폐쇄망) 모드로 전환한다. 한 번 돌려 두면 빌드·개발·이미지 푸시가
# 전부 같은 값을 쓴다(deploy/push-internal.sh 에 미러 주소를 다시 줄 필요가 없다).
#
#   ./deploy/use-internal-mirror.sh                          # Maven·npm 미러 설정
#   ./deploy/use-internal-mirror.sh --with-gradle-dist       # Gradle 배포본까지 사내 미러에서
#   ./deploy/use-internal-mirror.sh -r 192.168.70.114:5000   # 도커 레지스트리도 같이 기록
#   ./deploy/use-internal-mirror.sh --revert                 # 공개 저장소로 되돌리기
#
# 기본값은 환경변수로 덮어쓸 수 있다:
#   TESTER_REPO_URL / NPM_REGISTRY / GRADLE_DIST_URL / REGISTRY / TAG
#
# 기록하는 곳(= Gradle 과 다른 스크립트가 읽는 곳):
#   gradle.properties  testerRepoUrl / npmRegistry / gradleDistUrl
#   .env               REGISTRY / TAG          (-r 을 줬을 때만)
#
# ※ gradleDistUrl 은 --with-gradle-dist 로만 설정한다. 배포본 주소를 바꾸는 순간
#   ~/.gradle/wrapper/dists 의 기존 캐시가 무효가 되어 **미러가 그 zip 을 실제로 서빙해야만**
#   gradlew 가 뜬다. 이미 배포본을 받아 둔 장비라면 그대로 두는 편이 안전하다.
set -euo pipefail

cd "$(dirname "$0")/.."

PROPS=gradle.properties
ENV_FILE=.env

MAVEN_MIRROR_IN="${TESTER_REPO_URL:-https://test.com/repository/maven-public/}"
NPM_MIRROR_IN="${NPM_REGISTRY:-https://test.com/repository/npm-public/}"
GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin\.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
GRADLE_DIST_IN="${GRADLE_DIST_URL:-https://test.com/repository/gradle-dist/gradle-${GRADLE_VERSION}-bin.zip}"

WITH_DIST=0
REVERT=0
REGISTRY_IN="${REGISTRY:-}"
while [ $# -gt 0 ]; do
	case "$1" in
		--with-gradle-dist) WITH_DIST=1 ;;
		--revert) REVERT=1 ;;
		-r|--registry) shift; REGISTRY_IN="${1:-}" ;;
		*) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
	esac
	shift
done

set_key() { # set_key <파일> <키> <값>   값이 비면 주석 처리
	python3 - "$1" "$2" "$3" <<'PY'
import pathlib, re, sys
path, key, value = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
lines = path.read_text().splitlines() if path.exists() else []
pattern = re.compile(rf"^\s*#?\s*{re.escape(key)}\s*=")
out, done = [], False
for line in lines:
    if pattern.match(line):
        if not done:
            out.append(f"{key}={value}" if value else f"# {key}=")
            done = True
        continue
    out.append(line)
if not done:
    out.append(f"{key}={value}" if value else f"# {key}=")
path.write_text("\n".join(out) + "\n")
PY
}

if [ "$REVERT" = "1" ]; then
	echo "== 공개 저장소 모드로 되돌린다 =="
	set_key "$PROPS" testerRepoUrl ""
	set_key "$PROPS" npmRegistry ""
	set_key "$PROPS" gradleDistUrl ""
	echo "  gradle.properties: testerRepoUrl / npmRegistry / gradleDistUrl 주석 처리"
	echo "  (gradlew 는 값이 없으면 커밋된 distributionUrl = services.gradle.org 를 쓴다)"
	echo "  .env 의 REGISTRY/TAG 는 도커 배포 전용이라 건드리지 않았다"
	exit 0
fi

echo "== 내부망 모드로 전환 =="
set_key "$PROPS" testerRepoUrl "$MAVEN_MIRROR_IN"
set_key "$PROPS" npmRegistry "$NPM_MIRROR_IN"

if [ "$WITH_DIST" = "1" ]; then
	set_key "$PROPS" gradleDistUrl "$GRADLE_DIST_IN"
else
	set_key "$PROPS" gradleDistUrl ""
fi

if [ -n "$REGISTRY_IN" ]; then
	[ -f "$ENV_FILE" ] || cp .env.example "$ENV_FILE"
	set_key "$ENV_FILE" REGISTRY "$REGISTRY_IN"
	[ -n "${TAG:-}" ] && set_key "$ENV_FILE" TAG "$TAG"
fi

# 최종 결과는 다른 스크립트가 읽는 것과 같은 경로로 다시 읽어서 보여 준다.
# shellcheck source=deploy/internal-config.sh
. deploy/internal-config.sh
print_internal_config
if [ "$WITH_DIST" != "1" ]; then
	echo "  (Gradle 배포본은 그대로 — 미러에서 받으려면 --with-gradle-dist)"
fi

cat <<'EOM'

이제 이 값들을 다시 입력할 필요가 없다:
  ./gradlew dev              # 로컬 개발(백엔드 :8080 + 프론트엔드 :5173)
  ./gradlew dev --offline    # 의존성·node_modules 를 이미 받아 뒀을 때
  ./deploy/push-internal.sh  # 호스트 빌드 → 이미지 → 내부 레지스트리 푸시
EOM
