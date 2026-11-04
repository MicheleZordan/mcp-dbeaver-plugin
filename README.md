# DBeaver MCP Server plugin

A DBeaver Community Edition plugin that exposes the connections configured in DBeaver to MCP clients
(Claude Code, Claude Desktop, Cursor) as a local, bearer-token protected, **read-only** MCP server.

It runs inside DBeaver's JVM, so it reuses DBeaver's drivers, saved credentials, SSH tunnels and metadata model.
Nothing is read from DBeaver's credential files directly.

## What clients get

| Tool | Purpose |
|---|---|
| `list_connections` | Connections the user exposed in *Preferences > MCP Server* |
| `list_schemas` | Catalogs / schemas of a connection |
| `list_tables` | Tables and views of a schema |
| `describe_table` | Columns, constraints, indexes, foreign keys and DDL of a table or view |
| `get_table_ddl` | The `CREATE` statement as DBeaver generates it |
| `run_query` | Exactly one `SELECT`, `SHOW`, `EXPLAIN` or `DESCRIBE` statement, row-capped |

## Read-only enforcement (the hard constraint)

Enforcement lives in code, in [`ReadOnlyGuard`](plugins/com.afliant.dbeaver.mcp/src/com/afliant/dbeaver/mcp/guard/ReadOnlyGuard.java)
and [`QueryExecutor`](plugins/com.afliant.dbeaver.mcp/src/com/afliant/dbeaver/mcp/exec/QueryExecutor.java).
There is no tool that bypasses it and no setting that disables it.

1. **One statement.** DBeaver's dialect-aware script splitter must yield exactly one statement; client-side
   control commands (`@set`, `DELIMITER`, ...) are rejected.
2. **AST classification.** The statement is parsed with JSqlParser (the parser DBeaver bundles). Only a
   `SELECT` (optionally under `EXPLAIN`) passes. `SHOW` and `DESC[RIBE]`, which JSqlParser does not fully cover,
   are accepted by leading keyword since they are introspection-only. SQL that does not parse is **rejected**:
   if it cannot be proven read-only it does not run.
3. **Token checks** on the statement with literals, comments and quoted identifiers blanked out: no `;`,
   no `INTO` / `OUTFILE` / `DUMPFILE`, no `FOR UPDATE` / `FOR SHARE` / `LOCK IN SHARE MODE`, and no write or DDL
   keyword anywhere (`INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`, `GRANT`, `REVOKE`, `CALL`, `LOAD`,
   `LOCK`, `UNLOCK`, `KILL`, `RENAME`, `PURGE`, `OPTIMIZE`). MySQL version comments (`/*! ... */`) are rejected
   outright because the server executes their content.
4. **Isolated read-only context.** Queries never share the SQL editor's connection. Each exposed connection gets
   its own DBeaver execution context whose JDBC connection has `autocommit=false` and `readOnly=true`. On MySQL /
   MariaDB the session is additionally switched with `SET SESSION TRANSACTION READ ONLY` and the server is asked to
   confirm it, so DML **and DDL** are refused by the server itself (error 1792). PostgreSQL gets
   `default_transaction_read_only = on`. Every result reports `server_side_read_only`.
5. **Always rollback.** Every call ends with `rollback()`, even on success.
6. **Row cap and timeout** on every statement (defaults 500 rows, 30 s; configurable).

The remaining gap, which no client-side plugin can close, is a database account that can write: for a hard
guarantee, point the DBeaver connection at a read-only database user. For embedded engines (SQLite, H2) layers 1-3
and 5 still apply but the driver treats `readOnly` as a hint.

Also note: `SELECT` can still call functions with side effects or read server files (`LOAD_FILE`) if the database
account allows it, and `SELECT SLEEP(n)` is bounded only by the query timeout.

## Security model

- Binds to `127.0.0.1` only; requests with a non-local `Origin` header are refused.
- Every request needs `Authorization: Bearer <token>`. The token is generated on first use, shown in
  *Preferences > MCP Server* and can be regenerated. Anyone holding it can run read-only queries on the exposed
  connections while the server is running.
- Connections are opt-in: nothing is exposed until ticked in the preference page.
- The server does **not** start with DBeaver. Start and stop it from the **MCP** menu.
- Every tool call is appended to `mcp-audit.log` in the plugin's state folder of the DBeaver workspace
  (`.metadata/.plugins/com.afliant.dbeaver.mcp/`; the path is shown in *MCP > Server Status*).

## Build

Requirements: JDK 21 or newer, Maven 3.9+, and a local DBeaver 26.x installation (the build compiles against it).

```bash
mvn verify
```

The target platform is the installed DBeaver, configured in `.mvn/maven.config` (`-Ddbeaver.home=...`,
default `/Applications/DBeaver.app/Contents/Eclipse`). The OS/arch of the target platform is detected from the
build JVM; on Apple Silicon with an x86_64 JDK pass `-Dosgi.arch=aarch64` (or use a native arm64 JDK).

Outputs:
- `plugins/com.afliant.dbeaver.mcp/target/*.jar` – the bundle
- `releng/repository/target/repository/` – a p2 update site, also zipped as `releng/repository/target/*.zip`

## Install

Quit DBeaver, then:

```bash
./scripts/install-local.sh
```

The script uses DBeaver's p2 director to install or update the feature. Alternatively, in DBeaver use
*Help > Install New Software...*, add the `releng/repository/target/repository` folder (or the zip) as a local
site and install "DBeaver MCP Server". `./scripts/uninstall-local.sh` removes it.

## Use

1. *Preferences > MCP Server*: tick the connections to expose, adjust port / row limit / timeout, copy the token.
2. *MCP > Start MCP Server*.
3. *MCP > Copy Client Configuration...* and paste the snippet for your client:

```bash
# Claude Code
claude mcp add --transport http dbeaver http://127.0.0.1:7912/mcp --header "Authorization: Bearer <token>"
```

```json
// Cursor: ~/.cursor/mcp.json
{ "mcpServers": { "dbeaver": { "url": "http://127.0.0.1:7912/mcp", "headers": { "Authorization": "Bearer <token>" } } } }
```

```json
// Claude Desktop: claude_desktop_config.json (needs Node.js for the mcp-remote bridge)
{ "mcpServers": { "dbeaver": {
  "command": "npx", "args": ["-y", "mcp-remote", "http://127.0.0.1:7912/mcp", "--header", "Authorization:${AUTH_HEADER}"],
  "env": { "AUTH_HEADER": "Bearer <token>" } } } }
```

Connections that are not connected yet are connected on first use (DBeaver may prompt for a password if it is
not saved).

## Test database

`test/docker-compose.yml` starts a MySQL on port 3307 with a small seeded schema (`mcptest`), test credentials
`mcp` / `mcp-test`. Register it in DBeaver as a MySQL 8 connection, expose it, start the server, then:

```bash
MCP_TOKEN=<token> ./test/smoke.sh
```

## Layout

```
pom.xml                               Tycho parent (target platform = installed DBeaver)
releng/dbeaver.target                 directory-based target definition
plugins/com.afliant.dbeaver.mcp       the plugin
  guard/ReadOnlyGuard                 statement validation (layers 1-3)
  exec/QueryExecutor                  isolated read-only execution (layers 4-6)
  meta/MetadataService                schema browsing via DBeaver's model
  server/McpHttpServer, McpTools      MCP Streamable HTTP transport and tool catalogue
  ui/                                 MCP menu handlers, preference page, client config dialog
features/, releng/repository          feature and p2 update site
scripts/                              local install / uninstall via p2 director
test/                                 docker-compose MySQL, seed SQL, curl smoke test
```
