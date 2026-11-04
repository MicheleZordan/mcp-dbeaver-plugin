package com.afliant.dbeaver.mcp.exec;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCDataSource;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import com.afliant.dbeaver.mcp.McpToolException;
import com.afliant.dbeaver.mcp.guard.ReadOnlyGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Executes guarded statements on a dedicated, isolated execution context per connection.
 * The JDBC connection is read-only, autocommit is off, the server session is switched to
 * read-only transactions where the database supports it, and every call ends with a rollback.
 */
public final class QueryExecutor {

    private static final Log log = Log.getLog(QueryExecutor.class);
    private static final int MAX_CELL_CHARS = 4000;
    private static final int MAX_BINARY_BYTES = 64;

    private static final class Slot {
        final DBPDataSourceContainer container;
        JDBCExecutionContext context;
        boolean serverReadOnly;

        Slot(DBPDataSourceContainer container) {
            this.container = container;
        }
    }

    private final Map<String, Slot> slots = new HashMap<>();

    public JsonObject run(DBPDataSourceContainer container, String sql, int maxRows, int timeoutSeconds) throws McpToolException {
        DBPDataSource dataSource = ensureConnected(container);
        if (!(dataSource instanceof JDBCDataSource jdbcDataSource)) {
            throw new McpToolException("Connection '" + container.getName() + "' is not a JDBC data source and cannot be queried through MCP.");
        }
        ReadOnlyGuard.Verdict verdict = ReadOnlyGuard.check(dataSource, sql);

        Slot slot = slot(container);
        synchronized (slot) {
            try {
                return execute(slot, jdbcDataSource, verdict, maxRows, timeoutSeconds);
            } catch (SQLException e) {
                if (connectionIsDead(slot)) {
                    log.warn("MCP context for '" + container.getName() + "' is dead, reopening once");
                    closeSlot(slot);
                    try {
                        return execute(slot, jdbcDataSource, verdict, maxRows, timeoutSeconds);
                    } catch (SQLException | DBException retry) {
                        throw new McpToolException("Query failed: " + retry.getMessage(), retry);
                    }
                }
                throw new McpToolException("Query failed: " + e.getMessage(), e);
            } catch (DBException e) {
                throw new McpToolException("Could not open a read-only context on '" + container.getName() + "': " + e.getMessage(), e);
            }
        }
    }

    public static DBPDataSource ensureConnected(DBPDataSourceContainer container) throws McpToolException {
        try {
            if (!container.isConnected()) {
                container.connect(new VoidProgressMonitor(), true, true);
            }
        } catch (DBException e) {
            throw new McpToolException("Could not connect to '" + container.getName() + "': " + e.getMessage(), e);
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new McpToolException("Connection '" + container.getName() + "' is not connected.");
        }
        return dataSource;
    }

    public synchronized void closeAll() {
        for (Slot slot : slots.values()) {
            synchronized (slot) {
                closeSlot(slot);
            }
        }
        slots.clear();
    }

    private synchronized Slot slot(DBPDataSourceContainer container) {
        return slots.computeIfAbsent(container.getId(), id -> new Slot(container));
    }

    private JsonObject execute(Slot slot, JDBCDataSource dataSource, ReadOnlyGuard.Verdict verdict, int maxRows, int timeoutSeconds)
        throws SQLException, DBException {
        Connection connection = connection(slot, dataSource);
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(maxRows + 1);
            statement.setQueryTimeout(timeoutSeconds);
            boolean hasResultSet = statement.execute(verdict.sql());
            JsonObject out = new JsonObject();
            out.addProperty("connection", slot.container.getName());
            out.addProperty("statement_kind", verdict.kind().name());
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    readRows(rs, maxRows, out);
                }
            } else {
                out.addProperty("update_count", statement.getUpdateCount());
            }
            out.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            out.addProperty("server_side_read_only", slot.serverReadOnly);
            out.addProperty("transaction", "rolled back");
            return out;
        } finally {
            try {
                connection.rollback();
            } catch (SQLException e) {
                log.warn("Rollback after MCP query failed", e);
            }
        }
    }

    private Connection connection(Slot slot, JDBCDataSource dataSource) throws DBException, SQLException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        if (slot.context == null || !slot.context.isConnected()) {
            DBCExecutionContext ctx = dataSource.getDefaultInstance().openIsolatedContext(monitor, "MCP read-only", null);
            if (!(ctx instanceof JDBCExecutionContext jdbcContext)) {
                ctx.close();
                throw new DBException("Isolated execution context is not JDBC-based");
            }
            slot.context = jdbcContext;
            Connection connection = jdbcContext.getConnection(monitor);
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            slot.serverReadOnly = applyServerSideReadOnly(connection, dataSource);
            return connection;
        }
        Connection connection = slot.context.getConnection(monitor);
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
        return connection;
    }

    /** Asks the server itself to refuse writes for this session. Returns true only when the server confirmed it. */
    private static boolean applyServerSideReadOnly(Connection connection, JDBCDataSource dataSource) {
        String dialect = String.valueOf(dataSource.getSQLDialect().getDialectName()).toLowerCase(Locale.ROOT);
        try (Statement st = connection.createStatement()) {
            if (dialect.contains("mysql") || dialect.contains("maria")) {
                st.execute("SET SESSION TRANSACTION READ ONLY");
                boolean confirmed = readFlag(st, "SELECT @@session.transaction_read_only") || readFlag(st, "SELECT @@session.tx_read_only");
                connection.rollback();
                return confirmed;
            }
            if (dialect.contains("postgre")) {
                st.execute("SET default_transaction_read_only = on");
                boolean confirmed = "on".equalsIgnoreCase(readString(st, "SHOW default_transaction_read_only"));
                connection.rollback();
                return confirmed;
            }
        } catch (SQLException e) {
            log.warn("Could not enable server-side read-only mode on '" + dataSource.getContainer().getName() + "': " + e.getMessage());
        }
        return false;
    }

    private static boolean readFlag(Statement st, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private static String readString(Statement st, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static boolean connectionIsDead(Slot slot) {
        try {
            if (slot.context == null || !slot.context.isConnected()) {
                return true;
            }
            Connection c = slot.context.getConnectionOrNull();
            return c == null || c.isClosed() || !c.isValid(3);
        } catch (SQLException e) {
            return true;
        }
    }

    private static void closeSlot(Slot slot) {
        if (slot.context != null) {
            try {
                slot.context.close();
            } catch (Exception e) {
                log.debug("Error closing MCP context", e);
            }
            slot.context = null;
            slot.serverReadOnly = false;
        }
    }

    private static void readRows(ResultSet rs, int maxRows, JsonObject out) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        JsonArray columns = new JsonArray();
        for (int i = 1; i <= count; i++) {
            JsonObject col = new JsonObject();
            col.addProperty("name", meta.getColumnLabel(i));
            col.addProperty("type", meta.getColumnTypeName(i));
            columns.add(col);
        }
        JsonArray rows = new JsonArray();
        int n = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (n >= maxRows) {
                truncated = true;
                break;
            }
            JsonArray row = new JsonArray();
            for (int i = 1; i <= count; i++) {
                row.add(cell(rs, i));
            }
            rows.add(row);
            n++;
        }
        out.add("columns", columns);
        out.add("rows", rows);
        out.addProperty("row_count", n);
        out.addProperty("truncated", truncated);
        if (truncated) {
            out.addProperty("note", "Result limited to " + maxRows + " rows. Add a WHERE clause or LIMIT, or raise max_rows.");
        }
    }

    private static JsonElement cell(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number number) {
            if (value instanceof Double d && (d.isNaN() || d.isInfinite()) || value instanceof Float f && (f.isNaN() || f.isInfinite())) {
                return new JsonPrimitive(value.toString());
            }
            return new JsonPrimitive(number);
        }
        if (value instanceof byte[] bytes) {
            return new JsonPrimitive(hex(bytes));
        }
        if (value instanceof Blob blob) {
            try {
                long len = blob.length();
                byte[] head = blob.getBytes(1, (int) Math.min(len, MAX_BINARY_BYTES));
                return new JsonPrimitive(hex(head) + (len > MAX_BINARY_BYTES ? " ... (" + len + " bytes)" : ""));
            } finally {
                try { blob.free(); } catch (SQLException ignore) { }
            }
        }
        if (value instanceof Clob clob) {
            try {
                long len = clob.length();
                String head = clob.getSubString(1, (int) Math.min(len, MAX_CELL_CHARS));
                return new JsonPrimitive(len > MAX_CELL_CHARS ? head + "... [truncated, " + len + " chars]" : head);
            } finally {
                try { clob.free(); } catch (SQLException ignore) { }
            }
        }
        String s = String.valueOf(value);
        if (s.length() > MAX_CELL_CHARS) {
            s = s.substring(0, MAX_CELL_CHARS) + "... [truncated, " + s.length() + " chars]";
        }
        return new JsonPrimitive(s);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        int n = Math.min(bytes.length, MAX_BINARY_BYTES);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        if (bytes.length > n) {
            sb.append(" ... (").append(bytes.length).append(" bytes)");
        }
        return sb.toString();
    }

}
