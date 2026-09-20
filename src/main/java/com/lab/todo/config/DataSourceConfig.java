package com.lab.todo.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * Builds the connection pool that points at RDS Proxy.
 *
 * <p>The credentials are not part of the task definition. ECS injects only the
 * <em>ARN</em> of the Secrets Manager secret; this class resolves the secret
 * once at startup with the AWS SDK, authenticating with the task role through
 * the container credentials provider.
 *
 * <p>The secret is owned and rotated by RDS itself, so the password read here
 * changes roughly weekly. Nothing caches it beyond the life of the pool: a task
 * started after a rotation picks up the current version on its own.
 *
 * <p>The pool is deliberately small: RDS Proxy already multiplexes and pools
 * connections on the server side, so a large per-task pool only wastes database
 * connections when the service scales out.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public DataSourceConfig(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public DataSource dataSource() {
        AppProperties.Db db = properties.getDb();
        Credentials credentials = resolveCredentials(db);

        HikariConfig config = new HikariConfig();
        config.setPoolName("todo-rds-proxy-pool");
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(credentials.username());
        config.setPassword(credentials.password());
        config.setMaximumPoolSize(db.getPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(db.getConnectionTimeoutMs());
        // Keep connections short lived so the proxy can rebalance after a failover.
        config.setMaxLifetime(600_000);
        config.setIdleTimeout(300_000);
        config.setConnectionTestQuery("SELECT 1");

        log.info("Connecting to {} as {} (credentials from {})",
                db.jdbcUrl(),
                credentials.username(),
                credentials.source());
        return new HikariDataSource(config);
    }

    private Credentials resolveCredentials(AppProperties.Db db) {
        if (!StringUtils.hasText(db.getSecretArn())) {
            log.warn("app.db.secret-arn is not set - falling back to configured username/password. "
                    + "This path is for local development only.");
            requireSupplied("DB_USERNAME", db.getUsername());
            requireSupplied("DB_PASSWORD", db.getPassword());
            return new Credentials(db.getUsername(), db.getPassword(), "local configuration");
        }

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            GetSecretValueResponse response =
                    client.getSecretValue(request -> request.secretId(db.getSecretArn()));
            JsonNode secret = objectMapper.readTree(response.secretString());

            String username = text(secret, "username");
            String password = text(secret, "password");
            if (username == null || password == null) {
                throw new IllegalStateException(
                        "Secret " + db.getSecretArn() + " is missing a username or password field");
            }
            return new Credentials(username, password, "Secrets Manager: " + db.getSecretArn());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to read database credentials from secret " + db.getSecretArn(), e);
        }
    }

    /**
     * There are deliberately no defaults for the local credentials, so a missing
     * one has to fail clearly. An unresolved placeholder is caught alongside a
     * blank value: {@code @ConfigurationProperties} binding does not throw on an
     * unresolvable placeholder, it hands over the literal {@code "${DB_USERNAME}"},
     * which would otherwise surface much later as a confusing authentication
     * failure against PostgreSQL.
     */
    private static void requireSupplied(String variable, String value) {
        if (!StringUtils.hasText(value) || value.startsWith("${")) {
            throw new IllegalStateException(
                    variable + " is not set. With app.db.secret-arn empty the database "
                            + "credentials come from the environment: copy .env.example to .env "
                            + "and export it (see the Running locally section of README.md).");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record Credentials(String username, String password, String source) {
    }
}
