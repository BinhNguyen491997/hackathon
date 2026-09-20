package com.example.aihackathon.config;

import java.util.Arrays;
import java.util.List;

import com.example.aihackathon.codeanalysis.FixtureRepo;
import com.example.aihackathon.tools.CodeAnalysisTools;
import com.example.aihackathon.tools.TaskStore;
import com.example.aihackathon.tools.WorkTools;
import org.junit.jupiter.api.Test;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chặn đúng loại lỗi đã từng xảy ra: viết tool xong nhưng quên đưa vào
 * {@code ChatClient.defaultTools(...)}, khiến model không thấy tool nào cả. Endpoint REST vẫn
 * chạy nên bug này không lộ ra ở test nào khác.
 *
 * <p>Test dùng {@code ToolCallbacks} - đúng cơ chế Spring AI dùng để biến {@code @Tool} thành
 * tool gửi cho model - nên nếu annotation sai hoặc tool bị bỏ sót thì test đổ ở đây.
 */
class AgentToolRegistrationTests {

    private final WorkTools workTools = new WorkTools(new TaskStore(), "Asia/Ho_Chi_Minh");

    private final CodeAnalysisTools codeAnalysisTools = new CodeAnalysisTools(FixtureRepo.analyzer());

    @Test
    void toolPhanTichCodeDuocPhoiRaChoModel() {
        assertThat(toolNames(this.codeAnalysisTools))
                .containsExactlyInAnyOrder("listApiEndpoints", "describeApiFlow",
                        "generateSequenceDiagram", "collectCodeEvidence", "writeFunctionalSpec");
    }

    @Test
    void quyTrinhHaiBuocVietDacTaDuocPhoiDay() {
        // Thieu mot trong hai la agent khong the vua lay dan chung vua luu tai lieu
        assertThat(toolNames(this.codeAnalysisTools))
                .contains("collectCodeEvidence", "writeFunctionalSpec");
    }

    @Test
    void toolQuanLyCongViecVanConDuNhuTruoc() {
        assertThat(toolNames(this.workTools))
                .containsExactlyInAnyOrder("getCurrentDateTime", "createTask", "listTasks",
                        "completeTask", "countWorkingDays");
    }

    @Test
    void toolCuaHaiNhomKhongTrungTen() {
        List<String> all = toolNames(this.workTools, this.codeAnalysisTools);

        assertThat(all).doesNotHaveDuplicates();
        assertThat(all).hasSize(10);
    }

    @Test
    void moiToolDeuCoDescriptionDeModelBietKhiNaoGoi() {
        for (ToolCallback callback : ToolCallbacks.from(this.workTools, this.codeAnalysisTools)) {
            assertThat(callback.getToolDefinition().description())
                    .as("tool %s thiếu description", callback.getToolDefinition().name())
                    .isNotBlank();
        }
    }

    private static List<String> toolNames(Object... sources) {
        return Arrays.stream(ToolCallbacks.from(sources))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
