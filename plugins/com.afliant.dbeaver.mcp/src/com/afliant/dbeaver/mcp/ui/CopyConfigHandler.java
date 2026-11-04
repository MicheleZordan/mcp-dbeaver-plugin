package com.afliant.dbeaver.mcp.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

public class CopyConfigHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        new ClientConfigDialog(HandlerUtil.getActiveShell(event), ClientConfig.build()).open();
        return null;
    }
}
