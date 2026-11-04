package com.afliant.dbeaver.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.jkiss.dbeaver.Log;

/** Append-only log of every MCP tool call, stored in the plugin state folder of the DBeaver workspace. */
public final class AuditLog {

    private static final Log log = Log.getLog(AuditLog.class);
    private static final int MAX_DETAIL = 4000;

    private AuditLog() {
    }

    public static Path file() {
        return McpPlugin.getDefault().getStateLocation().toPath().resolve("mcp-audit.log");
    }

    public static synchronized void record(String tool, String connection, String outcome, long millis, String detail) {
        if (!McpPreferences.isAuditEnabled()) {
            return;
        }
        String safeDetail = detail == null ? "" : detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safeDetail.length() > MAX_DETAIL) {
            safeDetail = safeDetail.substring(0, MAX_DETAIL) + "...";
        }
        String line = String.join("\t",
            ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            tool,
            connection == null ? "-" : connection,
            outcome,
            millis + "ms",
            safeDetail) + System.lineSeparator();
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write MCP audit log", e);
        }
    }
}
