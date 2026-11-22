package io.superharness.reconciler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.superharness")
@EnableScheduling
public class ReconcilerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconcilerApplication.class, args);
    }
}
