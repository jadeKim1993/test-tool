# OpenStack qcow2 이미지 빌더 — 이미지 빌드에 필요한 도구를 컨테이너 하나에 담는다.
#
# 호스트에는 Docker 만 있으면 된다. 이 이미지 안에서
#   1) 오프라인 설치본을 만들고(JDK 21 + Node.js — 호스트에 JDK·npm 이 없을 때 build.sh 가 여기서 Gradle 을 돌린다)
#   2) Ubuntu 24.04 클라우드 이미지를 QEMU 로 부팅해 테스트 도구를 설치하고(빌드)
#   3) 만든 qcow2 를 다시 부팅해 검증한다(하네스).
#
#   docker build -t tester-image-builder deploy/openstack/docker
#
# 스크립트는 이미지에 굽지 않고 실행할 때 저장소의 deploy/openstack 을 마운트한다 — 스크립트를 고쳐도
# 이 이미지를 다시 만들 필요가 없다(deploy/openstack/build.sh 가 알아서 한다).
#
# 폐쇄망:
#   --build-arg UBUNTU_IMAGE=192.168.70.114:5000/ubuntu:24.04       베이스 이미지를 내부 레지스트리에서
#   --build-arg APT_MIRROR=http://mirror.internal/ubuntu             apt 를 내부 미러로
#   --build-arg NODE_URL=http://mirror.internal/node/v{version}/node-v{version}-linux-{arch}.tar.gz
# 한 번 만든 이미지는 docker save / docker load 로 옮길 수도 있다.

ARG UBUNTU_IMAGE=ubuntu:24.04
FROM ${UBUNTU_IMAGE}

ARG APT_MIRROR=
# python3-openstackclient 는 upload.sh 가 호스트에 openstack CLI 가 없을 때 쓴다. 빼려면 0.
ARG WITH_OPENSTACK_CLIENT=1
# 설치본 빌드용 Node.js — 프론트엔드 빌드에 Node 22+ 가 필요하다(Ubuntu 24.04 apt 의 nodejs 는 18 이라 쓰지 않는다).
# 설치본에 넣는 Node 와 같은 버전으로 맞춘다(build.gradle 의 nodeVersion).
ARG NODE_VERSION=24.22.1
ARG NODE_URL=https://nodejs.org/dist/v{version}/node-v{version}-linux-{arch}.tar.gz

# qemu-system-x86 / qemu-system-arm : amd64·arm64 게스트 (KVM 이 없으면 TCG 로 에뮬레이션)
# qemu-efi-aarch64                   : arm64 게스트용 UEFI 펌웨어
# qemu-utils                         : qemu-img (오버레이·압축·검사)
# xorriso                            : cloud-init 시드 / config-drive ISO
# openssh-client · curl · jq · python3 : 하네스(게스트 접속·API 호출·결과 정리)
# openjdk-21-jdk-headless            : 설치본 빌드(Gradle, Java 21 툴체인)
RUN set -eux; \
	if [ -n "$APT_MIRROR" ]; then \
		sed -i "s|^URIs: .*|URIs: ${APT_MIRROR}|" /etc/apt/sources.list.d/ubuntu.sources; \
	fi; \
	extra=""; \
	[ "$WITH_OPENSTACK_CLIENT" = "1" ] && extra="python3-openstackclient"; \
	apt-get update; \
	DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
		qemu-system-x86 qemu-system-arm qemu-efi-aarch64 qemu-utils \
		xorriso openssh-client curl ca-certificates jq python3 \
		openjdk-21-jdk-headless $extra; \
	rm -rf /var/lib/apt/lists/*; \
	ln -s "/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)" /usr/lib/jvm/java-21; \
	case "$(dpkg --print-architecture)" in amd64) node_arch=x64 ;; arm64) node_arch=arm64 ;; *) exit 1 ;; esac; \
	url="$(echo "$NODE_URL" | sed "s/{version}/$NODE_VERSION/g; s/{arch}/$node_arch/g")"; \
	mkdir -p /opt/node; \
	curl -fsSL "$url" | tar -xz -C /opt/node --strip-components=1 --no-same-owner; \
	qemu-system-x86_64 --version | head -1; \
	/usr/lib/jvm/java-21/bin/java -version; \
	/opt/node/bin/node --version

ENV JAVA_HOME=/usr/lib/jvm/java-21 \
	PATH=/opt/node/bin:/usr/lib/jvm/java-21/bin:$PATH

WORKDIR /work
