package com.afliant.dbeaver.mcp;

import java.io.IOException;

import org.jkiss.dbeaver.Log;

import com.afliant.dbeaver.mcp.exec.QueryExecutor;
import com.afliant.dbeaver.mcp.meta.MetadataService;
import com.afliant.dbeaver.mcp.server.McpHttpServer;
import com.afliant.dbeaver.mcp.server.McpTools;

/** Owns the MCP server lifecycle. The server never starts with DBeaver; it is started from the MCP menu. */
public final class ServerManager {

    private static final Log log = Log.getLog(ServerManager.class);
    private static final ServerManager INSTANCE = new ServerManager();

    private McpHttpServer server;
    private QueryExecutor executor;

    private ServerManager() {
    }

    public static ServerManager getInstance() {
        return INSTANCE;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        int port = McpPreferences.getPort();
        String token = McpPreferences.getToken();
        executor = new QueryExecutor();
        String version = McpPlugin.getDefault().getBundle().getVersion().toString();
        server = McpHttpServer.start(port, token, new McpTools(executor, new MetadataService()), version);
        log.info("MCP server started on port " + port + " exposing " + ConnectionRegistry.exposedContainers().size() + " connection(s)");
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } finally {
            server = null;
        }
        if (executor != null) {
            executor.closeAll();
            executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String getEndpoint() {
        return server != null ? server.getEndpoint() : "http://127.0.0.1:" + McpPreferences.getPort() + "/mcp";
    }
}
