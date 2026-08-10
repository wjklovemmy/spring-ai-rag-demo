package com.example.springairagdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.springairagdemo.mapper")
public class SpringAiRagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagDemoApplication.class, args);
    }

}
