#!/usr/bin/env bash
set -euo pipefail

DB_NAME="${DB_NAME:-piloto}"
DB_USER="${DB_USER:-dona_cron}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"

if [ -n "${DB_PASSWORD:-}" ] && [ -z "${PGPASSWORD:-}" ]; then
    export PGPASSWORD="${DB_PASSWORD}"
fi

psql "host=${DB_HOST} port=${DB_PORT} dbname=${DB_NAME} user=${DB_USER}" -v ON_ERROR_STOP=1 -f schema.sql
