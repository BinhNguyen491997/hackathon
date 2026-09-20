package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallFlowBuilderTests {

    private static ApiFlow createFlow;

    private static ApiFlow findOneFlow;

    @BeforeAll
    static void buildOnce() {
        JavaSourceIndex index = FixtureRepo.index();
        List<ApiEndpoint> endpoints = EndpointScanner.scan(index);
        createFlow = build(index, endpoints, "POST", "/api/orders");
        findOneFlow = build(index, endpoints, "GET", "/api/orders/{id}");
    }

    private static ApiFlow build(JavaSourceIndex index, List<ApiEndpoint> endpoints, String method,
            String path) {

        ApiEndpoint endpoint = endpoints.stream()
                .filter(candidate -> candidate.matches(method, path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("thiếu endpoint " + method + " " + path));
        return new CallFlowBuilder(index, FixtureRepo.properties())
                .build(endpoint, FixtureRepo.REPO_URL, FixtureRepo.BRANCH, FixtureRepo.COMMIT_SHA);
    }

    // ------------------------------------------------------------------
    // Mắt xích quan trọng nhất: interface -> implementation
    // ------------------------------------------------------------------

    @Test
    void noiInterfaceSangImplementationChuKhongDungOInterface() {
        assertThat(participantNames(createFlow)).contains("OrderServiceImpl");
        assertThat(participantNames(createFlow)).doesNotContain("OrderService");

        Optional<FlowNode.Call> create = findCall(createFlow, "OrderServiceImpl", "create");
        assertThat(create).isPresent();
        assertThat(create.get().note()).contains("hiện thực của OrderService");
        assertThat(create.get().children()).isNotEmpty();
    }

    @Test
    void diSauQuaNhieuTangChuKhongDungOTangService() {
        // OrderController -> OrderServiceImpl -> InventoryService -> StockRepository
        assertThat(participantNames(createFlow))
                .contains("OrderController", "OrderServiceImpl", "InventoryService", "StockRepository");
    }

    @Test
    void ghiNhanTransactional() {
        assertThat(findCall(createFlow, "OrderServiceImpl", "create"))
                .get()
                .extracting(FlowNode.Call::note).asString()
                .contains("@Transactional");
    }

    // ------------------------------------------------------------------
    // Cấu trúc điều khiển
    // ------------------------------------------------------------------

    @Test
    void giuLaiReNhanhIfElse() {
        List<FlowNode.Choice> choices = collect(createFlow.nodes(), FlowNode.Choice.class);
        assertThat(choices).isNotEmpty();

        boolean coNhanhTheoTongTien = choices.stream().anyMatch(choice ->
                choice.alternatives().stream().anyMatch(alt -> alt.label().contains("request.total()")));
        assertThat(coNhanhTheoTongTien)
                .as("phải giữ được điều kiện if theo tổng tiền")
                .isTrue();

        boolean coNhanhNguocLai = choices.stream().anyMatch(choice ->
                choice.alternatives().stream().anyMatch(alt -> alt.label().equals("ngược lại")));
        assertThat(coNhanhNguocLai).isTrue();
    }

    @Test
    void giuLaiVongLap() {
        List<FlowNode.Loop> loops = collect(createFlow.nodes(), FlowNode.Loop.class);
        assertThat(loops).isNotEmpty();
        assertThat(loops.get(0).label()).contains("với mỗi");

        List<String> trongVongLap = new ArrayList<>();
        collectCallLabels(loops.get(0).body(), trongVongLap);
        assertThat(trongVongLap).anyMatch(label -> label.startsWith("InventoryService.reserve"));
    }

    @Test
    void giuLaiNhanhLoiTuTryCatch() {
        List<FlowNode.Guarded> guarded = collect(createFlow.nodes(), FlowNode.Guarded.class);
        assertThat(guarded).isNotEmpty();

        // fixture co hai khoi try/catch: mot khoi xu ly loi thanh toan, mot khoi bo qua loi gui
        // thong bao. Khong dua vao thu tu, tim theo noi dung.
        boolean coNhanhXuLyLoiThanhToan = guarded.stream().anyMatch(block ->
                block.handlers().stream().anyMatch(handler -> {
                    List<String> calls = new ArrayList<>();
                    collectCallLabels(handler.body(), calls);
                    return handler.label().contains("RuntimeException")
                            && calls.stream().anyMatch(label -> label.contains("notifyPaymentFailed"));
                }));
        assertThat(coNhanhXuLyLoiThanhToan)
                .as("phải giữ được nhánh xử lý khi thanh toán lỗi")
                .isTrue();

        boolean coNhanhBoQuaLoi = guarded.stream().anyMatch(block ->
                block.handlers().stream().anyMatch(handler -> handler.body().isEmpty()));
        assertThat(coNhanhBoQuaLoi)
                .as("catch rỗng cũng phải được giữ lại, vì đó là điểm cần hỏi dev")
                .isTrue();
    }

    @Test
    void giuLaiSwitchThanhNhieuNhanh() {
        FlowNode.Call reserve = findCall(createFlow, "InventoryService", "reserve").orElseThrow();
        List<FlowNode.Choice> choices = collect(reserve.children(), FlowNode.Choice.class);
        assertThat(choices).isNotEmpty();

        List<String> labels = choices.get(0).alternatives().stream()
                .map(FlowNode.Alternative::label)
                .toList();
        assertThat(labels).anyMatch(label -> label.contains("GIFT"));
        assertThat(labels).anyMatch(label -> label.contains("mặc định"));
    }

    @Test
    void ghiNhanNemLoi() {
        List<FlowNode.Terminal> terminals = collect(createFlow.nodes(), FlowNode.Terminal.class);
        assertThat(terminals).anyMatch(terminal ->
                terminal.kind() == FlowNode.Terminal.Kind.THROW
                        && terminal.detail().contains("OrderNotFoundException"));
    }

    // ------------------------------------------------------------------
    // Thông tin cho BA: bảng dữ liệu, query, ranh giới hệ thống
    // ------------------------------------------------------------------

    @Test
    void chiRaBangDuLieuTuGenericCuaSpringData() {
        assertThat(noteOf(createFlow, "OrderRepository")).isEqualTo("bảng: orders");
        assertThat(noteOf(createFlow, "CustomerRepository")).isEqualTo("entity: Customer");
    }

    @Test
    void hienCauQueryCuaRepository() {
        assertThat(findCall(findOneFlow, "OrderRepository", "findActiveById"))
                .get()
                .extracting(FlowNode.Call::note).asString()
                .contains("query: select o from Order o where o.id = :id");
    }

    @Test
    void danhDauRanhGioiRaNgoaiHeThong() {
        List<ApiFlow.Participant> external = createFlow.participants().stream()
                .filter(participant -> participant.kind() == ParticipantKind.EXTERNAL)
                .toList();
        assertThat(external).extracting(ApiFlow.Participant::displayName)
                .contains("PaymentClient", "KafkaTemplate");
    }

    // ------------------------------------------------------------------
    // Bộ lọc nhiễu
    // ------------------------------------------------------------------

    @Test
    void bolQuaGetterSetterCuaEntityVaDto() {
        List<String> labels = new ArrayList<>();
        collectCallLabels(createFlow.nodes(), labels);
        assertThat(labels).noneMatch(label -> label.startsWith("Order.get"));
        assertThat(labels).noneMatch(label -> label.startsWith("Customer.get"));
        assertThat(labels).noneMatch(label -> label.startsWith("OrderRequest."));
        assertThat(labels).noneMatch(label -> label.startsWith("OrderLine.get"));
    }

    @Test
    void vanGiuLoiGoiSangLopPhuTroNhungKhongDiSauVaoTrong() {
        FlowNode.Call validate = findCall(createFlow, "OrderValidator", "validate").orElseThrow();
        assertThat(validate.children())
                .as("expand-support-types = false nên không mở chi tiết lớp Validator")
                .isEmpty();
        assertThat(findCall(createFlow, "OrderMapper", "toEntity")).isPresent();
    }

    @Test
    void khongCoDiemMoNaoTrongRepoMau() {
        assertThat(createFlow.unresolved()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Tiện ích cho test
    // ------------------------------------------------------------------

    private static List<String> participantNames(ApiFlow flow) {
        return flow.participants().stream().map(ApiFlow.Participant::displayName).toList();
    }

    private static String noteOf(ApiFlow flow, String participantName) {
        return flow.participants().stream()
                .filter(participant -> participant.displayName().equals(participantName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("thiếu participant " + participantName))
                .note();
    }

    private static Optional<FlowNode.Call> findCall(ApiFlow flow, String typeName, String methodName) {
        List<FlowNode.Call> calls = collect(flow.nodes(), FlowNode.Call.class);
        return calls.stream()
                .filter(call -> call.target().typeSimpleName().equals(typeName)
                        && call.target().methodName().equals(methodName))
                .findFirst();
    }

    /** Thu mọi node của một kiểu trong toàn bộ cây, kể cả trong nhánh alt/loop/try. */
    private static <T extends FlowNode> List<T> collect(List<FlowNode> nodes, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(nodes, node -> {
            if (type.isInstance(node)) {
                found.add(type.cast(node));
            }
        });
        return found;
    }

    private static void collectCallLabels(List<FlowNode> nodes, List<String> sink) {
        walk(nodes, node -> {
            if (node instanceof FlowNode.Call call) {
                sink.add(call.target().typeSimpleName() + "." + call.target().methodName());
            }
        });
    }

    private static void walk(List<FlowNode> nodes, java.util.function.Consumer<FlowNode> visitor) {
        for (FlowNode node : nodes) {
            visitor.accept(node);
            if (node instanceof FlowNode.Call call) {
                walk(call.children(), visitor);
            }
            else if (node instanceof FlowNode.Choice choice) {
                choice.alternatives().forEach(alt -> walk(alt.body(), visitor));
            }
            else if (node instanceof FlowNode.Loop loop) {
                walk(loop.body(), visitor);
            }
            else if (node instanceof FlowNode.Guarded guarded) {
                walk(guarded.body(), visitor);
                guarded.handlers().forEach(handler -> walk(handler.body(), visitor));
                walk(guarded.cleanup(), visitor);
            }
        }
    }
}
