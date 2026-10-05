package com.arman.bank.runtime;

import io.javalin.Javalin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ServiceRuntime implements AutoCloseable {
    private final Javalin app;
    private final AutoCloseable resources;

    private ServiceRuntime(Javalin app, AutoCloseable resources) {
        this.app = app;
        this.resources = resources;
    }

    public static ServiceRuntime start(String service, int port, Database database) throws IOException {
        return start(service, port, database, config -> {});
    }

    public static ServiceRuntime start(String service, int port, Database database,
            java.util.function.Consumer<io.javalin.config.JavalinConfig> routes) throws IOException {
        return start(service, port, () -> database == null || database.ready(), database, routes);
    }

    /** Technical lifecycle/readiness hooks for services owning multiple resources. */
    public static ServiceRuntime start(String service, int port,
            java.util.function.BooleanSupplier readiness, AutoCloseable resources,
            java.util.function.Consumer<io.javalin.config.JavalinConfig> routes) throws IOException {
        String contract;
        try (var input = Objects.requireNonNull(
                ServiceRuntime.class.getResourceAsStream("/openapi.yaml"), "Missing OpenAPI contract")) {
            contract = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var app = Javalin.create(config -> {
            routes.accept(config);
            config.routes.get("/health/live", ctx -> ctx.contentType("application/json")
                    .result("{\"status\":\"UP\"}"));
            config.routes.get("/health/ready", ctx -> {
                boolean ready;
                try { ready = readiness.getAsBoolean(); }
                catch (RuntimeException unavailable) { ready = false; }
                ctx.status(ready ? 200 : 503).contentType("application/json")
                        .result(ready ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
            });
            config.routes.get("/openapi.yaml", ctx -> ctx.contentType("application/yaml").result(contract));
        });
        app.start(port);
        return new ServiceRuntime(app, resources);
    }

    public static void launch(String service, boolean persistent) throws IOException {
        Database database = persistent ? new Database(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD")) : null;
        try {
            var runtime = start(service, Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), database);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, service + "-shutdown"));
        } catch (Exception e) {
            if (database != null) database.close();
            throw e;
        }
    }

    public static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }

    public int port() { return app.port(); }

    @Override public void close() {
        try { app.stop(); }
        finally {
            if (resources != null) {
                try { resources.close(); }
                catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw new IllegalStateException("Resource shutdown failed", e); }
            }
        }
    }
}
