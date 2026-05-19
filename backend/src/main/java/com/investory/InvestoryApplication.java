package com.investory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InvestoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvestoryApplication.class, args);
    }
}
