package com.afliant.dbeaver.mcp;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/** Bundle activator. Holds the plugin singleton and shuts the server down with DBeaver. */
public class McpPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.afliant.dbeaver.mcp";

    private static McpPlugin instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        McpPreferences.initDefaults(getPreferenceStore());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        ServerManager.getInstance().stop();
        instance = null;
        super.stop(context);
    }

    public static McpPlugin getDefault() {
        return instance;
    }
}
