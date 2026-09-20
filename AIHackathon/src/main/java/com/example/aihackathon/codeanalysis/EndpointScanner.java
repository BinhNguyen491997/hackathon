package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.example.aihackathon.codeanalysis.JavaSourceIndex.IndexedType;
import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quét toàn bộ controller trong repo để lấy danh sách endpoint.
 *
 * <p>Việc này hoàn toàn tất định, không cần LLM: path là phép ghép prefix ở
 * {@code @RequestMapping} mức class với path ở mức method.
 */
final class EndpointScanner {

    private static final Logger log = LoggerFactory.getLogger(EndpointScanner.class);

    /** Annotation mapping -> HTTP method tương ứng. */
    private static final Map<String, String> METHOD_BY_ANNOTATION = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    private EndpointScanner() {
    }

    static List<ApiEndpoint> scan(JavaSourceIndex index) {
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (IndexedType type : index.allTypes()) {
            if (!isController(type)) {
                continue;
            }
            List<String> classPaths = classLevelPaths(type.declaration());
            for (MethodDeclaration method : type.declaration().getMethods()) {
                endpoints.addAll(scanMethod(type, classPaths, method));
            }
        }
        endpoints.sort(Comparator.comparing(ApiEndpoint::path).thenComparing(ApiEndpoint::httpMethod));
        log.info("tìm thấy {} endpoint trong {} type", endpoints.size(), index.allTypes().size());
        return List.copyOf(endpoints);
    }

    private static boolean isController(IndexedType type) {
        return type.kind() == ParticipantKind.CONTROLLER
                && Annotations.has(type.declaration(), "RestController", "Controller");
    }

    private static List<String> classLevelPaths(TypeDeclaration<?> declaration) {
        Optional<AnnotationExpr> mapping = Annotations.find(declaration, "RequestMapping");
        if (mapping.isEmpty()) {
            return List.of("");
        }
        List<String> paths = Annotations.stringValues(mapping.get(), "value", "path");
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<ApiEndpoint> scanMethod(IndexedType type, List<String> classPaths,
            MethodDeclaration method) {

        List<ApiEndpoint> result = new ArrayList<>();
        String summary = Annotations.find(method, "Operation")
                .flatMap(annotation -> Annotations.stringValue(annotation, "summary"))
                .orElse(null);

        for (Map.Entry<String, String> entry : METHOD_BY_ANNOTATION.entrySet()) {
            Optional<AnnotationExpr> annotation = Annotations.find(method, entry.getKey());
            if (annotation.isEmpty()) {
                continue;
            }
            List<String> methodPaths = Annotations.stringValues(annotation.get(), "value", "path");
            addAll(result, type, method, summary, entry.getValue(), classPaths, methodPaths);
        }

        Annotations.find(method, "RequestMapping").ifPresent(annotation -> {
            List<String> methodPaths = Annotations.stringValues(annotation, "value", "path");
            List<String> httpMethods = Annotations.enumValues(annotation, "method");
            if (httpMethods.isEmpty()) {
                httpMethods = List.of("ANY");
            }
            for (String httpMethod : httpMethods) {
                addAll(result, type, method, summary, httpMethod, classPaths, methodPaths);
            }
        });
        return result;
    }

    private static void addAll(List<ApiEndpoint> sink, IndexedType type, MethodDeclaration method,
            String summary, String httpMethod, List<String> classPaths, List<String> methodPaths) {

        List<String> effectiveMethodPaths = methodPaths.isEmpty() ? List.of("") : methodPaths;
        for (String classPath : classPaths) {
            for (String methodPath : effectiveMethodPaths) {
                sink.add(new ApiEndpoint(
                        httpMethod.toUpperCase(Locale.ROOT),
                        joinPaths(classPath, methodPath),
                        type.fqn(),
                        type.simpleName(),
                        method.getNameAsString(),
                        method.getParameters().size(),
                        summary,
                        type.relativePath(),
                        method.getBegin().map(position -> position.line).orElse(0)));
            }
        }
    }

    /** Ghép prefix class với path method thành một path chuẩn, luôn có "/" đầu. */
    static String joinPaths(String classPath, String methodPath) {
        String combined = "/" + trimSlashes(classPath) + "/" + trimSlashes(methodPath);
        combined = combined.replaceAll("/{2,}", "/");
        if (combined.length() > 1 && combined.endsWith("/")) {
            combined = combined.substring(0, combined.length() - 1);
        }
        return combined;
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
