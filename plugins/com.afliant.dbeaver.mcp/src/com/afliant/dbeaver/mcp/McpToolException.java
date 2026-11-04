package com.afliant.dbeaver.mcp;

/** An error that is reported back to the MCP client as a tool error (isError=true), not as a protocol failure. */
public class McpToolException extends Exception {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
