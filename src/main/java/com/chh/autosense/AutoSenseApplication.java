package com.chh.autosense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.chh.autosense.config")
@EnableAsync
@EnableScheduling
public class AutoSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoSenseApplication.class, args);
    }

}
