package com.snowworld;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan(basePackages = "com.snowworld", annotationClass = Mapper.class)
public class SnowWorldApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnowWorldApplication.class, args);
    }
}
