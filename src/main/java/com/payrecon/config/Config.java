package com.payrecon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings come from the .env file in the project folder, and real environment variables override them.
 * Passwords are never hard-coded: if one is missing we stop with a clear message.
 */
public final class Config {

    private final Map<String, String> values = new HashMap<>();

    private Config() {
    }

    public static Config load() {
        Config config = new Config();
        Path envFile = Paths.get(".env");
        if (Files.isRegularFile(envFile)) {
            try {
                for (String raw : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    boolean quoted = value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")));
                    if (quoted) {
                        value = value.substring(1, value.length() - 1);
                    }
                    config.values.put(key, value);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not read .env: " + e.getMessage(), e);
            }
        }
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("PAYRECON_") || key.startsWith("MYSQL_")) {
                config.values.put(key, value);
            }
        });
        return config;
    }

    public String get(String key, String defaultValue) {
        String value = values.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    public String require(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not set. Copy .env.example to .env and fill it in (see RUN.md).");
        }
        return value;
    }

    public String mysqlUrl() {
        String base = get("PAYRECON_MYSQL_URL", "jdbc:mysql://localhost:3307/payrecon");
        String params = "rewriteBatchedStatements=true&sslMode=DISABLED&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&connectTimeout=5000";
        return base + (base.contains("?") ? "&" : "?") + params;
    }

    public String mysqlUser() {
        return get("PAYRECON_MYSQL_USER", "payrecon");
    }

    public String mysqlPassword() {
        return require("PAYRECON_MYSQL_PASSWORD");
    }

    public String mongoUri() {
        return get("PAYRECON_MONGO_URI", "mongodb://localhost:27017");
    }

    public String mongoDatabase() {
        return get("PAYRECON_MONGO_DB", "payrecon");
    }

    public String hiveContainer() {
        return get("PAYRECON_HIVE_CONTAINER", "payrecon-hive");
    }

    public Path dataDir() {
        return Paths.get(get("PAYRECON_DATA_DIR", "data")).toAbsolutePath().normalize();
    }

    public int apiPort() {
        return Integer.parseInt(get("PAYRECON_API_PORT", "8090"));
    }
}
