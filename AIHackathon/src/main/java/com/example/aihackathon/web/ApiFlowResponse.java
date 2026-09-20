package com.example.aihackathon.web;

import java.util.List;

import com.example.aihackathon.codeanalysis.ApiFlowAnalyzer;
import com.example.aihackathon.codeanalysis.model.ApiFlow;

/**
 * Kết quả phân tích trả cho client.
 *
 * @param summary bản mô tả luồng dạng text
 * @param formats nội dung + đường dẫn file của từng định dạng
 */
public record ApiFlowResponse(
        String httpMethod,
        String path,
        String operationSummary,
        String controller,
        String controllerMethod,
        String sourceFile,
        int sourceLine,
        String branch,
        String commitSha,
        List<ParticipantView> participants,
        String summary,
        Formats formats,
        boolean aiUsed,
        String aiNote,
        SpecResponse.ComparisonInfo comparison,
        List<String> unresolved,
        List<String> warnings) {

    public record ParticipantView(String name, String kind, String type, String note) {
    }

    /**
     * Bốn định dạng cho bốn cách dùng.
     *
     * @param html     mở bằng browser, không cần cài gì - dành cho BA
     * @param markdown dán vào GitLab wiki/MR, GitLab tự render sơ đồ Mermaid
     * @param mermaid  chỉ phần sơ đồ, để nhúng vào tài liệu khác
     * @param puml     cho developer dùng plugin PlantUML
     */
    public record Formats(Rendered html, Rendered markdown, Rendered mermaid, Rendered puml) {

        public record Rendered(String content, String file) {

            static Rendered from(ApiFlowAnalyzer.AnalysisResult.Rendered rendered) {
                return new Rendered(rendered.content(), rendered.filePath());
            }
        }
    }

    public static ApiFlowResponse from(ApiFlowAnalyzer.AnalysisResult result) {
        ApiFlow flow = result.flow();
        List<ParticipantView> participants = flow.participants().stream()
                .map(participant -> new ParticipantView(participant.displayName(),
                        participant.kind().stereotype(), participant.typeFqn(), participant.note()))
                .toList();

        return new ApiFlowResponse(
                flow.endpoint().httpMethod(),
                flow.endpoint().path(),
                flow.endpoint().summary(),
                flow.endpoint().controllerFqn(),
                flow.endpoint().methodName(),
                flow.endpoint().sourceFile(),
                flow.endpoint().line(),
                flow.branch(),
                flow.commitSha(),
                participants,
                result.summary(),
                new Formats(
                        Formats.Rendered.from(result.html()),
                        Formats.Rendered.from(result.markdown()),
                        Formats.Rendered.from(result.mermaid()),
                        Formats.Rendered.from(result.puml())),
                result.aiUsed(),
                result.aiNote(),
                SpecResponse.ComparisonInfo.from(result.comparison()),
                flow.unresolved(),
                flow.warnings());
    }
}
