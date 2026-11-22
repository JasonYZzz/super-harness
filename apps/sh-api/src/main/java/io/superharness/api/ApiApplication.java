package io.superharness.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.superharness")
@EnableScheduling
public class ApiApplication {
    @org.springframework.context.annotation.Bean
    com.fasterxml.jackson.databind.ObjectMapper eventMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .disable(
                        com.fasterxml.jackson.databind.SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--dev-token")) {
            if (args.length > 2 || (args.length == 2 && !args[1].equals("--metrics")))
                throw new IllegalArgumentException("Usage: --dev-token [--metrics]");
            System.out.println(LocalToken.issue(args.length == 2));
            return;
        }
        SpringApplication.run(ApiApplication.class, args);
    }
}
