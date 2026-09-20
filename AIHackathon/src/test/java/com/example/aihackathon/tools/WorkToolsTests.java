package com.example.aihackathon.tools;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkToolsTests {

    private static final ToolContext ANNA = new ToolContext(Map.of(WorkTools.OWNER_KEY, "anna"));

    private static final ToolContext BINH = new ToolContext(Map.of(WorkTools.OWNER_KEY, "binh"));

    private WorkTools tools;

    @BeforeEach
    void setUp() {
        this.tools = new WorkTools(new TaskStore(), "Asia/Ho_Chi_Minh");
    }

    @Test
    void createsAndListsTask() {
        this.tools.createTask("Viết slide demo", "2026-09-12", "HIGH", ANNA);

        assertThat(this.tools.listTasks(null, ANNA))
                .contains("Viết slide demo")
                .contains("HIGH/OPEN")
                .contains("hạn 2026-09-12");
    }

    @Test
    void completesTask() {
        String created = this.tools.createTask("Gửi báo cáo", null, null, ANNA);
        long id = Long.parseLong(created.replaceAll(".*#(\\d+).*", "$1"));

        assertThat(this.tools.completeTask(id, ANNA)).contains("Đã hoàn thành").contains("DONE");
        assertThat(this.tools.listTasks("OPEN", ANNA)).isEqualTo("Không có công việc nào.");
        assertThat(this.tools.listTasks("DONE", ANNA)).contains("Gửi báo cáo");
    }

    @Test
    void keepsTasksOfDifferentOwnersSeparate() {
        String created = this.tools.createTask("Task của Anna", null, "HIGH", ANNA);
        long id = Long.parseLong(created.replaceAll(".*#(\\d+).*", "$1"));

        assertThat(this.tools.listTasks(null, BINH)).isEqualTo("Không có công việc nào.");
        assertThat(this.tools.completeTask(id, BINH)).contains("Không tìm thấy");
    }

    @Test
    void countsWorkingDaysExcludingWeekend() {
        // 2026-09-07 là thứ Hai, 2026-09-11 là thứ Sáu -> 5 ngày làm việc
        assertThat(this.tools.countWorkingDays("2026-09-07", "2026-09-13"))
                .contains("5 ngày làm việc");
    }

    @Test
    void rejectsEndDateBeforeStartDate() {
        assertThat(this.tools.countWorkingDays("2026-09-10", "2026-09-01"))
                .contains("phải sau ngày bắt đầu");
    }

    @Test
    void reportsInvalidDateToModelInsteadOfCrashing() {
        assertThatThrownBy(() -> this.tools.createTask("X", "10/09/2026", null, ANNA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void currentDateTimeIsIsoParseable() {
        assertThat(this.tools.getCurrentDateTime()).contains("ISO: ");
    }

    @Test
    void fallsBackToAnonymousWhenToolContextMissing() {
        assertThat(this.tools.createTask("Không có owner", null, null, null)).contains("Đã tạo task");
    }
}
