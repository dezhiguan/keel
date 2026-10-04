#!/usr/bin/env bash
# 写出本次提交的推送地址和拉取地址。调用方传入镜像名，例如 keel-llm。
set -euo pipefail

name="${1:?image name}"
if [[ -z "${ACR_REGISTRY:-}" ]]; then
  echo "ACR_REGISTRY must be set" >&2
  exit 1
fi
pull="${ACR_REGISTRY%/}"
push="${ACR_PUBLIC_REGISTRY:-${pull/-vpc./.}}"
tag="${GITHUB_SHA::12}"
{
  echo "server=${push%%/*}"
  echo "push_image=${push}/${name}:${tag}"
  echo "pull_image=${pull}/${name}:${tag}"
} >> "${GITHUB_OUTPUT}"
