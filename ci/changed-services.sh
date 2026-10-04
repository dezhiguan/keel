#!/usr/bin/env bash
# 按本次改动的路径决定哪些服务要测试和发布。结果写到 GITHUB_OUTPUT。
set -euo pipefail

write() {
  echo "llm=${llm}" >> "${GITHUB_OUTPUT}"
  echo "server=${server}" >> "${GITHUB_OUTPUT}"
  echo "gateway=${gateway}" >> "${GITHUB_OUTPUT}"
  echo "audit=${audit}" >> "${GITHUB_OUTPUT}"
  echo "console=${console}" >> "${GITHUB_OUTPUT}"
  echo "echo=${echo}" >> "${GITHUB_OUTPUT}"
}

llm=false
server=false
gateway=false
audit=false
console=false
echo=false

if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" ]]; then
  llm=true
  server=true
  gateway=true
  audit=true
  console=true
  echo=true
  write
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME}" == "pull_request" ]]; then
  git fetch --depth=1 origin "${BASE_REF}"
  files="$(git diff --name-only "origin/${BASE_REF}...HEAD")"
else
  if [[ -z "${BEFORE:-}" || "${BEFORE}" == 0000000000000000000000000000000000000000 ]]; then
    llm=true
    server=true
    gateway=true
    audit=true
    console=true
    echo=true
    write
    exit 0
  fi
  git cat-file -e "${BEFORE}^{commit}" 2>/dev/null || git fetch --depth=1 origin "${BEFORE}"
  files="$(git diff --name-only "${BEFORE}" "${GITHUB_SHA}")"
fi

shared=false
while IFS= read -r file; do
  [[ -z "${file}" ]] && continue
  case "${file}" in
    keel-common/*|pom.xml|deploy/docker/service.Dockerfile|.github/workflows/keel-cd.yml|.github/actions/*|deploy/ci/*|ci/changed-services.sh|ci/backend-gate.sh|contracts/*)
      shared=true ;;
    keel-llm/*|deploy/k3s/services/keel-llm.yaml)
      llm=true ;;
    keel-server/*|deploy/k3s/services/keel-server.yaml)
      server=true ;;
    keel-gateway/*|deploy/k3s/services/keel-gateway.yaml)
      gateway=true ;;
    keel-audit/*|deploy/k3s/services/keel-audit.yaml)
      audit=true ;;
    console/*|deploy/docker/console.Dockerfile|deploy/docker/console-nginx.conf|deploy/k3s/console.yaml|ci/frontend-gate.sh)
      console=true ;;
    agents/echo/*|deploy/docker/echo-agent.Dockerfile|deploy/k3s/services/echo-agent.yaml)
      echo=true ;;
  esac
done <<< "${files}"

if [[ "${shared}" == true ]]; then
  llm=true
  server=true
  gateway=true
  audit=true
fi

echo "本次需要发布：llm=${llm} server=${server} gateway=${gateway} audit=${audit} console=${console} echo=${echo}"
write
