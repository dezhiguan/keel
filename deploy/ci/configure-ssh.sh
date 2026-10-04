#!/usr/bin/env bash
# 给后续步骤写好经跳板机到 Server 3 的 SSH 配置。密钥来自环境变量，不落仓库。
set -euo pipefail

if [[ -z "${SSH_KEY:-}" || -z "${DEPLOY_HOST:-}" ]]; then
  echo "CAREERMATE_APP_SSH_KEY and CAREERMATE_APP_HOST must be set" >&2
  exit 1
fi

mkdir -p ~/.ssh
chmod 700 ~/.ssh
printf '%s\n' "${SSH_KEY}" > ~/.ssh/deploy_key
chmod 600 ~/.ssh/deploy_key
JUMP_HOST="${JUMP_HOST:-8.163.63.222}"
ssh-keyscan "${JUMP_HOST}" >> ~/.ssh/known_hosts 2>/dev/null || true
{
  printf '%s\n' 'Host keel-jump'
  printf '%s\n' "  HostName ${JUMP_HOST}"
  printf '%s\n' '  User root'
  printf '%s\n' '  IdentityFile ~/.ssh/deploy_key'
  printf '%s\n' '  IdentitiesOnly yes'
  printf '%s\n' '  StrictHostKeyChecking accept-new'
  printf '%s\n' '  BatchMode yes'
  printf '%s\n' 'Host keel-app'
  printf '%s\n' "  HostName ${DEPLOY_HOST}"
  printf '%s\n' '  User root'
  printf '%s\n' '  IdentityFile ~/.ssh/deploy_key'
  printf '%s\n' '  IdentitiesOnly yes'
  printf '%s\n' '  StrictHostKeyChecking accept-new'
  printf '%s\n' '  BatchMode yes'
  printf '%s\n' '  ProxyJump keel-jump'
} > ~/.ssh/config
chmod 600 ~/.ssh/config
