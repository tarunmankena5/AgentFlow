package com.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.agentflow.identity.AuthService;
import com.agentflow.identity.JwtService;
import com.agentflow.organization.OrganizationController;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Verifies that the context boots and Flyway applies the baseline schema on real infrastructure. */
@SpringBootTest
@Testcontainers
class FoundationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Kafka is not used by the current execution path. Keep Kafka auto-configuration
        // pointed at the normal local endpoint without starting an unrelated broker here.
        r.add("agentflow.security.jwt-secret", () -> "test-secret-test-secret-test-secret-123456");
    }

    @Autowired DataSource dataSource;
    @Autowired AuthService authService;
    @Autowired JwtService jwtService;

    @Test
    void flywayAppliesBaselineSchemaAndSeedsRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer roles = jdbc.queryForObject("select count(*) from roles", Integer.class);
        assertThat(roles).isEqualTo(6);
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'workflow_executions'",
                Integer.class);
        assertThat(tables).isEqualTo(1);
        Integer seededTools = jdbc.queryForObject("select count(*) from tools where organization_id is null", Integer.class);
        assertThat(seededTools).isEqualTo(5);
    }

    @Test
    void authenticationRotatesRefreshTokensAndRevokesReplayedFamilies() {
        String email = "refresh-" + UUID.randomUUID() + "@example.test";
        authService.register(email, "secure-test-password-123", "Integration User");
        var initial = authService.login(email, "secure-test-password-123", "test", "127.0.0.1");
        assertThat(jwtService.verify(initial.accessToken()).email()).isEqualTo(email);

        var rotated = authService.refresh(initial.refreshToken(), "test", "127.0.0.1");
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThatThrownBy(() -> authService.refresh(initial.refreshToken(), "test", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken(), "test", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void organizationRolesDoNotCrossTenantBoundaries() {
        String email = "tenant-" + UUID.randomUUID() + "@example.test";
        var user = authService.register(email, "secure-test-password-123", "Tenant User");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        jdbc.update("insert into organizations(id,name,slug) values(?,?,?)", first, "First", "first-" + first);
        jdbc.update("insert into organizations(id,name,slug) values(?,?,?)", second, "Second", "second-" + second);
        jdbc.update("insert into organization_memberships(organization_id,user_id,role_id) select ?,?,id from roles where name='ORGANIZATION_ADMIN'", first, user.id());

        assertThat(OrganizationController.requireRole(jdbc, first, user.id(), "ORGANIZATION_ADMIN")).isEqualTo("ORGANIZATION_ADMIN");
        assertThatThrownBy(() -> OrganizationController.requireRole(jdbc, second, user.id(), "ORGANIZATION_ADMIN"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
