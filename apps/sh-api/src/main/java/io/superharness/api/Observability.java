package io.superharness.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.superharness.persistence.Sql;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;

import org.springframework.context.annotation.*;

import java.util.List;

@Configuration
class Observability {
    @Bean
    OpenAPI contract() {
        return new OpenAPI()
                .info(new Info().title("Super Harness API").version("1"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearer",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    @Bean
    MeterBinder runGauges(Sql sql) {
        return registry -> {
            for (String status :
                    List.of(
                            "QUEUED",
                            "RUNNING",
                            "CLAIMED",
                            "RECOVERY_REQUIRED",
                            "WAITING_APPROVAL")) {
                Gauge.builder(
                                "harness.runs",
                                sql,
                                db ->
                                        db.count(
                                                "SELECT COUNT(*) FROM agent_runs WHERE status=?",
                                                status))
                        .tag("status", status)
                        .description("Current runs in this state on the platform primary")
                        .register(registry);
            }
        };
    }
}
