package com.afliant.dbeaver.mcp.ui;

import com.afliant.dbeaver.mcp.ServerManager;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.jkiss.dbeaver.runtime.DBWorkbench;

public class StopServerHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ServerManager.getInstance().stop();
        DBWorkbench.getPlatformUI().showMessageBox("MCP Server", "MCP server stopped. Isolated read-only connections were closed.", false);
        return null;
    }

    @Override
    public boolean isEnabled() {
        return ServerManager.getInstance().isRunning();
    }
}
