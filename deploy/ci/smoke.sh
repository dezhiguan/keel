#!/usr/bin/env bash
# 只检查本次部署成功的服务。环境变量 SMOKE_* 为 true 才探对应的那一个。
set -euo pipefail

SSH_OPTS=(-T -o BatchMode=yes -o ConnectTimeout=15)

remote() {
  ssh "${SSH_OPTS[@]}" keel-app "$@"
}

cluster_ip() {
  remote "kubectl -n keel-system get svc $1 -o jsonpath='{.spec.type} {.spec.clusterIP}'"
}

http_code() {
  remote "curl -s -o /dev/null -w '%{http_code}' --max-time 10 $1"
}

if [[ "${SMOKE_CONSOLE}" == "true" ]]; then
  remote "curl --fail --silent --show-error --max-time 10 --retry 6 --retry-delay 5 http://127.0.0.1:31110/" > /tmp/keel-index.html
  grep -q 'Keel 控制台' /tmp/keel-index.html
  grep -q '/assets/' /tmp/keel-index.html
  echo "控制台页面 ok"
fi

if [[ "${SMOKE_SERVER}" == "true" ]]; then
  read -r type ip <<<"$(cluster_ip keel-server)"
  test "${type}" = "ClusterIP"
  remote "curl --fail --silent --show-error --max-time 10 http://${ip}:8080/api/v1/catalog" > /tmp/keel-catalog.json
  python3 - <<'PY'
import json
body = json.load(open("/tmp/keel-catalog.json"))
assert body["code"] == "OK", body
assert body["data"]["templates"], body
print("keel-server catalog ok")
PY
fi

if [[ "${SMOKE_LLM}" == "true" ]]; then
  read -r type ip <<<"$(cluster_ip keel-llm)"
  test "${type}" = "ClusterIP"
  remote "curl --fail --silent --show-error --max-time 10 http://${ip}:8088/health" > /tmp/keel-llm-health.json
  python3 - <<'PY'
import json
body = json.load(open("/tmp/keel-llm-health.json"))
assert body["status"] == "up", body
print("keel-llm health ok")
PY
fi

for svc in gateway audit; do
  flag="SMOKE_$(printf '%s' "${svc}" | tr '[:lower:]' '[:upper:]')"
  if [[ "${!flag}" != "true" ]]; then
    continue
  fi
  read -r type ip <<<"$(cluster_ip "keel-${svc}")"
  test "${type}" = "ClusterIP"
  code="$(http_code "http://${ip}:8080/")"
  test "${code}" != "000"
  echo "keel-${svc} 端口 ok (${code})"
done
