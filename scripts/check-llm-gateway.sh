#!/usr/bin/env bash
# CR 守卫脚本：
#   1. 禁止业务代码绕过 LlmGateway 直接使用 LlmAdapter
#   2. 禁止全局缓存 key（latest_context / current_context / ...）
#   3. 禁止前端直连模型 provider
#
# 用法：从 repo 根目录执行 ./scripts/check-llm-gateway.sh
# 退出码：0 通过，1 违规（CI 应据此 fail）。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_SRC="$ROOT_DIR/backend/src/main/java"
ALLOW_PREFIX="com/company/aitest/llm/gateway"

fail=0

# (1) LlmAdapter 直接引用
violations=$(grep -rln "import com.company.aitest.llm.LlmAdapter" "$BACKEND_SRC" 2>/dev/null \
  | grep -v "/$ALLOW_PREFIX/" || true)
if [[ -n "$violations" ]]; then
  echo "❌ 检测到非 Gateway 包直接引用 LlmAdapter："
  echo "$violations"
  echo "请改为依赖 com.company.aitest.llm.gateway.LlmGateway。"
  fail=1
fi

# (2) 缓存 key 黑名单
banned_pattern='"(latest_context|current_context|last_task|last_messages|case_generation_prompt|rag_result|kb_top|shared_thread|conversation_default)"'
cache_violations=$(grep -rEn "$banned_pattern" "$BACKEND_SRC" 2>/dev/null \
  | grep -v "/$ALLOW_PREFIX/cache/" || true)
if [[ -n "$cache_violations" ]]; then
  echo "❌ 检测到禁用的全局缓存 key："
  echo "$cache_violations"
  echo "请改为使用 com.company.aitest.llm.gateway.cache.LlmCacheKey 提供的工厂方法。"
  fail=1
fi

# (3) 前端直连模型 provider
declare -a FRONTEND_SRCS=()
[[ -d "$ROOT_DIR/frontend-react/src" ]] && FRONTEND_SRCS+=("$ROOT_DIR/frontend-react/src")
[[ -d "$ROOT_DIR/frontend/src" ]] && FRONTEND_SRCS+=("$ROOT_DIR/frontend/src")

for FRONTEND_SRC in "${FRONTEND_SRCS[@]}"; do
  fe_violations=$(grep -rEn "fetch\([\"'\`]https?://(api\.)?(openai\.com|anthropic\.com)" "$FRONTEND_SRC" 2>/dev/null || true)
  if [[ -n "$fe_violations" ]]; then
    echo "❌ 前端检测到直连模型 provider："
    echo "$fe_violations"
    fail=1
  fi
done

if [[ $fail -ne 0 ]]; then
  exit 1
fi

echo "✅ LLM Gateway / 缓存隔离 / 前端 provider 检查全部通过"
