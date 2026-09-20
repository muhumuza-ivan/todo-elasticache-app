package com.lab.todo;

import static org.assertj.core.api.Assertions.assertThat;

import com.lab.todo.domain.Task;
import com.lab.todo.domain.TaskDto;
import com.lab.todo.service.Cached;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests - no Spring context, so the build needs no database or cache.
 */
class TaskMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsEntityFields() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        Task task = new Task();
        task.setId(7L);
        task.setTitle("Write the runbook");
        task.setDescription("Include rollback");
        task.setCompleted(true);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        TaskDto dto = TaskDto.from(task);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.title()).isEqualTo("Write the runbook");
        assertThat(dto.description()).isEqualTo("Include rollback");
        assertThat(dto.completed()).isTrue();
        assertThat(dto.createdAt()).isEqualTo(now);
    }

    @Test
    void cachePayloadSurvivesARoundTrip() throws Exception {
        List<TaskDto> tasks =
                List.of(new TaskDto(1L, "Ship it", null, false, Instant.EPOCH, Instant.EPOCH));

        String json = objectMapper.writeValueAsString(tasks);
        List<TaskDto> restored = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        assertThat(restored).isEqualTo(tasks);
    }

    @Test
    void cachedReportsItsSource() {
        assertThat(new Cached<>("x", true, 2).source()).isEqualTo("cache");
        assertThat(new Cached<>("x", false, 40).source()).isEqualTo("database");
    }
}
