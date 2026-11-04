package com.afliant.dbeaver.mcp.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jkiss.dbeaver.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal MCP "Streamable HTTP" transport on the JDK's built-in HTTP server.
 * Loopback only, bearer-token protected, stateless (every POST carries a complete JSON-RPC message).
 */
public final class McpHttpServer {

    private static final Log log = Log.getLog(McpHttpServer.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String PATH = "/mcp";
    private static final String SERVER_NAME = "dbeaver-mcp";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final HttpServer server;
    private final ExecutorService pool;
    private final byte[] expectedAuth;
    private final McpTools tools;
    private final String version;

    private McpHttpServer(HttpServer server, ExecutorService pool, String token, McpTools tools, String version) {
        this.server = server;
        this.pool = pool;
        this.expectedAuth = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.tools = tools;
        this.version = version;
    }

    public static McpHttpServer start(int port, String token, McpTools tools, String version) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mcp-http");
            t.setDaemon(true);
            return t;
        });
        McpHttpServer instance = new McpHttpServer(httpServer, pool, token, tools, version);
        httpServer.createContext(PATH, instance::handle);
        httpServer.setExecutor(pool);
        httpServer.start();
        log.info("MCP server listening on http://127.0.0.1:" + port + PATH);
        return instance;
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("MCP server stopped");
    }

    public String getEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    // --- HTTP ------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !isLocalOrigin(origin)) {
                send(exchange, 403, "text/plain", "Forbidden origin");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"" + SERVER_NAME + "\"");
                send(exchange, 401, "text/plain", "Unauthorized: missing or invalid bearer token");
                return;
            }
            String method = exchange.getRequestMethod();
            if ("DELETE".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (!"POST".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "POST, DELETE");
                send(exchange, 405, "text/plain", "Method not allowed; this server only supports POST (no server-initiated streams)");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement root;
            try {
                root = JsonParser.parseString(body);
            } catch (JsonSyntaxException e) {
                send(exchange, 400, "application/json", GSON.toJson(error(null, JsonRpcError.PARSE_ERROR, "Parse error: " + e.getMessage())));
                return;
            }
            String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sessionId != null) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            }
            if (root.isJsonArray()) {
                JsonArray responses = new JsonArray();
                for (JsonElement item : root.getAsJsonArray()) {
                    JsonObject response = item.isJsonObject() ? process(item.getAsJsonObject()) : error(null, JsonRpcError.INVALID_REQUEST, "Invalid request");
                    if (response != null) {
                        responses.add(response);
                    }
                }
                if (responses.isEmpty()) {
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    send(exchange, 200, "application/json", GSON.toJson(responses));
                }
            } else if (root.isJsonObject()) {
                JsonObject response = process(root.getAsJsonObject());
                if (response == null) {
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    send(exchange, 200, "application/json", GSON.toJson(response));
                }
            } else {
                send(exchange, 400, "application/json", GSON.toJson(error(null, JsonRpcError.INVALID_REQUEST, "Invalid request")));
            }
        } catch (Exception e) {
            log.error("MCP request failed", e);
            try {
                send(exchange, 500, "text/plain", "Internal error");
            } catch (IOException ignore) {
                // nothing else to do
            }
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.isEmpty()) {
            return false;
        }
        byte[] actual = values.get(0).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedAuth, actual);
    }

    private static boolean isLocalOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // --- JSON-RPC --------------------------------------------------------------------------------------

    /** Returns the response object, or null for notifications (which get no response). */
    private JsonObject process(JsonObject message) {
        JsonElement id = message.get("id");
        boolean notification = id == null || id.isJsonNull();
        if (!message.has("method")) {
            // A response from the client (we never send requests), ignore.
            return null;
        }
        String method = message.get("method").getAsString();
        JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
        try {
            JsonElement result = dispatch(method, params);
            if (notification) {
                return null;
            }
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        } catch (JsonRpcError e) {
            return notification ? null : error(id, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("MCP method '" + method + "' failed", e);
            return notification ? null : error(id, JsonRpcError.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    private JsonElement dispatch(String method, JsonObject params) throws JsonRpcError {
        switch (method) {
            case "initialize": {
                String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : DEFAULT_PROTOCOL;
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", SUPPORTED_PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL);
                JsonObject capabilities = new JsonObject();
                JsonObject toolsCap = new JsonObject();
                toolsCap.addProperty("listChanged", false);
                capabilities.add("tools", toolsCap);
                result.add("capabilities", capabilities);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", SERVER_NAME);
                serverInfo.addProperty("title", "DBeaver MCP Server (read-only)");
                serverInfo.addProperty("version", version);
                result.add("serverInfo", serverInfo);
                result.addProperty("instructions", McpTools.INSTRUCTIONS.strip());
                return result;
            }
            case "notifications/initialized":
            case "notifications/cancelled":
            case "notifications/roots/list_changed":
                return new JsonObject();
            case "ping":
                return new JsonObject();
            case "tools/list": {
                JsonObject result = new JsonObject();
                result.add("tools", tools.listTools());
                return result;
            }
            case "tools/call": {
                if (!params.has("name")) {
                    throw new JsonRpcError(JsonRpcError.INVALID_PARAMS, "'name' is required");
                }
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
                return tools.callTool(params.get("name").getAsString(), args);
            }
            default:
                throw new JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + method);
        }
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("error", err);
        return response;
    }
}
