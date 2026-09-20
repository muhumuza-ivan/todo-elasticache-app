package com.lab.todo.web;

import com.lab.todo.domain.TaskDto;
import com.lab.todo.domain.TaskRequest;
import com.lab.todo.service.Cached;
import com.lab.todo.service.TaskService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * @param refresh bypass the cache and read PostgreSQL. The Refresh control
     *     in the UI sets this; without it a refresh re-reads the cached list
     *     and cannot show a change made outside this application.
     */
    @GetMapping("/tasks")
    public TaskListResponse list(@RequestParam(defaultValue = "false") boolean refresh) {
        Cached<List<TaskDto>> result = tasks.listTasks(refresh);
        return new TaskListResponse(
                result.source(), result.elapsedMillis(), result.value().size(), result.value());
    }

    @GetMapping("/tasks/{id}")
    public TaskResponse get(@PathVariable Long id) {
        Cached<TaskDto> result = tasks.getTask(id);
        return new TaskResponse(result.source(), result.elapsedMillis(), result.value());
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskDto> create(@Valid @RequestBody TaskRequest request) {
        TaskDto created = tasks.create(request);
        return ResponseEntity.created(URI.create("/api/tasks/" + created.id())).body(created);
    }

    @PutMapping("/tasks/{id}")
    public TaskDto update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        return tasks.update(id, request);
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Clears the application's Redis keys so the next read demonstrably misses. */
    @PostMapping("/cache/flush")
    public Map<String, Object> flushCache() {
        return Map.of("keysRemoved", tasks.flushCache());
    }

    public record TaskListResponse(String source, long elapsedMs, int count, List<TaskDto> items) {
    }

    public record TaskResponse(String source, long elapsedMs, TaskDto task) {
    }
}
