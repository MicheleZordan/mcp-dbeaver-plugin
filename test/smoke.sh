#!/usr/bin/env bash
# Exercises the running MCP server over HTTP with curl.
#   MCP_TOKEN=<token from Preferences > MCP Server> [MCP_PORT=7912] [MCP_CONN="MCP test MySQL"] ./test/smoke.sh
set -euo pipefail
PORT="${MCP_PORT:-7912}"
TOKEN="${MCP_TOKEN:?set MCP_TOKEN to the bearer token shown in Preferences > MCP Server}"
CONN="${MCP_CONN:-MCP test MySQL}"
URL="http://127.0.0.1:$PORT/mcp"

rpc() {
  local id="$1" method="$2" params="${3:-'{}'}"
  echo "### $method $params"
  curl -s -S -X POST "$URL" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Accept: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}"
  echo; echo
}
call() { rpc "$1" tools/call "{\"name\":\"$2\",\"arguments\":$3}"; }

echo "### unauthenticated request (expect 401)"; curl -s -o /dev/null -w "%{http_code}\n\n" -X POST "$URL" -d '{}'
rpc 1 initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}'
rpc 2 tools/list
call 3 list_connections '{}'
call 4 list_schemas "{\"connection\":\"$CONN\"}"
call 5 list_tables "{\"connection\":\"$CONN\",\"schema\":\"mcptest\"}"
call 6 describe_table "{\"connection\":\"$CONN\",\"schema\":\"mcptest\",\"table\":\"orders\"}"
call 7 get_table_ddl "{\"connection\":\"$CONN\",\"table\":\"mcptest.customers\"}"
call 8 run_query "{\"connection\":\"$CONN\",\"sql\":\"SELECT c.name, SUM(o.amount) total FROM mcptest.customers c JOIN mcptest.orders o ON o.customer_id = c.id GROUP BY c.name ORDER BY total DESC\"}"
call 9 run_query "{\"connection\":\"$CONN\",\"sql\":\"SHOW CREATE TABLE mcptest.orders\"}"
call 10 run_query "{\"connection\":\"$CONN\",\"sql\":\"EXPLAIN SELECT * FROM mcptest.orders WHERE customer_id = 1\"}"
echo "### the following must all be rejected"
call 11 run_query "{\"connection\":\"$CONN\",\"sql\":\"DELETE FROM mcptest.orders\"}"
call 12 run_query "{\"connection\":\"$CONN\",\"sql\":\"SELECT 1; DROP TABLE mcptest.orders\"}"
call 13 run_query "{\"connection\":\"$CONN\",\"sql\":\"SELECT * FROM mcptest.orders FOR UPDATE\"}"
call 14 run_query "{\"connection\":\"$CONN\",\"sql\":\"CREATE TABLE mcptest.x (a INT)\"}"
call 15 run_query "{\"connection\":\"$CONN\",\"sql\":\"SELECT COUNT(*) FROM mcptest.orders\",\"max_rows\":1}"
