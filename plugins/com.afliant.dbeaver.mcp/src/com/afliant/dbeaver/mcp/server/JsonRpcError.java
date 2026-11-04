package com.afliant.dbeaver.mcp.server;

/** A JSON-RPC protocol-level error (as opposed to a tool error, which is returned as a successful result with isError=true). */
public class JsonRpcError extends Exception {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final int code;

    public JsonRpcError(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
