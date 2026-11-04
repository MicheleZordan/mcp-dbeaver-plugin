package com.afliant.dbeaver.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceFolder;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import com.google.gson.JsonObject;

/** Looks up DBeaver connections and applies the MCP allowlist. */
public final class ConnectionRegistry {

    private ConnectionRegistry() {
    }

    /** Every connection configured in any DBeaver project. */
    public static List<DBPDataSourceContainer> allContainers() {
        List<DBPDataSourceContainer> out = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            if (registry != null) {
                out.addAll(registry.getDataSources());
            }
        }
        return out;
    }

    /** Only the connections the user explicitly exposed in the MCP preferences. */
    public static List<DBPDataSourceContainer> exposedContainers() {
        Set<String> allowed = McpPreferences.getAllowedConnectionIds();
        return allContainers().stream().filter(c -> allowed.contains(c.getId())).toList();
    }

    /** Resolves a client-supplied reference (id, or unique name) to an exposed connection. */
    public static DBPDataSourceContainer resolve(String ref) throws McpToolException {
        if (ref == null || ref.isBlank()) {
            throw new McpToolException("'connection' is required. Call list_connections to see the available ids and names.");
        }
        List<DBPDataSourceContainer> exposed = exposedContainers();
        for (DBPDataSourceContainer c : exposed) {
            if (c.getId().equals(ref)) {
                return c;
            }
        }
        List<DBPDataSourceContainer> byName = exposed.stream().filter(c -> c.getName().equalsIgnoreCase(ref)).toList();
        if (byName.size() == 1) {
            return byName.get(0);
        }
        if (byName.size() > 1) {
            throw new McpToolException("Connection name '" + ref + "' is ambiguous. Use the connection id instead.");
        }
        String names = exposed.stream().map(c -> c.getName() + " (" + c.getId() + ")").collect(Collectors.joining(", "));
        throw new McpToolException("Connection '" + ref + "' is not exposed to MCP. Exposed connections: " + (names.isEmpty() ? "none" : names));
    }

    public static JsonObject describe(DBPDataSourceContainer c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("name", c.getName());
        o.addProperty("driver", c.getDriver() != null ? c.getDriver().getName() : null);
        o.addProperty("project", c.getProject() != null ? c.getProject().getName() : null);
        DBPDataSourceFolder folder = c.getFolder();
        if (folder != null) {
            o.addProperty("folder", folder.getFolderPath());
        }
        DBPConnectionConfiguration cfg = c.getConnectionConfiguration();
        if (cfg != null) {
            o.addProperty("host", cfg.getHostName());
            o.addProperty("port", cfg.getHostPort());
            o.addProperty("database", cfg.getDatabaseName());
            o.addProperty("user", cfg.getUserName());
        }
        if (c.getDescription() != null && !c.getDescription().isBlank()) {
            o.addProperty("description", c.getDescription());
        }
        o.addProperty("connected", c.isConnected());
        o.addProperty("dbeaver_read_only_flag", c.isConnectionReadOnly());
        return o;
    }
}
