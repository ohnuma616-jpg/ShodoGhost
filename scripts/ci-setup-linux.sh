#!/usr/bin/env bash
# Depot の Ubuntu に JDK 21 と Swift 6.2 を置く。既にあるマシンでは何もしない。
set -euo pipefail

if ! java -version 2>&1 | grep -q 'version "21\.'; then
  sudo apt-get update
  sudo apt-get install -y openjdk-21-jdk
fi

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "JAVA_HOME=${java_home}" >>"$GITHUB_ENV"
fi
export JAVA_HOME="$java_home"

if swift --version 2>/dev/null | grep -q "Swift version 6"; then
  exit 0
fi

. /etc/os-release
case "$VERSION_ID" in
  24.04)
    swift_tag="ubuntu2404"
    swift_dir="ubuntu24.04"
    packages=(
      binutils git gnupg2 libc6-dev libcurl4-openssl-dev libedit2
      libgcc-13-dev libpython3-dev libsqlite3-0 libstdc++-13-dev
      libxml2-dev libncurses-dev libz3-dev pkg-config tzdata unzip zlib1g-dev
    )
    ;;
  22.04)
    swift_tag="ubuntu2204"
    swift_dir="ubuntu22.04"
    packages=(
      binutils git gnupg2 libc6-dev libcurl4-openssl-dev libedit2
      libgcc-11-dev libpython3-dev libsqlite3-0 libstdc++-11-dev
      libxml2-dev libncurses-dev libz3-dev pkg-config tzdata unzip zlib1g-dev
    )
    ;;
  *)
    echo "Swift を入れる Ubuntu は 22.04 か 24.04。今は ${VERSION_ID}。" >&2
    exit 1
    ;;
esac

sudo apt-get update
sudo apt-get install -y "${packages[@]}"

ver="6.2"
url="https://download.swift.org/swift-${ver}-release/${swift_tag}/swift-${ver}-RELEASE/swift-${ver}-RELEASE-${swift_dir}.tar.gz"
curl -fsSL "$url" -o /tmp/swift.tar.gz
sudo mkdir -p /opt/swift
sudo tar -xzf /tmp/swift.tar.gz -C /opt/swift --strip-components=1
rm -f /tmp/swift.tar.gz

if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "/opt/swift/usr/bin" >>"$GITHUB_PATH"
fi
export PATH="/opt/swift/usr/bin:${PATH}"
swift --version
