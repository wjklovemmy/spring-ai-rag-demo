package com.example.springairagdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.example.springairagdemo", "com.example.user"})
@MapperScan({"com.example.springairagdemo.mapper", "com.example.user.mapper"})
public class SpringAiRagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagDemoApplication.class, args);
    }

}
