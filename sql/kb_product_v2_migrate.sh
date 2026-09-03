#!/usr/bin/env bash
# 知识库 Product V2 增量迁移（PostgreSQL）
# 用法: PGHOST=... PGUSER=... PGDATABASE=... ./sql/kb_product_v2_migrate.sh
# 或: ./sql/kb_product_v2_migrate.sh "postgresql://user:pass@host:5432/db"
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CONN="${1:-}"
run() {
  local f="$1"
  echo "==> $f"
  if [[ -n "$CONN" ]]; then
    psql "$CONN" -v ON_ERROR_STOP=1 -f "$ROOT/$f"
  else
    psql -v ON_ERROR_STOP=1 -f "$ROOT/$f"
  fi
}
run kb_acl_v2.sql
run kb_eval_v1.sql
run kb_index_policy_v1.sql
echo "OK: Product V2 migrations applied (acl/eval/policy)."
