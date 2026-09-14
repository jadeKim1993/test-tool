# 내부망 설정 한 곳. deploy/*.sh 가 공통으로 읽는다(직접 실행하는 파일이 아니라 source 용).
#
# 우선순위: 환경변수 > 파일 > 기본값
#   의존성 미러   gradle.properties  (testerRepoUrl / npmRegistry / gradleDistUrl)
#   도커 레지스트리 .env             (REGISTRY / TAG)
#
# gradle.properties 를 쓰는 이유는 Gradle 과 gradlew 가 **이미 그 파일을 읽기 때문**이다.
# 같은 값을 스크립트마다 다시 입력하지 않도록 여기서 한 번만 해석한다.

_tester_root() { cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd; }
TESTER_ROOT="${TESTER_ROOT:-$(_tester_root)}"

# 파일에서 키 하나를 읽는다(주석 처리된 줄은 무시).
_read_key() { # _read_key <파일> <키>
	[ -f "$1" ] || return 0
	sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*//p" "$1" | head -1 | tr -d '[:space:]'
}

MAVEN_MIRROR="${TESTER_REPO_URL:-$(_read_key "$TESTER_ROOT/gradle.properties" testerRepoUrl)}"
NPM_MIRROR="${NPM_REGISTRY:-$(_read_key "$TESTER_ROOT/gradle.properties" npmRegistry)}"
GRADLE_DIST="${TESTER_GRADLE_DIST_URL:-$(_read_key "$TESTER_ROOT/gradle.properties" gradleDistUrl)}"

REGISTRY="${REGISTRY:-$(_read_key "$TESTER_ROOT/.env" REGISTRY)}"
REGISTRY="${REGISTRY:-192.168.70.114:5000}"
TAG="${TAG:-$(_read_key "$TESTER_ROOT/.env" TAG)}"
TAG="${TAG:-latest}"

# 미러가 하나라도 설정돼 있으면 "내부망 모드"로 본다.
if [ -n "$MAVEN_MIRROR" ] || [ -n "$NPM_MIRROR" ] || [ -n "$GRADLE_DIST" ]; then
	INTERNAL_MODE=1
else
	INTERNAL_MODE=0
fi

print_internal_config() {
	echo "  레지스트리 : $REGISTRY  (태그 $TAG)"
	echo "  Maven 미러 : ${MAVEN_MIRROR:-(미설정 → Maven Central)}"
	echo "  npm 미러   : ${NPM_MIRROR:-(미설정 → registry.npmjs.org)}"
	echo "  Gradle 배포본: ${GRADLE_DIST:-(미설정 → services.gradle.org)}"
}
