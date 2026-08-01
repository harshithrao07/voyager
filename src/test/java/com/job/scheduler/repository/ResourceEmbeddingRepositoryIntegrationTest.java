package com.job.scheduler.repository;

import com.job.scheduler.entity.ResourceEmbedding;
import com.job.scheduler.enums.ResourceEmbeddingType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises pgvector nearest-neighbour retrieval against a real Postgres with the vector
 * extension. Confirms {@code findNearestResourceIds} ranks by cosine distance and stays scoped
 * to a resource type.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResourceEmbeddingRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("jobscheduler")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withInitScript("db/pgvector-init.sql");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        // @DataJpaTest slices out spring.sql.init; the extension comes from the init script above.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ResourceEmbeddingRepository repository;

    @Test
    void ranksByCosineDistanceAndScopesToType() {
        UUID near = save(ResourceEmbeddingType.FUNCTION, axis(0));            // identical to query
        UUID mid = save(ResourceEmbeddingType.FUNCTION, blend());            // 45° from query
        save(ResourceEmbeddingType.FUNCTION, axis(1));                       // orthogonal to query
        UUID otherType = save(ResourceEmbeddingType.MCP_TOOL, axis(0));      // must be excluded

        List<UUID> nearest = repository.findNearestResourceIds(
                ResourceEmbeddingType.FUNCTION, axis(0), PageRequest.of(0, 2));

        assertThat(nearest).containsExactly(near, mid);
        assertThat(nearest).doesNotContain(otherType);
    }

    @Test
    void findsSourceHashAndPrunesByType() {
        UUID id = save(ResourceEmbeddingType.FUNCTION, axis(0));

        assertThat(repository.findSourceHash(ResourceEmbeddingType.FUNCTION, id)).isPresent();
        assertThat(repository.findResourceIdsByType(ResourceEmbeddingType.FUNCTION))
                .containsExactly(id);

        repository.deleteByResourceTypeAndResourceId(ResourceEmbeddingType.FUNCTION, id);
        assertThat(repository.findResourceIdsByType(ResourceEmbeddingType.FUNCTION)).isEmpty();
    }

    private UUID save(ResourceEmbeddingType type, float[] vector) {
        ResourceEmbedding embedding = new ResourceEmbedding();
        embedding.setResourceType(type);
        embedding.setResourceId(UUID.randomUUID());
        embedding.setEmbedding(vector);
        embedding.setEmbeddingModel("nomic-embed-text");
        embedding.setDimensions(ResourceEmbedding.DIMENSIONS);
        embedding.setSourceHash("hash-" + UUID.randomUUID());
        embedding.setEmbeddedAt(Instant.now());
        return repository.saveAndFlush(embedding).getResourceId();
    }

    /** Unit vector along one axis. */
    private static float[] axis(int index) {
        float[] vector = new float[ResourceEmbedding.DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

    /** Equal weight on the first two axes — 45° from either axis vector. */
    private static float[] blend() {
        float[] vector = new float[ResourceEmbedding.DIMENSIONS];
        vector[0] = 0.7071f;
        vector[1] = 0.7071f;
        return vector;
    }
}
