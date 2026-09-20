package com.example.aihackathon.codeanalysis.model;

/**
 * Vai của một lớp trong luồng, suy ra từ annotation Spring (ưu tiên) rồi tới quy ước tên.
 *
 * <p>Vai quyết định hai thứ: stereotype hiển thị trên diagram, và có đi sâu vào lớp đó
 * hay không (ví dụ ENTITY thì không, nếu không diagram sẽ đầy getter/setter).
 */
public enum ParticipantKind {

    /** @RestController / @Controller - điểm vào của request. */
    CONTROLLER("controller", true),

    /** @Service hoặc tên kết thúc bằng Service - nơi chứa nghiệp vụ, luôn đi sâu vào. */
    SERVICE("service", true),

    /** @Repository, interface Spring Data, DAO - chạm database. */
    REPOSITORY("repository", false),

    /** @FeignClient, RestTemplate, WebClient, Kafka - gọi ra ngoài hệ thống. */
    EXTERNAL("external", false),

    /** @Component/@Bean khác. */
    COMPONENT("component", true),

    /** Mapper, Converter, Validator, Util - phụ trợ, mặc định không đi sâu. */
    SUPPORT("support", false),

    /** @Entity, record, DTO - dữ liệu, không phải participant hành vi. */
    DATA("data", false),

    UNKNOWN("unknown", false);

    private final String stereotype;

    private final boolean businessLogic;

    ParticipantKind(String stereotype, boolean businessLogic) {
        this.stereotype = stereotype;
        this.businessLogic = businessLogic;
    }

    public String stereotype() {
        return this.stereotype;
    }

    /** true = đáng đi sâu vào bên trong để lấy chi tiết nghiệp vụ. */
    public boolean businessLogic() {
        return this.businessLogic;
    }
}
