package com.example.aihackathon.codeanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/**
 * Đọc giá trị annotation ở mức cú pháp.
 *
 * <p>Không dùng symbol solver ở đây: giá trị của {@code @GetMapping("/x")} nằm ngay trong AST,
 * còn resolve annotation của Spring thì cần jar - thứ mà bản clone shallow không có.
 */
final class Annotations {

    private Annotations() {
    }

    /** Tìm annotation theo tên ngắn (không cần khớp package). */
    static Optional<AnnotationExpr> find(NodeWithAnnotations<?> node, String... names) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String actual = annotation.getNameAsString();
            String simple = actual.contains(".") ? actual.substring(actual.lastIndexOf('.') + 1) : actual;
            for (String wanted : names) {
                if (simple.equals(wanted)) {
                    return Optional.of(annotation);
                }
            }
        }
        return Optional.empty();
    }

    static boolean has(NodeWithAnnotations<?> node, String... names) {
        return find(node, names).isPresent();
    }

    /**
     * Lấy các giá trị String của một thuộc tính. Xử lý cả 3 dạng khai báo:
     * {@code @X("a")}, {@code @X(value = "a")}, {@code @X(path = {"a", "b"})}.
     */
    static List<String> stringValues(AnnotationExpr annotation, String... memberNames) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return literals(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (String memberName : memberNames) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals(memberName)) {
                        return literals(pair.getValue());
                    }
                }
            }
        }
        return List.of();
    }

    static Optional<String> stringValue(AnnotationExpr annotation, String... memberNames) {
        return stringValues(annotation, memberNames).stream().findFirst();
    }

    /**
     * Tên các enum/hằng trong một thuộc tính, ví dụ {@code method = RequestMethod.POST}
     * cho ra {@code ["POST"]}.
     */
    static List<String> enumValues(AnnotationExpr annotation, String memberName) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return List.of();
        }
        for (MemberValuePair pair : normal.getPairs()) {
            if (!pair.getNameAsString().equals(memberName)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            collectEnumNames(pair.getValue(), values);
            return values;
        }
        return List.of();
    }

    private static void collectEnumNames(Expression expression, List<String> sink) {
        if (expression instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(value -> collectEnumNames(value, sink));
            return;
        }
        String text = expression.toString();
        int dot = text.lastIndexOf('.');
        sink.add(dot >= 0 ? text.substring(dot + 1) : text);
    }

    private static List<String> literals(Expression expression) {
        List<String> values = new ArrayList<>();
        collectLiterals(expression, values);
        return values;
    }

    private static void collectLiterals(Expression expression, List<String> sink) {
        if (expression instanceof StringLiteralExpr literal) {
            sink.add(literal.getValue());
            return;
        }
        if (expression instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(value -> collectLiterals(value, sink));
            return;
        }
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            collectLiterals(binary.getLeft(), left);
            collectLiterals(binary.getRight(), right);
            if (left.size() == 1 && right.size() == 1) {
                sink.add(left.get(0) + right.get(0));
                return;
            }
        }
        // Hằng số tham chiếu, ví dụ @RequestMapping(ApiPaths.ORDERS): không đọc được giá trị
        // ở mức cú pháp, giữ lại dạng ${...} để người đọc biết cần tra thêm.
        sink.add("${" + expression + "}");
    }
}
