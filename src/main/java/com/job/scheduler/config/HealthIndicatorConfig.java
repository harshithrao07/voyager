package com.job.scheduler.config;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.enums.McpServerStatus;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import com.job.scheduler.service.Judge0Client;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
public class HealthIndicatorConfig {

    private static final Duration KAFKA_HEALTH_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    public HealthIndicator redisHealthIndicator(RedisConnectionFactory redisConnectionFactory) {
        return () -> {
            try (RedisConnection connection = redisConnectionFactory.getConnection()) {
                String response = connection.ping();
                if ("PONG".equalsIgnoreCase(response)) {
                    return Health.up().build();
                }
                return Health.down().withDetail("response", response).build();
            } catch (RuntimeException exception) {
                return Health.down(exception).build();
            }
        };
    }

    @Bean
    public HealthIndicator kafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        return () -> {
            Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            config.putIfAbsent("request.timeout.ms", (int) KAFKA_HEALTH_TIMEOUT.toMillis());
            config.putIfAbsent("default.api.timeout.ms", (int) KAFKA_HEALTH_TIMEOUT.toMillis());

            try (AdminClient adminClient = AdminClient.create(config)) {
                adminClient.describeCluster().clusterId().get(
                        KAFKA_HEALTH_TIMEOUT.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS
                );
                return Health.up().build();
            } catch (Exception exception) {
                return Health.down(exception).build();
            }
        };
    }

    /**
     * Judge0 backs {@code voyager://function/name} Task resources. Reports UP only when the
     * engine advertises languages and has at least one execution worker; the
     * languages/statuses/worker counts are surfaced as details for diagnostics.
     * Custom indicators like this one are not part of the liveness/readiness
     * groups, so a Judge0 outage marks {@code /actuator/health} degraded without
     * failing the app's probes.
     */
    @Bean
    public HealthIndicator judge0HealthIndicator(Judge0Client judge0Client) {
        return () -> {
            try {
                int languages = judge0Client.listLanguages().size();
                int statuses = judge0Client.countStatuses();
                Judge0Client.WorkerStats workers = judge0Client.workerStats();
                boolean healthy = languages > 0 && workers.total() > 0;
                return (healthy ? Health.up() : Health.down())
                        .withDetail("languages", languages)
                        .withDetail("statuses", statuses)
                        .withDetail("workers", workers.total())
                        .withDetail("availableWorkers", workers.available())
                        .build();
            } catch (RuntimeException exception) {
                return Health.down(exception).build();
            }
        };
    }

    /**
     * Surfaces the MCP client subsystem: how many servers are ENABLED and, per
     * server, how many tools are known and when they were last seen by a sync.
     * Reports UP whenever the catalog is readable — an external MCP server being
     * unreachable is that server's problem, not this app's, so it never marks the
     * app DOWN. DOWN here means the tool catalog itself could not be read.
     */
    @Bean
    public HealthIndicator mcpHealthIndicator(
            McpServerRepository mcpServerRepository,
            McpToolRepository mcpToolRepository
    ) {
        return () -> {
            try {
                List<McpServer> enabledServers = mcpServerRepository
                        .findByStatusOrderByCreatedAtDesc(McpServerStatus.ENABLED);
                Map<String, Object> perServer = new LinkedHashMap<>();
                for (McpServer server : enabledServers) {
                    List<McpTool> knownTools = mcpToolRepository
                            .findByMcpServerAndEnabledTrueOrderByToolNameAsc(server);
                    Instant lastSeenAt = knownTools.stream()
                            .map(McpTool::getLastSeenAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("tools", knownTools.size());
                    detail.put("lastSeenAt", lastSeenAt);
                    perServer.put(server.getServerId(), detail);
                }
                return Health.up()
                        .withDetail("enabledServers", enabledServers.size())
                        .withDetail("servers", perServer)
                        .build();
            } catch (RuntimeException exception) {
                return Health.down(exception).build();
            }
        };
    }
}
