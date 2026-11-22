package io.superharness.runtime;

import com.mongodb.client.MongoClients;

import io.agentscope.extensions.mongodb.MongoDistributedStore;
import io.agentscope.extensions.redis.RedisDistributedStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteDataSource;
import org.testcontainers.containers.*;

import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;

class AlternativeStoresIT {
    @TempDir Path root;

    @Test
    void sqlite() throws Exception {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
        var coordinator = new org.h2.jdbcx.JdbcDataSource();
        coordinator.setURL("jdbc:h2:mem:sqlite-coordination;DB_CLOSE_DELAY=-1");
        HelloHarnessTest.verifyStore(
                () ->
                        StoreConfiguration.sqliteWithCoordinator(
                                io.agentscope.extensions.jdbc.JdbcDistributedStore.create(ds),
                                io.agentscope.extensions.jdbc.JdbcDistributedStore.create(
                                        coordinator)));
    }

    @Test
    void postgres() throws Exception {
        try (var server = new PostgreSQLContainer<>("postgres:16-alpine")) {
            server.start();
            var ds = new PGSimpleDataSource();
            ds.setUrl(server.getJdbcUrl());
            ds.setUser(server.getUsername());
            ds.setPassword(server.getPassword());
            HelloHarnessTest.verifyStore(ds);
        }
    }

    @Test
    void redis() throws Exception {
        try (var server = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            server.start();
            try (var client = new JedisPooled(server.getHost(), server.getMappedPort(6379))) {
                HelloHarnessTest.verifyStore(
                        () -> RedisDistributedStore.fromJedis(client, "test/"));
            }
        }
    }

    @Test
    void mongo() throws Exception {
        try (var server = new GenericContainer<>("mongo:7.0").withExposedPorts(27017)) {
            server.start();
            try (var client =
                    MongoClients.create(
                            "mongodb://" + server.getHost() + ":" + server.getMappedPort(27017))) {
                HelloHarnessTest.verifyStore(() -> MongoDistributedStore.create(client, "test"));
            }
        }
    }
}
