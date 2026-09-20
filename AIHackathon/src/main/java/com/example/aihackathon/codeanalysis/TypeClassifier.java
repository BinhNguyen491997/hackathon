package com.example.aihackathon.codeanalysis;

import java.util.List;
import java.util.Set;

import com.example.aihackathon.codeanalysis.model.ParticipantKind;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

/**
 * Suy ra vai của một lớp: annotation Spring trước, sau đó mới tới quy ước đặt tên.
 *
 * <p>Thứ tự này quan trọng: annotation là thứ Spring thực sự dùng lúc runtime, còn tên lớp
 * chỉ là quy ước và hay sai. Nhưng nhiều dự án đặt @Service ở lớp Impl và để interface trần,
 * nên vẫn cần nhánh theo tên để không rơi hết vào UNKNOWN.
 */
final class TypeClassifier {

    private static final Set<String> SPRING_DATA_BASES = Set.of(
            "Repository", "CrudRepository", "JpaRepository", "PagingAndSortingRepository",
            "ListCrudRepository", "ListPagingAndSortingRepository", "ReactiveCrudRepository",
            "MongoRepository", "ElasticsearchRepository", "R2dbcRepository", "CassandraRepository",
            "JpaSpecificationExecutor", "QuerydslPredicateExecutor","Dao");

    /** Lớp hạ tầng nằm ngoài repo nhưng đáng hiện lên diagram vì nó là ranh giới hệ thống. */
    private static final Set<String> EXTERNAL_TYPES = Set.of(
            "RestTemplate", "TestRestTemplate", "WebClient", "RestClient", "HttpClient", "OkHttpClient",
            "JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient", "EntityManager", "SessionFactory",
            "DSLContext", "MongoTemplate", "RedisTemplate", "StringRedisTemplate",
            "KafkaTemplate", "RabbitTemplate", "JmsTemplate", "StreamBridge",
            "ApplicationEventPublisher", "S3Client", "AmazonS3", "SqsClient", "SnsClient");

    private TypeClassifier() {
    }

    static ParticipantKind classify(TypeDeclaration<?> type) {
        ParticipantKind decisive = byDecisiveAnnotation(type.getAnnotations());
        if (decisive != ParticipantKind.UNKNOWN) {
            return decisive;
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration && extendsSpringData(declaration)) {
            return ParticipantKind.REPOSITORY;
        }
        if (type instanceof RecordDeclaration || type instanceof EnumDeclaration) {
            return ParticipantKind.DATA;
        }
        ParticipantKind byName = byName(type.getNameAsString());
        if (byName != ParticipantKind.UNKNOWN) {
            return byName;
        }
        // @Component/@Configuration là marker chung chung, không nói gì về vai trong luồng,
        // nên chỉ dùng khi tên lớp cũng không gợi ý được gì. Nếu xét nó trước tên lớp thì
        // mọi Mapper/Validator (vốn hầu hết là @Component) sẽ bị coi là nghiệp vụ và bị mở
        // chi tiết, làm diagram phình ra vô ích.
        return hasWeakComponentAnnotation(type) ? ParticipantKind.COMPONENT : ParticipantKind.UNKNOWN;
    }

    /** Vai của một lớp mà ta không có source (thư viện, framework). */
    static ParticipantKind classifyExternal(String simpleName) {
        if (simpleName == null) {
            return ParticipantKind.UNKNOWN;
        }
        String name = simpleName.contains(".")
                ? simpleName.substring(simpleName.lastIndexOf('.') + 1)
                : simpleName;
        return EXTERNAL_TYPES.contains(name) ? ParticipantKind.EXTERNAL : ParticipantKind.UNKNOWN;
    }

    private static ParticipantKind byDecisiveAnnotation(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            switch (simpleName(annotation)) {
                case "RestController", "Controller" -> {
                    return ParticipantKind.CONTROLLER;
                }
                case "Service" -> {
                    return ParticipantKind.SERVICE;
                }
                case "Repository" -> {
                    return ParticipantKind.REPOSITORY;
                }
                case "FeignClient", "HttpExchange" -> {
                    return ParticipantKind.EXTERNAL;
                }
                case "Entity", "Table", "Embeddable", "Document" -> {
                    return ParticipantKind.DATA;
                }
                default -> {
                    // annotation khác không kết luận được vai
                }
            }
        }
        return ParticipantKind.UNKNOWN;
    }

    private static boolean hasWeakComponentAnnotation(TypeDeclaration<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String name = simpleName(annotation);
            if (name.equals("Component") || name.equals("Configuration")
                    || name.equals("RestControllerAdvice") || name.equals("ControllerAdvice")) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(AnnotationExpr annotation) {
        String actual = annotation.getNameAsString();
        return actual.contains(".") ? actual.substring(actual.lastIndexOf('.') + 1) : actual;
    }

    private static boolean extendsSpringData(ClassOrInterfaceDeclaration declaration) {
        if (!declaration.isInterface()) {
            return false;
        }
        for (ClassOrInterfaceType parent : declaration.getExtendedTypes()) {
            if (SPRING_DATA_BASES.contains(parent.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private static ParticipantKind byName(String name) {
        if (name.endsWith("Controller") || name.endsWith("Resource") || name.endsWith("Endpoint")) {
            return ParticipantKind.CONTROLLER;
        }
        if (name.endsWith("Service") || name.endsWith("ServiceImpl") || name.endsWith("Manager")
                || name.endsWith("Facade") || name.endsWith("UseCase") || name.endsWith("Handler")
                || name.endsWith("Processor") || name.endsWith("Orchestrator")) {
            return ParticipantKind.SERVICE;
        }
        if (name.endsWith("Repository") || name.endsWith("RepositoryImpl") || name.endsWith("Dao")
                || name.endsWith("DAO") || name.endsWith("Store")) {
            return ParticipantKind.REPOSITORY;
        }
        if (name.endsWith("Client") || name.endsWith("Gateway") || name.endsWith("Adapter")
                || name.endsWith("Publisher") || name.endsWith("Producer") || name.endsWith("Sender")) {
            return ParticipantKind.EXTERNAL;
        }
        if (name.endsWith("Mapper") || name.endsWith("Converter") || name.endsWith("Validator")
                || name.endsWith("Util") || name.endsWith("Utils") || name.endsWith("Helper")
                || name.endsWith("Factory") || name.endsWith("Assembler") || name.endsWith("Builder")) {
            return ParticipantKind.SUPPORT;
        }
        if (name.endsWith("Dto") || name.endsWith("DTO") || name.endsWith("Request")
                || name.endsWith("Response") || name.endsWith("Entity") || name.endsWith("Model")
                || name.endsWith("Form") || name.endsWith("Payload") || name.endsWith("Vo")
                || name.endsWith("VO") || name.endsWith("Command") || name.endsWith("Event")) {
            return ParticipantKind.DATA;
        }
        return ParticipantKind.UNKNOWN;
    }
}
