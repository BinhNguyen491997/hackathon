package com.example.aihackathon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// bắt AnalysisProperties (prefix "analysis") thành bean, không cần khai báo thủ công
@ConfigurationPropertiesScan
public class AiHackathonApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiHackathonApplication.class, args);
    }

}
