package com.xiaomi.education;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EducationAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EducationAiApplication.class, args);
    }
}
