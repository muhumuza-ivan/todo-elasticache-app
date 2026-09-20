package com.lab.todo.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskRequest(
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be 200 characters or fewer")
        String title,

        @Size(max = 1000, message = "description must be 1000 characters or fewer")
        String description,

        Boolean completed) {
}
