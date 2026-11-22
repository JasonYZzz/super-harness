package io.superharness.runtime;

import com.mongodb.client.MongoClients;

import io.agentscope.extensions.cos.CosDistributedStore;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.mongodb.MongoDistributedStore;
import io.agentscope.extensions.oss.OssDistributedStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import redis.clients.jedis.JedisPooled;

import java.net.URI;

@Configuration
class StoreConfiguration {
    record Store(DistributedStore backend, Runnable shutdown) implements AutoCloseable {
        public void close() {
            shutdown.run();
        }
    }

    @Bean
    Store runtimeStore(
            org.springframework.beans.factory.ObjectProvider<RuntimeConfiguration.RuntimePool> pool,
            Environment env) {
        String backend = env.getProperty("SH_STORE_BACKEND", "jdbc");
        String prefix = env.getProperty("SH_STORE_PREFIX", "superharness/");
        switch (backend) {
            case "jdbc":
                {
                    var dataSource = pool.getObject().dataSource();
                    var jdbc = JdbcDistributedStore.create(dataSource);
                    if (!dataSource.getJdbcUrl().startsWith("jdbc:sqlite:"))
                        return new Store(jdbc, () -> {});
                    // Xerial's lengthless setBinaryStream is unsupported by the pinned SDK snapshot
                    // client.
                    var coordination = RuntimeConfiguration.coordinationPool(env);
                    try {
                        var combined =
                                sqliteWithCoordinator(
                                        jdbc,
                                        JdbcDistributedStore.create(coordination.dataSource()));
                        return new Store(combined, coordination::close);
                    } catch (RuntimeException failed) {
                        coordination.close();
                        throw failed;
                    }
                }
            case "redis":
                {
                    var client =
                            new JedisPooled(URI.create(env.getRequiredProperty("SH_REDIS_URL")));
                    return new Store(
                            RedisDistributedStore.fromJedis(client, prefix), client::close);
                }
            case "mongodb":
                {
                    var client = MongoClients.create(env.getRequiredProperty("SH_MONGODB_URL"));
                    return new Store(
                            MongoDistributedStore.create(
                                    client, env.getRequiredProperty("SH_MONGODB_DATABASE")),
                            client::close);
                }
            case "oss":
                {
                    var client =
                            new com.aliyun.oss.OSSClientBuilder()
                                    .build(
                                            env.getRequiredProperty("SH_OSS_ENDPOINT"),
                                            env.getRequiredProperty("SH_OSS_KEY_ID"),
                                            env.getRequiredProperty("SH_OSS_KEY_SECRET"));
                    return compose(
                            pool.getObject(),
                            OssDistributedStore.create(
                                    client, env.getRequiredProperty("SH_OSS_BUCKET"), prefix),
                            client::shutdown);
                }
            case "cos":
                {
                    var credentials =
                            new com.qcloud.cos.auth.BasicCOSCredentials(
                                    env.getRequiredProperty("SH_COS_KEY_ID"),
                                    env.getRequiredProperty("SH_COS_KEY_SECRET"));
                    var config =
                            new com.qcloud.cos.ClientConfig(
                                    new com.qcloud.cos.region.Region(
                                            env.getRequiredProperty("SH_COS_REGION")));
                    var client = new com.qcloud.cos.COSClient(credentials, config);
                    return compose(
                            pool.getObject(),
                            CosDistributedStore.create(
                                    client, env.getRequiredProperty("SH_COS_BUCKET"), prefix),
                            client::shutdown);
                }
            default:
                throw new IllegalArgumentException("Unknown SH_STORE_BACKEND");
        }
    }

    static DistributedStore sqliteWithCoordinator(
            DistributedStore sqlite, DistributedStore coordinator) {
        return DistributedStore.builder()
                .agentStateStore(sqlite.agentStateStore())
                .baseStore(sqlite.baseStore())
                .sandboxExecutionGuard(coordinator.sandboxExecutionGuard())
                .sandboxSnapshotSpec(coordinator.sandboxSnapshotSpec())
                .build();
    }

    private Store compose(
            RuntimeConfiguration.RuntimePool pool, DistributedStore objects, Runnable close) {
        var jdbc = JdbcDistributedStore.create(pool.dataSource());
        return new Store(
                DistributedStore.builder()
                        .agentStateStore(jdbc.agentStateStore())
                        .sandboxExecutionGuard(jdbc.sandboxExecutionGuard())
                        .baseStore(objects.baseStore())
                        .sandboxSnapshotSpec(objects.sandboxSnapshotSpec())
                        .build(),
                close);
    }
}
