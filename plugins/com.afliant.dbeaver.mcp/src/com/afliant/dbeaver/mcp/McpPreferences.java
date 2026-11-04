package com.afliant.dbeaver.mcp;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.jkiss.dbeaver.Log;

/** Typed access to the plugin preference store. */
public final class McpPreferences {

    private static final Log log = Log.getLog(McpPreferences.class);

    public static final String PORT = "server.port";
    public static final String TOKEN = "server.token";
    public static final String ALLOWED_CONNECTIONS = "connections.allowed";
    public static final String MAX_ROWS = "query.maxRows";
    public static final String TIMEOUT_SECONDS = "query.timeoutSeconds";
    public static final String AUDIT_ENABLED = "audit.enabled";

    public static final int DEFAULT_PORT = 7912;
    public static final int DEFAULT_MAX_ROWS = 500;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private McpPreferences() {
    }

    public static IPreferenceStore store() {
        return McpPlugin.getDefault().getPreferenceStore();
    }

    static void initDefaults(IPreferenceStore store) {
        store.setDefault(PORT, DEFAULT_PORT);
        store.setDefault(MAX_ROWS, DEFAULT_MAX_ROWS);
        store.setDefault(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
        store.setDefault(AUDIT_ENABLED, true);
        store.setDefault(ALLOWED_CONNECTIONS, "");
        store.setDefault(TOKEN, "");
    }

    public static int getPort() {
        int port = store().getInt(PORT);
        return port > 0 ? port : DEFAULT_PORT;
    }

    public static int getMaxRows() {
        return Math.max(1, store().getInt(MAX_ROWS));
    }

    public static int getTimeoutSeconds() {
        return Math.max(1, store().getInt(TIMEOUT_SECONDS));
    }

    public static boolean isAuditEnabled() {
        return store().getBoolean(AUDIT_ENABLED);
    }

    /** Returns the bearer token, generating and persisting one on first use. */
    public static synchronized String getToken() {
        String token = store().getString(TOKEN);
        if (token == null || token.isBlank()) {
            token = regenerateToken();
        }
        return token;
    }

    public static synchronized String regenerateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store().setValue(TOKEN, token);
        save();
        return token;
    }

    public static Set<String> getAllowedConnectionIds() {
        String value = store().getString(ALLOWED_CONNECTIONS);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static void setAllowedConnectionIds(Set<String> ids) {
        store().setValue(ALLOWED_CONNECTIONS, String.join(",", ids));
    }

    public static void save() {
        IPreferenceStore store = store();
        if (store instanceof IPersistentPreferenceStore persistent && persistent.needsSaving()) {
            try {
                persistent.save();
            } catch (IOException e) {
                log.error("Could not save MCP preferences", e);
            }
        }
    }
}
