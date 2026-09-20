package com.lab.todo.web;

import com.lab.todo.config.AppProperties;
import com.lab.todo.service.TaskService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small diagnostics endpoint. Useful during a live review to show which task is
 * answering and which backends it is wired to. Host names are shown; no
 * credential ever is.
 */
@RestController
@RequestMapping("/api")
public class InfoController {

    private final AppProperties properties;
    private final TaskService tasks;
    private final String version;

    public InfoController(AppProperties properties,
                          TaskService tasks,
                          @Value("${spring.application.version:1.0.0}") String version) {
        this.properties = properties;
        this.tasks = tasks;
        this.version = version;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", "todo-app");
        info.put("version", version);
        info.put("environment", properties.getEnvironment());
        info.put("region", System.getenv().getOrDefault("AWS_REGION", "local"));
        info.put("availabilityZone", System.getenv().getOrDefault("AWS_AVAILABILITY_ZONE", "unknown"));
        info.put("taskArn", System.getenv().getOrDefault("ECS_CONTAINER_METADATA_URI_V4", "not-on-ecs"));
        info.put("databaseHost", properties.getDb().getHost());
        info.put("databaseName", properties.getDb().getName());
        info.put("credentialSource",
                properties.getDb().getSecretArn().isBlank()
                        ? "local configuration"
                        : "secretsmanager:" + properties.getDb().getSecretArn());
        info.put("cacheEnabled", properties.getCache().isEnabled());
        info.put("cacheTtlSeconds", properties.getCache().getTtlSeconds());
        info.put("cacheReachable", tasks.cacheReachable());
        return info;
    }
}
