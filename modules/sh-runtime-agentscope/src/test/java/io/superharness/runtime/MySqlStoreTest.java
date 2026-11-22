package io.superharness.runtime;

import com.mysql.cj.jdbc.MysqlDataSource;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

class MySqlStoreTest {
    @Test
    void jdbcMySql() throws Exception {
        try (var db = new MySQLContainer<>("mysql:8.4.13")) {
            db.start();
            var ds = new MysqlDataSource();
            ds.setURL(db.getJdbcUrl());
            ds.setUser(db.getUsername());
            ds.setPassword(db.getPassword());
            HelloHarnessTest.verifyStore(ds);
        }
    }
}
