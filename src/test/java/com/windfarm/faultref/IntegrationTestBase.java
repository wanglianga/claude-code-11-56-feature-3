package com.windfarm.faultref;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * Base for integration tests: a real PostgreSQL database (faultref_test),
 * truncated between tests so every scenario starts from an empty schema.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        List<String> tables = jdbc.queryForList("""
                select tablename from pg_tables where schemaname = 'public'
                  and tablename <> 'flyway_schema_history'
                """, Map.of(), String.class);
        if (!tables.isEmpty()) {
            String joined = String.join(", ", tables.stream().map(t -> "public." + t).toList());
            jdbc.getJdbcTemplate().execute("truncate table " + joined + " cascade");
        }
    }
}
