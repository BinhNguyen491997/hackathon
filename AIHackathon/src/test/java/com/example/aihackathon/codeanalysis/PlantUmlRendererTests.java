package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlRendererTests {

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:participant|actor|database|queue|entity|boundary|control|collections)\\s+\"[^\"]*\"\\s+as\\s+(\\w+)");

    private static final Pattern MESSAGE = Pattern.compile("^\\s*(\\w+)\\s+(?:->|-->)\\s+(\\w+)");

    private static final Pattern LIFELINE = Pattern.compile("^\\s*(?:activate|deactivate)\\s+(\\w+)\\s*$");

    private static final Pattern NOTE = Pattern.compile("^\\s*note\\s+(?:over|right of|left of)\\s+(\\w+)");

    private static String puml;

    private static ApiFlow flow;

    @BeforeAll
    static void renderOnce() {
        JavaSourceIndex index = FixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.matches("POST", "/api/orders"))
                .findFirst()
                .orElseThrow();
        flow = new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);
        puml = PlantUmlRenderer.render(flow, EvidenceCollector.collect(index, flow), true);
    }

    @Test
    void coKhungFileHopLe() {
        assertThat(puml).startsWith("@startuml").endsWith("@enduml\n");
        assertThat(puml).contains("title POST /api/orders");
        assertThat(puml).contains("Tạo đơn hàng mới cho khách");
    }

    /**
     * PlantUML sẽ tự tạo participant cho alias lạ và vẽ sai cột, nên đây là kiểm tra quan
     * trọng nhất: mọi alias được dùng đều phải được khai báo trước.
     */
    @Test
    void moiAliasDuocDungDeuDaKhaiBao() {
        Set<String> declared = new LinkedHashSet<>();
        List<String> referenced = new ArrayList<>();

        for (String line : puml.split("\n")) {
            Matcher declaration = DECLARATION.matcher(line);
            if (declaration.find()) {
                declared.add(declaration.group(1));
                continue;
            }
            addIfMatches(MESSAGE, line, referenced, 2);
            addIfMatches(LIFELINE, line, referenced, 1);
            addIfMatches(NOTE, line, referenced, 1);
        }

        assertThat(declared).contains("CLIENT", "OrderController", "OrderServiceImpl");
        assertThat(referenced).isNotEmpty();
        assertThat(declared).as("alias dùng trong diagram nhưng chưa khai báo")
                .containsAll(referenced);
    }

    @Test
    void canBangKhoiAltLoopGroup() {
        int open = 0;
        for (String line : puml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("alt ") || trimmed.startsWith("loop ") || trimmed.startsWith("group ")) {
                open++;
            }
            else if (trimmed.equals("end")) {
                open--;
            }
            assertThat(open).as("có 'end' thừa ở dòng: " + trimmed).isNotNegative();
        }
        assertThat(open).as("số khối alt/loop/group chưa đóng").isZero();
    }

    @Test
    void canBangActivateVaDeactivate() {
        long activate = puml.lines().filter(line -> line.trim().startsWith("activate ")).count();
        long deactivate = puml.lines().filter(line -> line.trim().startsWith("deactivate ")).count();
        assertThat(activate).isEqualTo(deactivate);
    }

    @Test
    void veTangDatabaseTuongMinhChuKhongDungORepository() {
        // repository chi la cua vao; thu that su bi doc/ghi la database
        assertThat(puml).contains("database \"Way4\" as DB__");
        assertThat(puml).contains("participant \"OrderRepository");
        assertThat(puml).contains("-> DB__:");
        assertThat(puml).contains("DB__ --> OrderRepository");
        assertThat(puml).contains("Tầng repository được hiểu là cửa vào database Way4");
    }

    @Test
    void nhanChangDatabaseNoiRoLoaiThaoTacVaBang() {
        assertThat(puml).contains("ghi dữ liệu trên bảng: orders");
        assertThat(puml).contains("đọc dữ liệu trên entity: Customer");
    }

    /**
     * {@code reserveGift} không có tiền tố nào cho biết đọc hay ghi. Gán nhãn "đọc dữ liệu" cho nó
     * là nói sai chiều tác động - thà nói không biết.
     */
    @Test
    void khongDoanChieuTacDongKhiTenMethodKhongCoTinHieu() {
        assertThat(puml).contains("thao tác dữ liệu (chưa suy được đọc hay ghi) (reserveGift)");
        assertThat(puml).doesNotContain("đọc dữ liệu (reserveGift)");
    }

    @Test
    void canBangActivateVaDeactivateKeCaChangDatabase() {
        long activate = puml.lines().filter(line -> line.trim().startsWith("activate ")).count();
        long deactivate = puml.lines().filter(line -> line.trim().startsWith("deactivate ")).count();

        assertThat(activate).isEqualTo(deactivate);
        // moi repository hop them mot cap activate/deactivate -> phai nhieu hon so participant
        assertThat(activate).isGreaterThan(3);
    }

    @Test
    void uuTienCauQueryLamNhanThaoTacDatabase() {
        JavaSourceIndex index = FixtureRepo.index();
        ApiEndpoint endpoint = EndpointScanner.scan(index).stream()
                .filter(candidate -> candidate.matches("GET", "/api/orders/{id}"))
                .findFirst()
                .orElseThrow();
        ApiFlow getFlow = new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);

        String getPuml = PlantUmlRenderer.render(getFlow,
                EvidenceCollector.collect(index, getFlow), true);

        assertThat(getPuml).contains("select o from Order o where o.id = :id");
    }

    @Test
    void ghiDieuKienTienDeGomPhanQuyenVaValidation() {
        assertThat(puml).contains("Điều kiện tiền đề");
        assertThat(puml).contains("[phân quyền]").contains("ORDER_CREATE");
        assertThat(puml).contains("[kiểm tra dữ liệu]").contains("NotBlank");
    }

    @Test
    void hienMaHttpNgayTaiChoNemLoi() {
        // BA hoi nhieu nhat ve nhanh loi la "client nhan duoc gi"
        assertThat(puml).contains("ném lỗi:");
        assertThat(puml).contains("client nhận HTTP NOT_FOUND");
    }

    @Test
    void hienKieuTraVeTrenMuiTenReturn() {
        assertThat(puml).contains("OrderServiceImpl --> OrderController: OrderResponse");
        assertThat(puml).contains("InventoryService --> OrderServiceImpl: (void)");
    }

    @Test
    void veKafkaThanhQueue() {
        assertThat(puml).contains("queue \"KafkaTemplate");
    }

    @Test
    void veNhanhLoiVaNhanhReNhanh() {
        assertThat(puml).contains("alt luồng bình thường");
        assertThat(puml).contains("else lỗi RuntimeException");
        assertThat(puml).contains("loop với mỗi");
        assertThat(puml).contains("else ngược lại");
        assertThat(puml).contains("ném lỗi:");
    }

    @Test
    void ghiNguonGocDeTruyVetLaiCode() {
        assertThat(puml).contains("Repo: " + FixtureRepo.REPO_URL);
        assertThat(puml).contains("Branch: master @ 01234567");
        assertThat(puml).contains("OrderController.java:");
    }

    @Test
    void khongConDauNgoacKepLotVaoNhanLamHongCuPhap() {
        for (String line : puml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("participant") || trimmed.startsWith("actor")
                    || trimmed.startsWith("database") || trimmed.startsWith("queue")) {
                assertThat(trimmed.chars().filter(character -> character == '"').count())
                        .as("khai báo participant phải có đúng 2 dấu ngoặc kép: " + trimmed)
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void legendNoiRoLuongNaySachHayConDiemMo() {
        assertThat(puml).contains("legend right");
        if (flow.unresolved().isEmpty() && flow.warnings().isEmpty()) {
            assertThat(puml).contains("không gặp điểm mờ nào");
        }
    }

    private static void addIfMatches(Pattern pattern, String line, List<String> sink, int groups) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return;
        }
        for (int group = 1; group <= groups; group++) {
            sink.add(matcher.group(group));
        }
    }
}
