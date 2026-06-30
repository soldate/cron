#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8086}"

curl -fsS "${BASE_URL}/health"
printf '\n'
curl -fsS "${BASE_URL}/api/cron/tasks"
printf '\n'
