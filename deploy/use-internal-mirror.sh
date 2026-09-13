#!/usr/bin/env bash
# 로컬 개발 환경을 내부망(폐쇄망) 모드로 전환한다. `./gradlew dev` 가 인터넷 없이 돌게 하는 게 목적.
#
#   ./deploy/use-internal-mirror.sh                      # Maven·npm 미러만 설정(대부분 이걸로 충분)
#   ./deploy/use-internal-mirror.sh --with-gradle-dist   # Gradle 배포본까지 사내 미러에서
#   ./deploy/use-internal-mirror.sh --revert             # 공개 저장소로 되돌리기
#
# 기본값은 환경변수로 덮어쓸 수 있다:
#   TESTER_REPO_URL   사내 Maven 미러      (기본 https://test.com/repository/maven-public/)
#   NPM_REGISTRY      사내 npm 미러        (기본 https://test.com/repository/npm-public/)
#   GRADLE_DIST_URL   사내 Gradle 배포본    (기본 https://test.com/repository/gradle-dist/gradle-9.7.1-bin.zip)
#
# 건드리는 파일은 둘뿐이다:
#   gradle.properties                  testerRepoUrl / npmRegistry
#   gradle/wrapper/gradle-wrapper.properties   distributionUrl
#
# ※ distributionUrl 은 기본적으로 건드리지 않는다(--with-gradle-dist 로만 바꾼다).
#   바꾸는 순간 ~/.gradle/wrapper/dists 의 기존 캐시가 무효가 되어 **미러가 그 zip 을 실제로
#   서빙해야만** gradlew 가 뜬다. 이미 배포본을 받아 둔 장비나 Gradle 이 설치된 장비라면
#   그대로 두는 편이 안전하다.
set -euo pipefail

cd "$(dirname "$0")/.."

MAVEN_MIRROR="${TESTER_REPO_URL:-https://test.com/repository/maven-public/}"
NPM_MIRROR="${NPM_REGISTRY:-https://test.com/repository/npm-public/}"
GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin\.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
GRADLE_DIST="${GRADLE_DIST_URL:-https://test.com/repository/gradle-dist/gradle-${GRADLE_VERSION}-bin.zip}"

PROPS=gradle.properties
WRAPPER=gradle/wrapper/gradle-wrapper.properties

set_prop() { # set_prop <키> <값>
	local key="$1" value="$2"
	# 주석 처리돼 있든 값이 들어 있든 없든, 한 줄로 정리한다.
	python3 - "$PROPS" "$key" "$value" <<'PY'
import pathlib, re, sys
path, key, value = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
lines = path.read_text().splitlines()
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

WITH_DIST=0
[ "${1:-}" = "--with-gradle-dist" ] && WITH_DIST=1

if [ "${1:-}" = "--revert" ]; then
	echo "== 공개 저장소 모드로 되돌린다 =="
	set_prop testerRepoUrl ""
	set_prop npmRegistry ""
	sed -i.bak "s|^distributionUrl=.*|distributionUrl=https\\\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip|" "$WRAPPER"
	rm -f "${WRAPPER}.bak"
	echo "  gradle.properties: testerRepoUrl / npmRegistry 주석 처리"
	echo "  ${WRAPPER}: services.gradle.org"
	exit 0
fi

echo "== 내부망 모드로 전환 =="
set_prop testerRepoUrl "$MAVEN_MIRROR"
set_prop npmRegistry "$NPM_MIRROR"
echo "  Maven  : $MAVEN_MIRROR"
echo "  npm    : $NPM_MIRROR"

if [ "$WITH_DIST" = "1" ]; then
	# distributionUrl 의 ':' 는 properties 규격상 이스케이프한다.
	sed -i.bak "s|^distributionUrl=.*|distributionUrl=$(printf '%s' "$GRADLE_DIST" | sed 's|:|\\\\:|')|" "$WRAPPER"
	rm -f "${WRAPPER}.bak"
	echo "  Gradle : $GRADLE_DIST"
	echo "           (기존 wrapper 캐시가 무효화된다 — 미러가 이 zip 을 서빙해야 gradlew 가 뜬다)"
else
	echo "  Gradle : 그대로 둠(이미 받아 둔 배포본 사용). 미러에서 받으려면 --with-gradle-dist"
fi
echo
echo "확인:"
echo "  ./gradlew dev            # 백엔드 :8080 + 프론트엔드 :5173"
echo "  ./gradlew dev --offline  # 의존성·node_modules 를 이미 받아 뒀을 때(네트워크 접근 0)"
