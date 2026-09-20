package com.lab.todo.service;

/**
 * A read result plus where it came from, so the UI (and a live demo) can show
 * the cache doing its job.
 */
public record Cached<T>(T value, boolean fromCache, long elapsedMillis) {

    public String source() {
        return fromCache ? "cache" : "database";
    }
}
