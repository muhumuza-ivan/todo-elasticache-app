package com.lab.todo.service;

import com.lab.todo.config.AppProperties;
import com.lab.todo.domain.Task;
import com.lab.todo.domain.TaskDto;
import com.lab.todo.domain.TaskRequest;
import com.lab.todo.repository.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache-aside over ElastiCache for Redis.
 *
 * <p>Reads check Redis first and fall back to PostgreSQL (through RDS Proxy) on
 * a miss, repopulating the key with a short TTL. Writes go to PostgreSQL first
 * and then invalidate the keys they touched, so the cache can never serve a
 * value the database has not accepted.
 *
 * <p>Every Redis call is best-effort: if the cache is unreachable the request
 * still succeeds against the database, just more slowly.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final TypeReference<List<TaskDto>> TASK_LIST = new TypeReference<>() {
    };

    private final TaskRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties.Cache cacheProperties;

    public TaskService(TaskRepository repository,
                       StringRedisTemplate redis,
                       ObjectMapper objectMapper,
                       AppProperties properties) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheProperties = properties.getCache();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Cached<List<TaskDto>> listTasks() {
        return listTasks(false);
    }

    /**
     * @param forceFresh skip the cache and read PostgreSQL, then repopulate.
     *     Writes made through this application evict the keys they touch, so
     *     the cached list is normally correct - but anything that changes the
     *     database behind the application's back (another instance, a manual
     *     statement) stays invisible until the TTL lapses. The Refresh control
     *     in the UI sets this so that it genuinely refreshes.
     */
    @Transactional(readOnly = true)
    public Cached<List<TaskDto>> listTasks(boolean forceFresh) {
        long started = System.nanoTime();
        String key = listKey();

        List<TaskDto> cached = forceFresh ? null : readJson(key, TASK_LIST);
        if (cached != null) {
            return new Cached<>(cached, true, millisSince(started));
        }

        List<TaskDto> tasks = repository.findAllByOrderByCreatedAtDesc().stream().map(TaskDto::from).toList();
        writeJson(key, tasks);
        return new Cached<>(tasks, false, millisSince(started));
    }

    @Transactional(readOnly = true)
    public Cached<TaskDto> getTask(Long id) {
        long started = System.nanoTime();
        String key = taskKey(id);

        TaskDto cached = readJson(key, new TypeReference<TaskDto>() {
        });
        if (cached != null) {
            return new Cached<>(cached, true, millisSince(started));
        }

        TaskDto task = repository.findById(id).map(TaskDto::from).orElseThrow(() -> new TaskNotFoundException(id));
        writeJson(key, task);
        return new Cached<>(task, false, millisSince(started));
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    public TaskDto create(TaskRequest request) {
        Task task = new Task();
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setCompleted(Boolean.TRUE.equals(request.completed()));

        TaskDto saved = TaskDto.from(repository.save(task));
        evict(listKey(), taskKey(saved.id()));
        return saved;
    }

    @Transactional
    public TaskDto update(Long id, TaskRequest request) {
        Task task = repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        if (request.completed() != null) {
            task.setCompleted(request.completed());
        }

        TaskDto saved = TaskDto.from(repository.save(task));
        evict(listKey(), taskKey(id));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new TaskNotFoundException(id);
        }
        repository.deleteById(id);
        evict(listKey(), taskKey(id));
    }

    /** Drops every key this application owns. Exposed for demos. */
    @Transactional(readOnly = true)
    public long flushCache() {
        List<String> keys = new ArrayList<>();
        keys.add(listKey());
        repository.findAllByOrderByCreatedAtDesc().forEach(task -> keys.add(taskKey(task.getId())));
        Long removed = tryRedis(() -> redis.delete(keys), "flush");
        return removed == null ? 0 : removed;
    }

    public boolean cacheReachable() {
        Boolean ok = tryRedis(() -> {
            redis.hasKey(listKey());
            return Boolean.TRUE;
        }, "ping");
        return Boolean.TRUE.equals(ok);
    }

    // ------------------------------------------------------------- cache glue

    private <T> T readJson(String key, TypeReference<T> type) {
        if (!cacheProperties.isEnabled()) {
            return null;
        }
        String json = tryRedis(() -> redis.opsForValue().get(key), "get " + key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // A payload we cannot parse is a stale schema, not a fatal error.
            log.warn("Discarding unreadable cache entry {}: {}", key, e.getMessage());
            evict(key);
            return null;
        }
    }

    private void writeJson(String key, Object value) {
        if (!cacheProperties.isEnabled()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            tryRedis(() -> {
                redis.opsForValue().set(key, json, Duration.ofSeconds(cacheProperties.getTtlSeconds()));
                return Boolean.TRUE;
            }, "set " + key);
        } catch (Exception e) {
            log.warn("Could not serialise {} for the cache: {}", key, e.getMessage());
        }
    }

    private void evict(String... keys) {
        tryRedis(() -> redis.delete(List.of(keys)), "evict");
    }

    private <T> T tryRedis(RedisCall<T> call, String description) {
        try {
            return call.execute();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable during '{}' ({}); serving from the database", description, e.getMessage());
            return null;
        }
    }

    private String listKey() {
        return cacheProperties.getKeyPrefix() + ":tasks:all";
    }

    private String taskKey(Long id) {
        return cacheProperties.getKeyPrefix() + ":task:" + id;
    }

    private static long millisSince(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    @FunctionalInterface
    private interface RedisCall<T> {
        T execute();
    }
}
