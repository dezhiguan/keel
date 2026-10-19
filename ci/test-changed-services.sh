#!/usr/bin/env bash
set -euo pipefail
scope_script="$(cd "$(dirname "$0")" && pwd)/changed-services.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/keel-deployment-scope.XXXXXX")"
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"
git init -q
git config user.email qa@example.invalid
git config user.name 'Deployment scope fixture'
echo base > README
git add README
git commit -qm base
base_sha="$(git rev-parse HEAD)"
mkdir -p keel-server/src console/src
echo backend > keel-server/src/changed.java
git add keel-server
git commit -qm backend
before_sha="$(git rev-parse HEAD)"
echo frontend > console/src/changed.vue
git add console
git commit -qm frontend
head_sha="$(git rev-parse HEAD)"
GITHUB_EVENT_NAME=push BEFORE="$before_sha" DEPLOY_BASE_SHA="$base_sha" GITHUB_SHA="$head_sha" GITHUB_OUTPUT="$fixture/cumulative" bash "$scope_script" >/dev/null
for field in server console; do grep -qx "$field=true" "$fixture/cumulative"; done
GITHUB_EVENT_NAME=push BEFORE="$before_sha" DEPLOY_BASE_SHA=ALL GITHUB_SHA="$head_sha" GITHUB_OUTPUT="$fixture/bootstrap" bash "$scope_script" >/dev/null
for field in llm server gateway audit console echo sandbox qa_agent qa_mainflow; do grep -qx "$field=true" "$fixture/bootstrap"; done
GITHUB_EVENT_NAME=push BEFORE="$before_sha" DEPLOY_BASE_SHA= GITHUB_SHA="$head_sha" GITHUB_OUTPUT="$fixture/event-only" bash "$scope_script" >/dev/null
grep -qx server=false "$fixture/event-only"
grep -qx console=true "$fixture/event-only"
echo '部署累计范围与首次部署检查通过'
