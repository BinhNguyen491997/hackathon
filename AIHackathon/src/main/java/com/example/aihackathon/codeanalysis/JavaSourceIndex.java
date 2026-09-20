package com.example.aihackathon.codeanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bảng tra cứu toàn bộ source Java của một repo: FQN -> khai báo lớp, interface -> các lớp
 * hiện thực, và symbol solver để resolve lời gọi method.
 *
 * <p>Vì repo được clone dạng shallow và KHÔNG có jar dependency, symbol solver sẽ thất bại với
 * hầu hết type của thư viện ngoài. Điều đó chấp nhận được: mục tiêu là resolve chính xác các
 * type NẰM TRONG repo - đúng phần mà BA cần thấy. Những chỗ không resolve được thì
 * {@link CallFlowBuilder} có nhánh dự phòng theo cú pháp và cuối cùng là đánh dấu unresolved.
 */
public final class JavaSourceIndex {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceIndex.class);

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "generated-sources");

    private final Path root;

    private final Map<String, IndexedType> typesByFqn;

    private final Map<String, List<IndexedType>> typesBySimpleName;

    private final Map<String, List<IndexedType>> implementationsByParent;

    private final List<String> warnings;

    private final int parsedFiles;

    private JavaSourceIndex(Path root, Map<String, IndexedType> typesByFqn,
            Map<String, List<IndexedType>> typesBySimpleName,
            Map<String, List<IndexedType>> implementationsByParent,
            List<String> warnings, int parsedFiles) {
        this.root = root;
        this.typesByFqn = typesByFqn;
        this.typesBySimpleName = typesBySimpleName;
        this.implementationsByParent = implementationsByParent;
        this.warnings = warnings;
        this.parsedFiles = parsedFiles;
    }

    /** Một lớp/interface/record đã index, kèm CompilationUnit để tra import khi cần. */
    public record IndexedType(
            String fqn,
            String simpleName,
            TypeDeclaration<?> declaration,
            CompilationUnit unit,
            String relativePath,
            ParticipantKind kind) {

        public boolean isInterface() {
            return this.declaration instanceof ClassOrInterfaceDeclaration decl && decl.isInterface();
        }

        public boolean isAbstract() {
            return this.declaration instanceof ClassOrInterfaceDeclaration decl && decl.isAbstract();
        }

        // FQN là danh tính duy nhất. Nếu để record tự sinh, equals/hashCode sẽ so sánh
        // toàn bộ cây AST (EqualsVisitor của JavaParser) - vừa chậm vừa dễ gộp sai.
        @Override
        public boolean equals(Object other) {
            return other instanceof IndexedType type && this.fqn.equals(type.fqn);
        }

        @Override
        public int hashCode() {
            return this.fqn.hashCode();
        }

        @Override
        public String toString() {
            return this.fqn + " [" + this.kind + "]";
        }
    }

    public static JavaSourceIndex build(Path repoRoot, AnalysisProperties properties) {
        long startNanos = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        List<Path> sourceRoots = findSourceRoots(repoRoot);
        if (sourceRoots.isEmpty()) {
            warnings.add("Không tìm thấy thư mục src/main/java nào - đang parse từ gốc repo.");
            sourceRoots = List.of(repoRoot);
        }

        JavaParser parser = new JavaParser(configuration(sourceRoots));

        Map<String, IndexedType> typesByFqn = new LinkedHashMap<>();
        Map<String, List<IndexedType>> typesBySimpleName = new LinkedHashMap<>();
        int parsed = 0;
        int failed = 0;

        List<Path> files = collectJavaFiles(sourceRoots, properties.getMaxSourceFiles(), warnings);
        for (Path file : files) {
            Optional<CompilationUnit> unit = parse(parser, file);
            if (unit.isEmpty()) {
                failed++;
                continue;
            }
            parsed++;
            String relative = relativize(repoRoot, file);
            CompilationUnit compilationUnit = unit.get();
            for (Object raw : compilationUnit.findAll(TypeDeclaration.class)) {
                TypeDeclaration<?> typed = (TypeDeclaration<?>) raw;
                Optional<String> fqn = typed.getFullyQualifiedName();
                if (fqn.isEmpty()) {
                    continue;
                }
                IndexedType indexed = new IndexedType(fqn.get(), typed.getNameAsString(), typed,
                        compilationUnit, relative, TypeClassifier.classify(typed));
                if (typesByFqn.putIfAbsent(fqn.get(), indexed) == null) {
                    typesBySimpleName.computeIfAbsent(typed.getNameAsString(), key -> new ArrayList<>())
                            .add(indexed);
                }
            }
        }
        if (failed > 0) {
            warnings.add(failed + " file .java không parse được (có thể do syntax mới hơn Java 21 "
                    + "hoặc file sinh tự động) - phần luồng đi qua các file đó sẽ bị thiếu.");
        }

        JavaSourceIndex index = new JavaSourceIndex(repoRoot, typesByFqn, typesBySimpleName,
                new LinkedHashMap<>(), warnings, parsed);
        index.buildImplementationMap();

        log.info("index xong {} file, {} type trong {} ms ({} source root)", parsed, typesByFqn.size(),
                (System.nanoTime() - startNanos) / 1_000_000, sourceRoots.size());
        return index;
    }

    private static ParserConfiguration configuration(List<Path> sourceRoots) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        // false = cho phép resolve cả type trên classpath của chính agent (Spring, JDK),
        // giúp nhận ra RestTemplate/JpaRepository khi repo đích cũng dùng Spring.
        solver.add(new ReflectionTypeSolver(false));
        sourceRoots.forEach(sourceRoot -> solver.add(new JavaParserTypeSolver(sourceRoot.toFile())));

        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setAttributeComments(false)
                .setLexicalPreservationEnabled(false);
    }

    private static Optional<CompilationUnit> parse(JavaParser parser, Path file) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            return result.isSuccessful() ? result.getResult() : Optional.empty();
        }
        catch (IOException ex) {
            log.debug("không đọc được {}: {}", file, ex.getMessage());
            return Optional.empty();
        }
        catch (RuntimeException ex) {
            log.debug("parse lỗi {}: {}", file, ex.toString());
            return Optional.empty();
        }
    }

    private static List<Path> findSourceRoots(Path repoRoot) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoRoot, 8)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("src", "main", "java")))
                    .filter(path -> !isSkipped(repoRoot, path))
                    .forEach(roots::add);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Không quét được thư mục repo: " + repoRoot, ex);
        }
        return roots;
    }

    private static List<Path> collectJavaFiles(List<Path> sourceRoots, int limit, List<String> warnings) {
        Set<Path> files = new LinkedHashSet<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                        .filter(path -> !isSkipped(sourceRoot, path))
                        .forEach(files::add);
            }
            catch (IOException ex) {
                warnings.add("Không đọc được " + sourceRoot + ": " + ex.getMessage());
            }
        }
        if (files.size() > limit) {
            warnings.add("Repo có " + files.size() + " file .java, chỉ parse " + limit
                    + " file đầu (analysis.max-source-files). Luồng có thể bị thiếu nhánh.");
            return files.stream().limit(limit).toList();
        }
        return List.copyOf(files);
    }

    private static boolean isSkipped(Path base, Path path) {
        Path relative = base.relativize(path);
        for (Path segment : relative) {
            if (SKIPPED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path repoRoot, Path file) {
        try {
            return repoRoot.relativize(file).toString().replace('\\', '/');
        }
        catch (IllegalArgumentException ex) {
            return file.toString().replace('\\', '/');
        }
    }

    /**
     * Map interface/abstract class -> các lớp hiện thực. Đây là mắt xích quyết định: Spring
     * inject qua interface, nếu không nối được OrderService -> OrderServiceImpl thì diagram
     * dừng ngay ở tầng service.
     */
    private void buildImplementationMap() {
        for (IndexedType type : this.typesByFqn.values()) {
            if (!(type.declaration() instanceof ClassOrInterfaceDeclaration declaration)) {
                continue;
            }
            List<ClassOrInterfaceType> parents = new ArrayList<>();
            parents.addAll(declaration.getImplementedTypes());
            parents.addAll(declaration.getExtendedTypes());
            for (ClassOrInterfaceType parent : parents) {
                resolveTypeName(type.unit(), parent.getNameAsString()).ifPresent(parentFqn ->
                        this.implementationsByParent
                                .computeIfAbsent(parentFqn, key -> new ArrayList<>())
                                .add(type));
            }
        }
    }

    /**
     * Đổi tên type viết tắt trong một file thành FQN, theo đúng thứ tự Java tra tên:
     * FQN sẵn có -> import tường minh -> cùng package -> import wildcard -> tên duy nhất
     * trong toàn repo.
     */
    public Optional<String> resolveTypeName(CompilationUnit unit, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = typeName.trim();
        int generics = trimmed.indexOf('<');
        final String name = (generics > 0) ? trimmed.substring(0, generics) : trimmed;

        if (this.typesByFqn.containsKey(name)) {
            return Optional.of(name);
        }
        // type lồng nhau viết dạng Outer.Inner
        String outerMost = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String suffix = name.contains(".") ? name.substring(name.indexOf('.')) : "";

        if (unit != null) {
            for (ImportDeclaration importDeclaration : unit.getImports()) {
                if (importDeclaration.isAsterisk() || importDeclaration.isStatic()) {
                    continue;
                }
                String imported = importDeclaration.getNameAsString();
                if (imported.endsWith("." + outerMost)) {
                    String candidate = imported + suffix;
                    if (this.typesByFqn.containsKey(candidate)) {
                        return Optional.of(candidate);
                    }
                    return Optional.of(imported);
                }
            }
            Optional<String> samePackage = unit.getPackageDeclaration()
                    .map(pkg -> pkg.getNameAsString() + "." + name)
                    .filter(this.typesByFqn::containsKey);
            if (samePackage.isPresent()) {
                return samePackage;
            }
            for (ImportDeclaration importDeclaration : unit.getImports()) {
                if (!importDeclaration.isAsterisk() || importDeclaration.isStatic()) {
                    continue;
                }
                String candidate = importDeclaration.getNameAsString() + "." + name;
                if (this.typesByFqn.containsKey(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        List<IndexedType> bySimpleName = this.typesBySimpleName.get(outerMost);
        if (bySimpleName != null && bySimpleName.size() == 1) {
            return Optional.of(bySimpleName.get(0).fqn() + suffix);
        }
        return Optional.empty();
    }

    public Optional<IndexedType> type(String fqn) {
        return Optional.ofNullable(this.typesByFqn.get(fqn));
    }

    /**
     * Tra theo tên ngắn. Dùng khi chỉ biết tên lớp mà không biết package - ví dụ tên exception
     * đọc được từ một câu {@code throw new OrderNotFoundException(...)}.
     */
    public List<IndexedType> typesBySimpleName(String simpleName) {
        return List.copyOf(this.typesBySimpleName.getOrDefault(simpleName, List.of()));
    }

    public boolean contains(String fqn) {
        return this.typesByFqn.containsKey(fqn);
    }

    /**
     * Các lớp hiện thực trực tiếp hoặc gián tiếp của một interface/abstract class.
     *
     * <p>Gộp theo FQN, không theo equals của record: {@code Node.equals} của JavaParser so sánh
     * cấu trúc AST rất sâu, dùng nó làm khoá Set thì vừa chậm vừa có thể gộp sai hai lớp
     * khác nhau nhưng nội dung giống nhau.
     */
    public List<IndexedType> implementations(String parentFqn) {
        if (!this.implementationsByParent.containsKey(parentFqn)) {
            return List.of();
        }
        Map<String, IndexedType> byFqn = new LinkedHashMap<>();
        collectImplementations(parentFqn, byFqn, new LinkedHashSet<>());
        return List.copyOf(byFqn.values());
    }

    private void collectImplementations(String parentFqn, Map<String, IndexedType> sink, Set<String> visited) {
        if (!visited.add(parentFqn)) {
            return;
        }
        for (IndexedType child : this.implementationsByParent.getOrDefault(parentFqn, List.of())) {
            if (child.isInterface() || child.isAbstract()) {
                collectImplementations(child.fqn(), sink, visited);
            }
            else {
                sink.putIfAbsent(child.fqn(), child);
            }
        }
    }

    public Collection<IndexedType> allTypes() {
        return this.typesByFqn.values();
    }

    public List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    public Path root() {
        return this.root;
    }

    public int parsedFiles() {
        return this.parsedFiles;
    }
}
