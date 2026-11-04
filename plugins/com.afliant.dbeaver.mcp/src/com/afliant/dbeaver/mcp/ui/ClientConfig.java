package com.afliant.dbeaver.mcp.ui;

import com.afliant.dbeaver.mcp.McpPreferences;

/** Builds ready-to-paste client configuration snippets for the running server. */
final class ClientConfig {

    private ClientConfig() {
    }

    static String build() {
        int port = McpPreferences.getPort();
        String token = McpPreferences.getToken();
        String url = "http://127.0.0.1:" + port + "/mcp";
        return """
            # DBeaver MCP Server - client configuration
            # Endpoint: %1$s   (loopback only, bearer token required)
            # The server must be started from DBeaver's MCP menu before clients can connect.

            ## Claude Code (run once in a terminal)
            claude mcp add --transport http dbeaver %1$s --header "Authorization: Bearer %2$s"

            ## Cursor  (~/.cursor/mcp.json or <project>/.cursor/mcp.json)
            {
              "mcpServers": {
                "dbeaver": {
                  "url": "%1$s",
                  "headers": { "Authorization": "Bearer %2$s" }
                }
              }
            }

            ## Claude Desktop  (claude_desktop_config.json; uses the mcp-remote bridge, needs Node.js)
            {
              "mcpServers": {
                "dbeaver": {
                  "command": "npx",
                  "args": ["-y", "mcp-remote", "%1$s", "--header", "Authorization:${AUTH_HEADER}"],
                  "env": { "AUTH_HEADER": "Bearer %2$s" }
                }
              }
            }
            """.formatted(url, token);
    }
}
