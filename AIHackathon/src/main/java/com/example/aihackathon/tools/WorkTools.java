package com.example.aihackathon.tools;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bộ tool nghiệp vụ mà model được phép gọi.
 *
 * <p>Nguyên tắc: mỗi tool làm một việc rõ ràng, description viết cho model đọc,
 * và trả về String để model dễ diễn giải lại cho người dùng.
 */
@Component
public class WorkTools {

    public static final String OWNER_KEY = "owner";

    private static final Logger log = LoggerFactory.getLogger(WorkTools.class);

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm");

    private final TaskStore taskStore;

    private final ZoneId zoneId;

    public WorkTools(TaskStore taskStore,
            @Value("${agent.timezone:Asia/Ho_Chi_Minh}") String timezone) {
        this.taskStore = taskStore;
        this.zoneId = ZoneId.of(timezone);
    }

    @Tool(description = "Lấy ngày và giờ hiện tại. Bắt buộc gọi tool này trước khi tính toán "
            + "bất cứ điều gì liên quan tới 'hôm nay', 'mai', 'tuần này', deadline.")
    public String getCurrentDateTime() {
        ZonedDateTime now = ZonedDateTime.now(this.zoneId);
        String result = now.format(DATE_TIME) + " (" + this.zoneId + "), ISO: " + now.toLocalDate();
        log.info("TOOL getCurrentDateTime() -> {}", result);
        return result;
    }

    @Tool(description = "Tạo một công việc (task) mới cho người dùng hiện tại.")
    public String createTask(
            @ToolParam(description = "Tiêu đề ngắn gọn của công việc") String title,
            @ToolParam(description = "Ngày hết hạn dạng yyyy-MM-dd", required = false) String dueDate,
            @ToolParam(description = "Mức ưu tiên: LOW, MEDIUM hoặc HIGH", required = false) String priority,
            ToolContext toolContext) {

        String owner = owner(toolContext);
        log.info("TOOL createTask(title='{}', dueDate={}, priority={}) owner={}", title, dueDate, priority, owner);

        if (title == null || title.isBlank()) {
            log.warn("TOOL createTask bị từ chối: tiêu đề trống (owner={})", owner);
            return "Không tạo được: tiêu đề công việc đang trống.";
        }
        LocalDate due = Task.parseDate(dueDate);
        Task.Priority level = Task.parsePriority(priority);
        Task created = this.taskStore.add(owner, title.trim(), due, level);
        log.info("TOOL createTask -> {}", describe(created));
        return "Đã tạo task " + describe(created);
    }

    @Tool(description = "Liệt kê công việc của người dùng hiện tại.")
    public String listTasks(
            @ToolParam(description = "Lọc theo trạng thái: OPEN (chưa xong) hoặc DONE (đã xong). "
                    + "Bỏ trống để lấy tất cả.", required = false) String status,
            ToolContext toolContext) {

        String owner = owner(toolContext);
        List<Task> tasks = (status == null || status.isBlank())
                ? this.taskStore.findAll(owner)
                : this.taskStore.findByStatus(owner, parseStatus(status));
        log.info("TOOL listTasks(status={}) owner={} -> {} task", status, owner, tasks.size());

        if (tasks.isEmpty()) {
            return "Không có công việc nào.";
        }
        StringBuilder result = new StringBuilder("Có ").append(tasks.size()).append(" công việc:");
        tasks.forEach(task -> result.append("\n- ").append(describe(task)));
        return result.toString();
    }

    @Tool(description = "Đánh dấu một công việc là đã hoàn thành, theo id của công việc đó.")
    public String completeTask(
            @ToolParam(description = "Id của công việc") long id,
            ToolContext toolContext) {

        String owner = owner(toolContext);
        Optional<Task> updated = this.taskStore.complete(owner, id);
        if (updated.isEmpty()) {
            // không tìm thấy cũng có thể là model đoán id của người khác -> đáng log WARN
            log.warn("TOOL completeTask(id={}) owner={} -> không tìm thấy", id, owner);
            return "Không tìm thấy công việc có id " + id + ".";
        }
        log.info("TOOL completeTask(id={}) owner={} -> {}", id, owner, describe(updated.get()));
        return "Đã hoàn thành task " + describe(updated.get());
    }

    @Tool(description = "Đếm số ngày làm việc (không tính thứ Bảy, Chủ nhật) giữa hai ngày, "
            + "tính cả ngày bắt đầu và ngày kết thúc. Dùng để ước lượng thời gian còn lại tới deadline.")
    public String countWorkingDays(
            @ToolParam(description = "Ngày bắt đầu, dạng yyyy-MM-dd") String from,
            @ToolParam(description = "Ngày kết thúc, dạng yyyy-MM-dd") String to) {

        LocalDate start = Task.parseDate(from);
        LocalDate end = Task.parseDate(to);
        if (start == null || end == null) {
            log.warn("TOOL countWorkingDays(from={}, to={}) -> tham số không hợp lệ", from, to);
            return "Cần cả ngày bắt đầu và ngày kết thúc dạng yyyy-MM-dd.";
        }
        if (end.isBefore(start)) {
            log.warn("TOOL countWorkingDays(from={}, to={}) -> ngày kết thúc trước ngày bắt đầu", from, to);
            return "Ngày kết thúc (" + end + ") phải sau ngày bắt đầu (" + start + ").";
        }
        long workingDays = start.datesUntil(end.plusDays(1))
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
        log.info("TOOL countWorkingDays({} -> {}) = {} ngày làm việc", start, end, workingDays);
        return "Từ " + start + " đến " + end + " có " + workingDays + " ngày làm việc.";
    }

    private static Task.Status parseStatus(String value) {
        try {
            return Task.Status.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Trạng thái '" + value + "' không hợp lệ. Chỉ nhận OPEN hoặc DONE");
        }
    }

    private static String describe(Task task) {
        return "#" + task.id() + " \"" + task.title() + "\""
                + " [" + task.priority() + "/" + task.status() + "]"
                + (task.dueDate() == null ? "" : " hạn " + task.dueDate());
    }

    /**
     * Owner được truyền qua ToolContext từ controller, không đi qua model,
     * nên model không thể tự đổi sang đọc dữ liệu của người khác.
     */
    private static String owner(ToolContext toolContext) {
        Object owner = (toolContext == null) ? null : toolContext.getContext().get(OWNER_KEY);
        return (owner == null) ? "anonymous" : owner.toString();
    }
}
