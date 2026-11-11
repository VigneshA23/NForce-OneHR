package com.nforce.onehr.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Validates that the role-categorisation / attendance-policy-subject JPQL parses and resolves
 * against the real entity model. DDL is disabled on purpose: several entities use Postgres-only
 * column definitions that H2 can't create, so this checks query SEMANTICS at repository start-up
 * (Spring Data compiles every {@code @Query} then), not data. Row-level behaviour is covered by
 * service tests against mocked repositories.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class UserRepositoryQueriesTest {

    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;

    @Test
    void queriesCompileAgainstTheEntityModel() {
        assertNotNull(userRepository);
        assertNotNull(employeeRepository);
    }
}
