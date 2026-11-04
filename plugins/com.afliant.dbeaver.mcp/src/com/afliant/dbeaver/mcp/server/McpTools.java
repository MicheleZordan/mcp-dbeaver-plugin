package com.afliant.dbeaver.mcp.server;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

import com.afliant.dbeaver.mcp.AuditLog;
import com.afliant.dbeaver.mcp.ConnectionRegistry;
import com.afliant.dbeaver.mcp.McpPreferences;
import com.afliant.dbeaver.mcp.McpToolException;
import com.afliant.dbeaver.mcp.exec.QueryExecutor;
import com.afliant.dbeaver.mcp.meta.MetadataService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Tool catalogue and dispatch. Every tool is read-only by construction. */
public final class McpTools {

    private static final Log log = Log.getLog(McpTools.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    public static final String INSTRUCTIONS = """
        This server exposes database connections configured in DBeaver, strictly read-only.
        Start with list_connections, then list_schemas / list_tables / describe_table to learn the structure.
        run_query accepts exactly one SELECT (or SHOW / EXPLAIN / DESCRIBE) statement per call; anything else is rejected
        before it reaches the database, and every statement runs in a read-only transaction that is rolled back.
        Results are capped at a configurable number of rows, so use WHERE and LIMIT for large tables.
        """;

    private final QueryExecutor executor;
    private final MetadataService metadata;

    public McpTools(QueryExecutor executor, MetadataService metadata) {
        this.executor = executor;
        this.metadata = metadata;
    }

    public JsonArray listTools() {
        JsonArray tools = new JsonArray();
        tools.add(tool("list_connections",
            "List the DBeaver connections exposed to MCP, with their ids, names, driver, host and database.",
            """
            {"type":"object","properties":{},"additionalProperties":false}
            """));
        tools.add(tool("list_schemas",
            "List the catalogs/schemas of a connection. Connects to the database if DBeaver is not connected yet.",
            """
            {"type":"object","properties":{
              "connection":{"type":"string","description":"Connection id or name (see list_connections)"}
            },"required":["connection"],"additionalProperties":false}
            """));
        tools.add(tool("list_tables",
            "List tables and views in a schema. Omit 'schema' for connections without a schema level.",
            """
            {"type":"object","properties":{
              "connection":{"type":"string","description":"Connection id or name"},
              "schema":{"type":"string","description":"Schema or catalog name; use 'catalog.schema' for nested levels"}
            },"required":["connection"],"additionalProperties":false}
            """));
        tools.add(tool("describe_table",
            "Describe a table or view: columns with types, nullability and defaults, constraints, indexes, foreign keys and the DDL.",
            """
            {"type":"object","properties":{
              "connection":{"type":"string","description":"Connection id or name"},
              "table":{"type":"string","description":"Table or view name; 'schema.table' is accepted"},
              "schema":{"type":"string","description":"Schema or catalog name"}
            },"required":["connection","table"],"additionalProperties":false}
            """));
        tools.add(tool("get_table_ddl",
            "Return the CREATE statement (DDL) of a table or view as DBeaver generates it, including indexes and foreign keys.",
            """
            {"type":"object","properties":{
              "connection":{"type":"string","description":"Connection id or name"},
              "table":{"type":"string","description":"Table or view name; 'schema.table' is accepted"},
              "schema":{"type":"string","description":"Schema or catalog name"}
            },"required":["connection","table"],"additionalProperties":false}
            """));
        tools.add(tool("run_query",
            "Execute exactly one read-only SQL statement (SELECT, SHOW, EXPLAIN or DESCRIBE) and return the rows. "
                + "INSERT/UPDATE/DELETE/DDL, multiple statements, SELECT ... INTO and locking reads are rejected. "
                + "Rows are capped at the server's configured limit (" + McpPreferences.getMaxRows() + " by default).",
            """
            {"type":"object","properties":{
              "connection":{"type":"string","description":"Connection id or name"},
              "sql":{"type":"string","description":"A single SELECT / SHOW / EXPLAIN / DESCRIBE statement"},
              "max_rows":{"type":"integer","minimum":1,"description":"Optional row cap for this call (cannot exceed the server limit)"}
            },"required":["connection","sql"],"additionalProperties":false}
            """));
        return tools;
    }

    public JsonObject callTool(String name, JsonObject args) throws JsonRpcError {
        long started = System.nanoTime();
        String connectionRef = optString(args, "connection");
        String detail = "run_query".equals(name) ? optString(args, "sql") : GSON.toJson(args);
        try {
            JsonElement result = dispatch(name, args);
            AuditLog.record(name, connectionRef, "ok", elapsed(started), detail);
            return toolResult(result, false);
        } catch (McpToolException e) {
            AuditLog.record(name, connectionRef, "rejected: " + e.getMessage(), elapsed(started), detail);
            return toolResult(errorText(e.getMessage()), true);
        } catch (JsonRpcError e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP tool '" + name + "' failed", e);
            AuditLog.record(name, connectionRef, "error: " + e.getMessage(), elapsed(started), detail);
            return toolResult(errorText("Internal error: " + e.getMessage()), true);
        }
    }

    private JsonElement dispatch(String name, JsonObject args) throws McpToolException, JsonRpcError {
        switch (name) {
            case "list_connections": {
                JsonArray arr = new JsonArray();
                for (DBPDataSourceContainer c : ConnectionRegistry.exposedContainers()) {
                    arr.add(ConnectionRegistry.describe(c));
                }
                JsonObject out = new JsonObject();
                out.add("connections", arr);
                if (arr.isEmpty()) {
                    out.addProperty("note", "No connections are exposed. In DBeaver open Preferences > MCP Server and tick the connections to expose.");
                }
                return out;
            }
            case "list_schemas":
                return metadata.listSchemas(connection(args));
            case "list_tables":
                return metadata.listTables(connection(args), optString(args, "schema"));
            case "describe_table":
                return metadata.describeTable(connection(args), optString(args, "schema"), requireString(args, "table"));
            case "get_table_ddl":
                return metadata.tableDdl(connection(args), optString(args, "schema"), requireString(args, "table"));
            case "run_query": {
                int limit = McpPreferences.getMaxRows();
                if (args.has("max_rows") && args.get("max_rows").isJsonPrimitive()) {
                    try {
                        limit = Math.max(1, Math.min(limit, args.get("max_rows").getAsInt()));
                    } catch (RuntimeException e) {
                        throw new JsonRpcError(JsonRpcError.INVALID_PARAMS, "'max_rows' must be an integer");
                    }
                }
                return executor.run(connection(args), requireString(args, "sql"), limit, McpPreferences.getTimeoutSeconds());
            }
            default:
                throw new JsonRpcError(JsonRpcError.INVALID_PARAMS, "Unknown tool: " + name);
        }
    }

    private static DBPDataSourceContainer connection(JsonObject args) throws McpToolException {
        return ConnectionRegistry.resolve(optString(args, "connection"));
    }

    private static String optString(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        JsonElement v = args.get(key);
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    private static String requireString(JsonObject args, String key) throws JsonRpcError {
        String v = optString(args, key);
        if (v == null || v.isBlank()) {
            throw new JsonRpcError(JsonRpcError.INVALID_PARAMS, "'" + key + "' is required");
        }
        return v;
    }

    private static JsonObject tool(String name, String description, String schemaJson) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", JsonParser.parseString(schemaJson));
        return t;
    }

    private static JsonObject toolResult(JsonElement payload, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", payload.isJsonPrimitive() ? payload.getAsString() : GSON.toJson(payload));
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contents);
        result.addProperty("isError", isError);
        if (!isError && payload.isJsonObject()) {
            result.add("structuredContent", payload);
        }
        return result;
    }

    private static JsonElement errorText(String message) {
        return new com.google.gson.JsonPrimitive(message);
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
