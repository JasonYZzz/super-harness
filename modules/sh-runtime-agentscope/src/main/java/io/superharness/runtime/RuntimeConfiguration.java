package io.superharness.runtime;

import com.zaxxer.hikari.*;

import io.superharness.runtimespi.AgentRuntime;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

@Configuration
public class RuntimeConfiguration {
    record RuntimePool(HikariDataSource dataSource) implements AutoCloseable {
        public void close() {
            dataSource.close();
        }
    }

    @Bean
    @Lazy
    RuntimePool runtimePool(Environment env) {
        return pool(env, "SH_RUNTIME_JDBC", "agent-state");
    }

    static RuntimePool coordinationPool(Environment env) {
        return pool(env, "SH_COORDINATION_JDBC", "snapshot-coordination");
    }

    private static RuntimePool pool(Environment env, String prefix, String name) {
        String defaultUrl =
                "jdbc:mysql://"
                        + env.getProperty("MYSQL_HOST", "localhost")
                        + ":"
                        + env.getProperty("MYSQL_PORT", "3306")
                        + "/"
                        + env.getProperty("AGENTSCOPE_MYSQL_DATABASE", "super_harness_runtime")
                        + "?connectionTimeZone=UTC";
        var config = new HikariConfig();
        config.setJdbcUrl(env.getProperty(prefix + "_URL", defaultUrl));
        config.setUsername(env.getProperty(prefix + "_USER", env.getProperty("MYSQL_USER")));
        config.setPassword(
                env.getProperty(prefix + "_PASSWORD", env.getProperty("MYSQL_PASSWORD")));
        config.setMaximumPoolSize(8);
        config.setPoolName(name);
        return new RuntimePool(new HikariDataSource(config));
    }

    @Bean
    AgentRuntime runtime(StoreConfiguration.Store store, Environment env) {
        return new HarnessRuntime(
                store.backend(),
                Path.of(env.getProperty("SH_WORKSPACE_ROOT", ".local/workspaces")),
                env.getProperty("SH_SANDBOX_IMAGE"),
                env);
    }
}
