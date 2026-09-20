package com.example.aihackathon.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Lưu task trong bộ nhớ, tách riêng theo từng "owner" (thường là conversationId/user).
 * Đổi sang JPA/Redis chỉ cần thay implementation của lớp này.
 */
@Component
public class TaskStore {

    private final Map<String, List<Task>> byOwner = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    public Task add(String owner, String title, LocalDate dueDate, Task.Priority priority) {
        Task task = new Task(sequence.incrementAndGet(), title, dueDate, priority, Task.Status.OPEN);
        byOwner.computeIfAbsent(owner, key -> new CopyOnWriteArrayList<>()).add(task);
        return task;
    }

    public List<Task> findAll(String owner) {
        return Task.sorted(byOwner.getOrDefault(owner, List.of()));
    }

    public List<Task> findByStatus(String owner, Task.Status status) {
        return findAll(owner).stream().filter(task -> task.status() == status).toList();
    }

    /** Đánh dấu hoàn thành. Trả về empty nếu không tìm thấy id trong phạm vi owner. */
    public Optional<Task> complete(String owner, long id) {
        List<Task> tasks = byOwner.get(owner);
        if (tasks == null) {
            return Optional.empty();
        }
        for (int i = 0; i < tasks.size(); i++) {
            Task current = tasks.get(i);
            if (current.id() == id) {
                Task done = new Task(current.id(), current.title(), current.dueDate(),
                        current.priority(), Task.Status.DONE);
                tasks.set(i, done);
                return Optional.of(done);
            }
        }
        return Optional.empty();
    }
}
