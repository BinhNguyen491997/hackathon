package com.example.aihackathon.web;

import java.util.List;

import com.example.aihackathon.codeanalysis.ApiFlowAnalyzer;
import com.example.aihackathon.codeanalysis.model.FlowComparison;

/**
 * Dẫn chứng và điểm cần xác nhận, dạng JSON.
 *
 * <p>Mỗi dẫn chứng mang theo {@code sourceFile} và {@code line} do parser sinh ra. Hệ thống nào
 * dùng lại API này cũng có thể tự kiểm chứng từng phát biểu bằng cách mở đúng dòng code đó.
 */
public record SpecResponse(
        String httpMethod,
        String path,
        String operationSummary,
        String branch,
        String commitSha,
        boolean aiUsed,
        int aiAttempts,
        String aiNote,
        String rejectedAiDraft,
        CitationInfo citations,
        ComparisonInfo comparison,
        int evidenceCount,
        int questionCount,
        boolean hasBlockingQuestions,
        List<EvidenceView> evidence,
        List<QuestionView> questions,
        String markdownFile,
        String htmlFile) {

    public record EvidenceView(String id, String kind, String kindTitle, String statement,
            String sourceFile, int line, String snippet) {
    }

    public record QuestionView(String severity, String severityTitle, String question, String reason,
            List<String> evidenceIds) {
    }

    /**
     * Kết quả kiểm tra trích dẫn của phần AI viết.
     *
     * @param trustworthy false = phần mô tả đã bị loại khỏi tài liệu
     */
    public record CitationInfo(boolean trustworthy, List<String> citedIds, List<String> invalidIds,
            List<String> handWrittenSources) {
    }

    /**
     * Đối chiếu chéo giữa parser và AI.
     *
     * @param onlyByAi     bước AI tìm ra mà parser bỏ sót - đây là danh sách cần kiểm tra thủ công
     * @param onlyByParser bước parser khẳng định mà AI không nhắc tới
     */
    public record ComparisonInfo(
            boolean ran,
            int agreementPercent,
            int agreedCount,
            List<String> onlyByAi,
            List<String> onlyByParser,
            String aiNotes) {

        static ComparisonInfo from(FlowComparison comparison) {
            return new ComparisonInfo(
                    comparison.aiResponded(),
                    comparison.agreementPercent(),
                    comparison.agreed().size(),
                    comparison.onlyByAi().stream().map(FlowComparison.Step::label).toList(),
                    comparison.onlyByParser().stream().map(FlowComparison.Step::label).toList(),
                    comparison.aiNoteText());
        }
    }

    public static SpecResponse from(ApiFlowAnalyzer.SpecResult result) {
        List<EvidenceView> evidence = result.spec().evidence().stream()
                .map(item -> new EvidenceView(item.id(), item.kind().name(), item.kind().title(),
                        item.statement(), item.sourceFile(), item.line(), item.snippet()))
                .toList();

        List<QuestionView> questions = result.spec().questions().stream()
                .map(item -> new QuestionView(item.severity().name(), item.severity().title(),
                        item.question(), item.reason(), item.evidenceIds()))
                .toList();

        return new SpecResponse(
                result.flow().endpoint().httpMethod(),
                result.flow().endpoint().path(),
                result.flow().endpoint().summary(),
                result.flow().branch(),
                result.flow().commitSha(),
                result.aiUsed(),
                result.aiAttempts(),
                result.aiNote(),
                result.rejectedDraft(),
                new CitationInfo(result.citations().trustworthy(), result.citations().citedIds(),
                        result.citations().invalidIds(), result.citations().handWritten()),
                ComparisonInfo.from(result.comparison()),
                evidence.size(),
                questions.size(),
                result.spec().hasBlockingQuestions(),
                evidence,
                questions,
                result.markdown().filePath(),
                result.html().filePath());
    }
}
