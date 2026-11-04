package com.afliant.dbeaver.mcp.ui;

import com.afliant.dbeaver.mcp.ConnectionRegistry;
import com.afliant.dbeaver.mcp.ServerManager;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.jkiss.dbeaver.runtime.DBWorkbench;

public class StartServerHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ServerManager manager = ServerManager.getInstance();
        try {
            manager.start();
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError("MCP Server", "Could not start the MCP server on " + manager.getEndpoint()
                + ". Is the port already in use? Change it in Preferences > MCP Server.", e);
            return null;
        }
        int exposed = ConnectionRegistry.exposedContainers().size();
        String message = "MCP server running at " + manager.getEndpoint() + "\n\n"
            + (exposed == 0
                ? "No connections are exposed yet. Open Preferences > MCP Server and tick the connections clients may query."
                : exposed + " connection(s) exposed, read-only.")
            + "\n\nUse MCP > Copy Client Configuration to configure Claude Code, Claude Desktop or Cursor.";
        DBWorkbench.getPlatformUI().showMessageBox("MCP Server", message, false);
        return null;
    }

    @Override
    public boolean isEnabled() {
        return !ServerManager.getInstance().isRunning();
    }
}
