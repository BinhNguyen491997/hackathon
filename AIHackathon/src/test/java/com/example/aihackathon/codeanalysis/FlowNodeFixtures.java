package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ApiFlow;
import com.example.aihackathon.codeanalysis.model.FlowNode;
import com.example.aihackathon.codeanalysis.model.MethodRef;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;

/**
 * Dựng {@link ApiFlow} tối giản bằng tay, để test riêng phần đối chiếu.
 *
 * <p>Vì sao không dùng repo mẫu: cần kiểm soát chính xác <b>ghi chú</b> trên node Call (cái mang
 * thông tin "hiện thực của X"). Cố tình tạo ra một cặp interface/impl đặt tên lệch khuôn trong repo
 * mẫu chỉ để test một phép so khoá là làm fixture phình ra vì lý do không liên quan.
 */
final class FlowNodeFixtures {

    private FlowNodeFixtures() {
    }

    static Builder call(String callerType, String callerMethod, String calleeType,
            String calleeMethod, String note) {

        return new Builder(callerType, callerMethod, calleeType, calleeMethod, note);
    }

    record Builder(String callerType, String callerMethod, String calleeType, String calleeMethod,
            String note) {

        ApiFlow toFlow(ApiEndpoint template) {
            ApiEndpoint endpoint = new ApiEndpoint(template.httpMethod(), template.path(),
                    template.controllerFqn(), this.callerType, this.callerMethod,
                    template.argCount(), template.summary(), template.sourceFile(), template.line());

            MethodRef target = new MethodRef("com.example." + this.calleeType, this.calleeType,
                    this.calleeMethod, 1, ParticipantKind.SERVICE, null);
            FlowNode.Call node = new FlowNode.Call(target, this.calleeMethod + "()", this.note,
                    new FlowNode.Source(template.sourceFile(), template.line()), List.of());

            List<ApiFlow.Participant> participants = List.of(
                    new ApiFlow.Participant("C", this.callerType, template.controllerFqn(),
                            ParticipantKind.CONTROLLER, null),
                    new ApiFlow.Participant("S", this.calleeType, target.typeFqn(),
                            ParticipantKind.SERVICE, this.note));

            return new ApiFlow(endpoint, participants, List.of(node), List.of(), List.of(),
                    ProcedureFixtureRepo.REPO_URL, ProcedureFixtureRepo.BRANCH,
                    ProcedureFixtureRepo.COMMIT_SHA, "Way4");
        }
    }
}
