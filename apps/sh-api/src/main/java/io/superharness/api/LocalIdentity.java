package io.superharness.api;

import io.superharness.persistence.Sql;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;

@Configuration
@Profile("local")
public class LocalIdentity {
    @Bean
    ApplicationRunner localTenant(Sql sql) {
        return args -> {
            sql.update("INSERT INTO tenants(id) VALUES('local') ON DUPLICATE KEY UPDATE id=id");
            sql.update(
                    """
                    INSERT INTO memberships(tenant_id,user_id,role) VALUES('local','developer','OWNER')
                    ON DUPLICATE KEY UPDATE user_id=user_id
                    """);
        };
    }
}
