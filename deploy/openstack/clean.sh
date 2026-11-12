#!/usr/bin/env bash
#
# OpenStack 이미지 빌드가 남긴 것 정리 — 디스크가 부족할 때.
#
#   ./deploy/openstack/clean.sh             검증 보고서·빌드 로그·베이스 이미지 캐시·컨테이너 Gradle 캐시·빌더 이미지
#                                           (만든 qcow2·.sha256·.json 은 남긴다)
#   ./deploy/openstack/clean.sh --all       qcow2 까지 모두 (build/openstack 통째로)
#   ./deploy/openstack/clean.sh --dry-run   지울 것만 보여 준다
#
# 지우지 않는 것: 오프라인 설치본(build/distributions — ./gradlew clean 이 지운다), 설치본용 JRE·Node 캐시(.runtime-cache/*.tar.gz)

set -euo pipefail

# shellcheck source=lib/host.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/host.sh"

ALL=0
DRY=0
while [ $# -gt 0 ]; do
	case "$1" in
		--all) ALL=1; shift ;;
		--dry-run) DRY=1; shift ;;
		-h | --help) sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
	esac
done

remove_path() {
	[ -e "$1" ] || return 0
	echo "  $(du -sh "$1" 2> /dev/null | cut -f1)	$1"
	[ "$DRY" -eq 1 ] || rm -rf "$1"
}

echo "파일$([ "$DRY" -eq 1 ] && echo ' (--dry-run: 지우지 않음)')"
if [ "$ALL" -eq 1 ]; then
	remove_path "$OUT_DIR"
else
	remove_path "$OUT_DIR/harness"
	for f in "$OUT_DIR"/*.build-console.log "$OUT_DIR"/*.part; do remove_path "$f"; done
fi
remove_path "$CACHE_DIR"
remove_path "$REPO_DIR/.runtime-cache/gradle-docker"

echo "Docker"
if command -v docker > /dev/null 2>&1 && docker info > /dev/null 2>&1; then
	# 빌드·검증이 떠 있으면 남은 컨테이너도 정리한다(정상 종료하면 --rm 으로 이미 없다).
	for c in $(docker ps -aq --filter name=tester-image-build- --filter name=tester-image-test-); do
		echo "  컨테이너 $c"
		[ "$DRY" -eq 1 ] || docker rm -f "$c" > /dev/null
	done
	if docker image inspect "$BUILDER_IMAGE" > /dev/null 2>&1; then
		echo "  이미지 $BUILDER_IMAGE ($(docker image inspect -f '{{.Size}}' "$BUILDER_IMAGE" | awk '{printf "%.0fMB", $1/1e6}'))"
		[ "$DRY" -eq 1 ] || docker rmi "$BUILDER_IMAGE" > /dev/null
	fi
	echo "  (Docker 빌드 캐시는 다른 프로젝트 것과 섞여 있어 건드리지 않는다 — 필요하면 docker builder prune)"
fi
