package com.smartlib.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Tiny programmable HTTP server standing in for Gemini and Razorpay in tests. */
public final class StubServer {

    public record Recorded(String method, String path, String body, String authorization, String apiKey) {
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange, String body) throws Exception;
    }

    private final HttpServer server;
    private final Map<String, Deque<Handler>> queued = new ConcurrentHashMap<>();
    private final Map<String, Handler> defaults = new ConcurrentHashMap<>();
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

    private StubServer(HttpServer server) {
        this.server = server;
    }

    public static StubServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubServer stub = new StubServer(server);
            server.createContext("/", stub::dispatch);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        queued.clear();
        defaults.clear();
        recorded.clear();
    }

    public void on(String pathPrefix, Handler handler) {
        defaults.put(pathPrefix, handler);
    }

    public void enqueue(String pathPrefix, Handler handler) {
        queued.computeIfAbsent(pathPrefix, k -> new ArrayDeque<>()).add(handler);
    }

    public List<Recorded> requests(String pathPrefix) {
        synchronized (recorded) {
            return recorded.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        recorded.add(new Recorded(ex.getRequestMethod(), path, body, ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("x-goog-api-key")));
        Handler handler = null;
        String best = "";
        for (Map.Entry<String, Deque<Handler>> e : queued.entrySet()) {
            if (path.startsWith(e.getKey()) && e.getKey().length() > best.length() && !e.getValue().isEmpty()) {
                best = e.getKey();
            }
        }
        if (!best.isEmpty()) {
            handler = queued.get(best).poll();
        }
        if (handler == null) {
            for (Map.Entry<String, Handler> e : defaults.entrySet()) {
                if (path.startsWith(e.getKey()) && e.getKey().length() >= best.length()) {
                    best = e.getKey();
                    handler = e.getValue();
                }
            }
        }
        try {
            if (handler == null) {
                json(ex, 404, "{\"error\":{\"code\":404,\"message\":\"no stub\"}}");
            } else {
                handler.handle(ex, body);
            }
        } catch (Exception e) {
            // client went away (e.g. timeout test) - ignore
        } finally {
            ex.close();
        }
    }

    public static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Writes Gemini-style SSE: each payload becomes one "data: ..." event. */
    public static void sse(HttpExchange ex, long initialDelayMs, long gapMs, String... payloads) throws Exception {
        if (initialDelayMs > 0) {
            Thread.sleep(initialDelayMs);
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        for (String p : payloads) {
            out.write(("data: " + p + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (gapMs > 0) {
                Thread.sleep(gapMs);
            }
        }
        out.close();
    }

    public static String textChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + quote(text) + "}]}}]}";
    }

    public static String finalChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + quote(text) + "}]},\"finishReason\":\"STOP\"}]}";
    }

    public static String functionCall(String name, String argsJson) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + name + "\",\"args\":" + argsJson
                + "},\"thoughtSignature\":\"sig-abc\"}]},\"finishReason\":\"STOP\"}]}";
    }

    public static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
