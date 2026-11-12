#!/bin/bash
#
# 이미지 일반화(seal) — provision.sh 가 systemd-run 으로 띄운다. 끝나면 VM 을 끈다.
#
# 같은 이미지로 만든 서버들이 같은 값을 가지면 안 되는 것과, 빌드할 때 생긴 흔적을 지운다.
#   - 로그인 서명키(TESTER_JWT_SECRET)·검증용 DB  → 인스턴스 첫 부팅 때 tester-instance-init 이 새로
#   - SSH 호스트 키 · machine-id · cloud-init 상태 → 인스턴스 첫 부팅 때 cloud-init/systemd 가 새로
#   - 빌드 VM 의 MAC 에 묶인 netplan 설정          → cloud-init 이 인스턴스 NIC 기준으로 새로
#   - 로그 · apt 캐시 · 임시 파일
# 마지막에 fstrim 으로 빈 블록을 돌려줘 qcow2 를 작게 만든다(빌더가 discard=unmap 으로 붙인다).

set -Eeuo pipefail
exec > /dev/console 2>&1

say() { echo "[tester-image] $*"; }
fail() {
	trap - ERR
	say "실패: $*"
	echo "TESTER-IMAGE-RESULT: FAIL $*"
	sync
	sleep 2
	systemctl poweroff --no-block || poweroff -f
	exit 1
}
trap 'fail "seal.sh ${LINENO}행 — $BASH_COMMAND"' ERR

PREFIX=/opt/tester

say "일반화: cloud-init 이 끝나기를 기다립니다"
rc=0
cloud-init status --wait > /dev/null || rc=$?
case "$rc" in
	0) ;;
	2)
		# 복구 가능한 경고(폐기 예정 설정 등). 이미지에는 문제가 없으므로 기록만 남기고 계속한다.
		say "cloud-init 경고가 있습니다(계속 진행):"
		{ cloud-init status --long || true; } | sed 's/^/[tester-image]   /'
		;;
	*)
		{ cloud-init status --long || true; } | sed 's/^/[tester-image]   /'
		fail "빌드 부팅에서 cloud-init 오류 (exit $rc)"
		;;
esac
umount /run/tester-seed 2> /dev/null || true

say "서비스 정지"
systemctl stop tester-web tester-api

say "인스턴스마다 달라야 하는 것 제거"
sed -i 's/^TESTER_JWT_SECRET=.*/TESTER_JWT_SECRET=/' "$PREFIX/conf/tester.conf"
rm -f "$PREFIX/conf/instance-id"
find "$PREFIX/data" "$PREFIX/logs" "$PREFIX/run" -mindepth 1 -delete
say "  로그인 서명키·검증용 DB·로그 ($PREFIX/conf, data, logs)"
rm -f /etc/ssh/ssh_host_*
say "  SSH 호스트 키"
rm -f /etc/netplan/50-cloud-init.yaml
say "  빌드 VM 네트워크 설정 (/etc/netplan/50-cloud-init.yaml)"
if cloud-init clean --help 2>&1 | grep -q -- '--machine-id'; then
	cloud-init clean --logs --seed --machine-id
else
	cloud-init clean --logs --seed
	truncate -s 0 /etc/machine-id
fi
rm -f /var/lib/systemd/random-seed
say "  cloud-init 상태·시드·로그, machine-id, random-seed"

say "흔적 정리"
rm -f /var/log/tester-image-build.log
apt-get clean
rm -rf /var/lib/apt/lists/*
find /var/log -type f \( -name '*.gz' -o -name '*.[0-9]' -o -name '*.old' \) -delete
find /var/log -type f ! -path '/var/log/journal/*' -exec truncate -s 0 {} +
rm -rf /tmp/* /var/tmp/* /root/.bash_history /root/.cache /root/.ssh/authorized_keys
# journald 는 지운 파일에 계속 쓰다가 전원이 꺼지면 함께 사라진다 — 빌드 부팅의 저널이 이미지에 남지 않는다.
rm -rf /var/log/journal/*
say "  로그·저널·apt 캐시·임시 파일"

say "빈 블록 반환 (fstrim)"
{ fstrim -av || true; } | sed 's/^/[tester-image]   /'

say "일반화 완료 — 전원을 끕니다"
echo "TESTER-IMAGE-RESULT: OK"
sync
systemctl poweroff --no-block
