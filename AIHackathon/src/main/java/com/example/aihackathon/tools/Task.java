package com.example.aihackathon.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Một task công việc do agent tạo/quản lý.
 */
public record Task(
        long id,
        String title,
        LocalDate dueDate,
        Priority priority,
        Status status) {

    public enum Priority { LOW, MEDIUM, HIGH }

    public enum Status { OPEN, DONE }

    /** Parse ngày dạng ISO (yyyy-MM-dd); trả về null nếu rỗng, ném lỗi rõ ràng nếu sai format. */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Ngày '" + value + "' không đúng định dạng ISO yyyy-MM-dd");
        }
    }

    public static Priority parsePriority(String value) {
        if (value == null || value.isBlank()) {
            return Priority.MEDIUM;
        }
        try {
            return Priority.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Priority '" + value + "' không hợp lệ. Chỉ nhận LOW, MEDIUM, HIGH");
        }
    }

    /** Sắp xếp: việc chưa xong trước, ưu tiên cao trước, deadline gần trước. */
    public static Comparator<Task> defaultOrder() {
        return Comparator.comparing(Task::status)
                .thenComparing(Comparator.comparing(Task::priority).reversed())
                .thenComparing(Task::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::id);
    }

    public static List<Task> sorted(List<Task> tasks) {
        return tasks.stream().filter(Objects::nonNull).sorted(defaultOrder()).toList();
    }
}
