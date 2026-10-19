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
  echo "sandbox=${sandbox}" >> "${GITHUB_OUTPUT}"
  echo "qa_agent=${qa_agent}" >> "${GITHUB_OUTPUT}"
  echo "qa_mainflow=${qa_mainflow}" >> "${GITHUB_OUTPUT}"
}

llm=false
server=false
gateway=false
audit=false
console=false
echo=false
sandbox=false
qa_agent=false
qa_mainflow=false

if [[ "${GITHUB_EVENT_NAME}" == "workflow_dispatch" || "${DEPLOY_BASE_SHA:-}" == ALL ]]; then
  llm=true
  server=true
  gateway=true
  audit=true
  console=true
  echo=true
  sandbox=true
  qa_agent=true
  qa_mainflow=true
  write
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME}" == "pull_request" ]]; then
  git fetch --depth=1 origin "${BASE_REF}"
  files="$(git diff --name-only "origin/${BASE_REF}...HEAD")"
else
  scope_base="${DEPLOY_BASE_SHA:-${BEFORE:-}}"
  if [[ -z "${scope_base}" || "${scope_base}" == 0000000000000000000000000000000000000000 ]]; then
    llm=true
    server=true
    gateway=true
    audit=true
    console=true
    echo=true
    sandbox=true
    qa_agent=true
    qa_mainflow=true
    write
    exit 0
  fi
  git cat-file -e "${scope_base}^{commit}" 2>/dev/null || git fetch --depth=1 origin "${scope_base}"
  files="$(git diff --name-only "${scope_base}" "${GITHUB_SHA}")"
fi

shared=false
while IFS= read -r file; do
  [[ -z "${file}" ]] && continue
  case "${file}" in
    .github/workflows/keel-cd.yml)
      shared=true; qa_mainflow=true ;;
    keel-common/*|pom.xml|deploy/docker/service.Dockerfile|.github/actions/*|deploy/ci/*|ci/changed-services.sh|ci/test-changed-services.sh|ci/backend-gate.sh|contracts/*)
      shared=true ;;
    keel-llm/*|deploy/k3s/services/keel-llm.yaml)
      llm=true ;;
    keel-server/*|deploy/k3s/services/keel-server.yaml)
      server=true; sandbox=true ;;
    keel-gateway/*|deploy/k3s/services/keel-gateway.yaml)
      gateway=true ;;
    keel-audit/*|deploy/k3s/services/keel-audit.yaml)
      audit=true ;;
    console/*|deploy/docker/console.Dockerfile|deploy/docker/console-nginx.conf|deploy/k3s/console.yaml|ci/frontend-gate.sh)
      console=true ;;
    agents/meta-agent/*|deploy/k3s/services/meta-agent.yaml|services/git-ci/*|deploy/docker/git-ci.Dockerfile|deploy/k3s/services/git-ci.yaml|agents/echo/*|deploy/docker/echo-agent.Dockerfile|deploy/k3s/services/echo-agent.yaml|deploy/k3s/services/chat-demo.yaml)
      echo=true ;;
    agents/qa-lifecycle-1016/*|deploy/k3s/services/qa-lifecycle-1016.yaml)
      qa_agent=true ;;
    agents/qa-mainflow/*|deploy/k3s/services/qa-mainflow.yaml.tpl)
      qa_mainflow=true ;;
    sdk-python/keel/*)
      echo=true; sandbox=true; server=true; qa_agent=true; qa_mainflow=true ;;
    services/sandbox-sidecar/*|deploy/k3s/sandbox/*)
      sandbox=true; server=true ;;
  esac
done <<< "${files}"

if [[ "${shared}" == true ]]; then
  llm=true
  server=true
  gateway=true
  audit=true
  sandbox=true
fi

echo "本次需要发布：llm=${llm} server=${server} gateway=${gateway} audit=${audit} console=${console} echo=${echo} sandbox=${sandbox} qa_agent=${qa_agent}"
write
