package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chế độ có AI. Test dùng {@link NarrativeWriter} giả nên không cần API key và không tốn token.
 *
 * <p>Điều được kiểm tra không phải "LLM viết hay không" mà là: hệ thống có bảo vệ được người đọc
 * khi LLM viết sai hay không. Đó mới là phần quyết định tài liệu có dùng được.
 */
class AiNarrativeTests {

    private ApiFlowAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        this.analyzer = FixtureRepo.analyzer();
    }

    private ApiFlowAnalyzer.SpecResult run(NarrativeWriter writer) {
        return this.analyzer.specWith(FixtureRepo.REPO_URL, "master", "POST", "/api/orders", writer,
                FlowProposer.NONE);
    }

    // ------------------------------------------------------------------
    // Không dùng AI
    // ------------------------------------------------------------------

    @Test
    void cheDoKhongAiKhongGoiModelVaKhongCoNhanAi() {
        List<String> calls = new ArrayList<>();
        NarrativeWriter spy = (promptText, feedback) -> {
            calls.add("called");
            return "không nên được gọi";
        };

        ApiFlowAnalyzer.SpecResult result = run(NarrativeWriter.NONE);

        assertThat(calls).isEmpty();
        assertThat(result.aiUsed()).isFalse();
        assertThat(result.aiAttempts()).isZero();
        assertThat(result.aiNote()).isNull();
        assertThat(result.markdown().content()).doesNotContain("do AI viết");
        // van phai day du phan tat dinh
        assertThat(result.spec().evidence()).isNotEmpty();
        assertThat(spy).isNotNull();
    }

    // ------------------------------------------------------------------
    // Dùng AI, viết đạt
    // ------------------------------------------------------------------

    @Test
    void chapNhanBanVietDatTruyVetVaGanNhanRoRang() {
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) ->
                "### Hệ thống đang làm gì\nTiếp nhận yêu cầu tạo đơn hàng [E1].");

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.aiAttempts()).isEqualTo(1);
        assertThat(result.citations().trustworthy()).isTrue();
        assertThat(result.rejectedDraft()).isNull();

        assertThat(result.markdown().content())
                .contains("## Mô tả chức năng (do AI viết)")
                .contains("Nguồn gốc phần này:")
                .contains("Tiếp nhận yêu cầu tạo đơn hàng");
        assertThat(result.html().content())
                .contains("do AI viết")
                .contains("callout ai");
    }

    @Test
    void modelChiNhanDanhSachDuKienChuKhongNhanSoDong() {
        List<String> seen = new ArrayList<>();
        run((promptText, feedback) -> {
            seen.add(promptText);
            return "Tiếp nhận đơn [E1].";
        });

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).contains("E1").contains("DỮ KIỆN ĐỌC ĐƯỢC TỪ CODE");
        assertThat(seen.get(0))
                .as("model thấy số dòng là nó sẽ gõ lại vào văn bản, và lúc đó số dòng bắt đầu sai")
                .doesNotContain(".java:");
    }

    // ------------------------------------------------------------------
    // Dùng AI, viết sai -> thử lại -> loại bỏ
    // ------------------------------------------------------------------

    @Test
    void choThuLaiMotLanKemPhanHoiCuThe() {
        List<String> feedbacks = new ArrayList<>();
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) -> {
            feedbacks.add(String.valueOf(feedback));
            return feedback == null
                    ? "Chặn đơn vượt hạn mức [E999]."   // lần đầu: dẫn mã không tồn tại
                    : "Tiếp nhận yêu cầu tạo đơn hàng [E1]."; // lần hai: sửa đúng
        });

        assertThat(feedbacks).hasSize(2);
        assertThat(feedbacks.get(0)).isEqualTo("null");
        assertThat(feedbacks.get(1)).contains("E999").contains("dẫn chứng bịa");

        assertThat(result.aiAttempts()).isEqualTo(2);
        assertThat(result.citations().trustworthy()).isTrue();
        assertThat(result.markdown().content()).contains("model phải viết lại lần thứ hai mới đạt");
    }

    /**
     * Sau 2 lượt vẫn sai thì LOẠI BỎ phần mô tả, không đưa vào tài liệu kèm cảnh báo. Người đọc là
     * BA, họ không đối chiếu dẫn chứng được; một đoạn văn trôi chảy có trích dẫn bịa sẽ được tin ngay.
     */
    @Test
    void loaiBoBanVietNeuSauHaiLuotVanKhongDatTruyVet() {
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) ->
                "Chặn đơn khi vượt hạn mức tín dụng [E999].");

        assertThat(result.aiAttempts()).isEqualTo(2);
        assertThat(result.citations().trustworthy()).isFalse();
        assertThat(result.rejectedDraft()).contains("E999");

        assertThat(result.markdown().content())
                .contains("bị LOẠI BỎ")
                .doesNotContain("hạn mức tín dụng");
        // phan tat dinh khong bi anh huong
        assertThat(result.markdown().content()).contains("| Mã | Dữ kiện | Nguồn |");
        assertThat(result.spec().evidence()).isNotEmpty();
    }

    @Test
    void loaiBoBanVietTuGoSoDong() {
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) ->
                "Quy tắc nằm ở OrderValidator.java:9 [E1].");

        assertThat(result.citations().trustworthy()).isFalse();
        assertThat(result.citations().handWritten()).contains("OrderValidator.java:9");
        assertThat(result.markdown().content()).doesNotContain("OrderValidator.java:9 [E1]");
    }

    // ------------------------------------------------------------------
    // LLM lỗi: không được làm sập cả request
    // ------------------------------------------------------------------

    @Test
    void loiGoiModelKhongLamSapCaRequest() {
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) -> {
            throw new IllegalStateException("401 Unauthorized - thiếu LLM_API_KEY");
        });

        assertThat(result.spec().evidence()).isNotEmpty();
        assertThat(result.markdown().content()).contains("không tạo được phần mô tả");
        assertThat(result.markdown().content()).contains("| Mã | Dữ kiện | Nguồn |");
    }

    @Test
    void modelTraVeRongCungXuLyNhuKhongTaoDuoc() {
        ApiFlowAnalyzer.SpecResult result = run((promptText, feedback) -> "   ");

        assertThat(result.aiNote()).contains("không tạo được phần mô tả");
        assertThat(result.spec().questions()).isNotEmpty();
    }

    @Test
    void dinhDangTatDinhKhongDoiGiuaHaiCheDo() {
        String withoutAi = run(NarrativeWriter.NONE).markdown().content();
        String withAi = run((promptText, feedback) -> "Tiếp nhận đơn [E1].").markdown().content();

        // bang dan chung phai giong nhau o ca hai che do
        String marker = "## Dẫn chứng từ code";
        assertThat(withoutAi.substring(withoutAi.indexOf(marker)))
                .isEqualTo(withAi.substring(withAi.indexOf(marker)));
    }
}
