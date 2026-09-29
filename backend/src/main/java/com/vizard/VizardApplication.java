package com.vizard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VizardApplication {

    public static void main(String[] args) {
        SpringApplication.run(VizardApplication.class, args);
    }
}
