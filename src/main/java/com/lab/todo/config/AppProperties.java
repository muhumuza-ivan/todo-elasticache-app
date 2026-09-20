package com.lab.todo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the container needs to reach its backing services.
 *
 * <p>In AWS each value arrives as an environment variable that ECS resolves from
 * SSM Parameter Store at task start (see {@code taskdef.json}); the defaults in
 * {@code application.yaml} only apply when running locally.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String environment = "local";
    private final Db db = new Db();
    private final Cache cache = new Cache();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Db getDb() {
        return db;
    }

    public Cache getCache() {
        return cache;
    }

    public static class Db {
        /** RDS Proxy endpoint - the application never addresses the instance. */
        private String host = "localhost";
        private int port = 5432;
        private String name = "todoapp";
        /** Secrets Manager secret holding {"username","password"}; blank locally. */
        private String secretArn = "";
        /** Only used when no secret name is configured. */
        private String username = "postgres";
        private String password = "postgres";
        private String sslMode = "require";
        private int poolSize = 5;
        private long connectionTimeoutMs = 10_000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSecretArn() {
            return secretArn;
        }

        public void setSecretArn(String secretArn) {
            this.secretArn = secretArn;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSslMode() {
            return sslMode;
        }

        public void setSslMode(String sslMode) {
            this.sslMode = sslMode;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }

        public String jdbcUrl() {
            return "jdbc:postgresql://%s:%d/%s?sslmode=%s".formatted(host, port, name, sslMode);
        }
    }

    public static class Cache {
        private long ttlSeconds = 60;
        private String keyPrefix = "todo";
        /** When false the service goes straight to the database (useful for demos). */
        private boolean enabled = true;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
