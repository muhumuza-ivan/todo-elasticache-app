package com.lab.todo.domain;

import java.time.Instant;

/**
 * What the API returns and what is stored in Redis. Keeping the cache payload
 * separate from the JPA entity means nothing Hibernate-specific is ever
 * serialised into the cache.
 */
public record TaskDto(
        Long id,
        String title,
        String description,
        boolean completed,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskDto from(Task task) {
        return new TaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.isCompleted(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
