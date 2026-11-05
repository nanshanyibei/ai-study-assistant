#!/usr/bin/env bash
# 启动后端：自动加载 .env 里的模型配置
set -e
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

PORT="${APP_PORT:-8080}"
echo "启动后端：port=$PORT 主模型=${LLM_MODEL:-mock} 快模型=${LLM_FAST_MODEL:-复用主模型}"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORT"
