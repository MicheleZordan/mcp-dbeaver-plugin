package com.afliant.dbeaver.mcp.ui;

import com.afliant.dbeaver.mcp.AuditLog;
import com.afliant.dbeaver.mcp.ConnectionRegistry;
import com.afliant.dbeaver.mcp.McpPreferences;
import com.afliant.dbeaver.mcp.ServerManager;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;

public class StatusHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ServerManager manager = ServerManager.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append(manager.isRunning() ? "Running at " : "Stopped. Configured endpoint: ").append(manager.getEndpoint()).append('\n');
        sb.append("Row limit: ").append(McpPreferences.getMaxRows())
            .append(", query timeout: ").append(McpPreferences.getTimeoutSeconds()).append(" s\n");
        sb.append("Audit log: ").append(McpPreferences.isAuditEnabled() ? AuditLog.file().toString() : "disabled").append("\n\n");
        sb.append("Exposed connections (read-only):\n");
        for (DBPDataSourceContainer c : ConnectionRegistry.exposedContainers()) {
            sb.append("  - ").append(c.getName()).append("  [").append(c.getDriver().getName()).append("]")
                .append(c.isConnected() ? "  connected" : "").append('\n');
        }
        if (ConnectionRegistry.exposedContainers().isEmpty()) {
            sb.append("  (none; tick connections in Preferences > MCP Server)\n");
        }
        DBWorkbench.getPlatformUI().showMessageBox("MCP Server status", sb.toString(), false);
        return null;
    }
}
