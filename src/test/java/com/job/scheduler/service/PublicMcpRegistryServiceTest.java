package com.job.scheduler.service;

import com.job.scheduler.dto.PublicMcpEnvVarDTO;
import com.job.scheduler.dto.PublicMcpInstallOptionDTO;
import com.job.scheduler.dto.PublicMcpServerDTO;
import com.job.scheduler.enums.McpTransport;
import com.job.scheduler.enums.PublicMcpSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicMcpRegistryServiceTest {

    private PublicMcpRegistryService service;

    @BeforeEach
    void setUp() {
        service = new PublicMcpRegistryService(new DefaultResourceLoader(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "bundledCatalogLocation", "classpath:mcp/public-catalog.json");
        ReflectionTestUtils.setField(service, "externalEnabled", false);
        service.loadBundledCatalog();
    }

    @Test
    void searchMatchesBundledServerByName() {
        List<PublicMcpServerDTO> results = service.search("github", 10);

        assertThat(results).isNotEmpty();
        PublicMcpServerDTO top = results.get(0);
        assertThat(top.name()).isEqualTo("GitHub");
        assertThat(top.source()).isEqualTo(PublicMcpSource.BUNDLED);
        assertThat(top.installs()).isNotEmpty();
        assertThat(top.installs().get(0).transport()).isEqualTo(McpTransport.STDIO);
        assertThat(top.installs().get(0).env())
                .extracting(PublicMcpEnvVarDTO::name)
                .contains("GITHUB_PERSONAL_ACCESS_TOKEN");
        assertThat(top.installs().get(0).env().get(0).secret()).isTrue();
    }

    @Test
    void nameMatchOutranksDescriptionMatch() {
        // "search" appears in Brave Search's name and in other servers' descriptions.
        List<PublicMcpServerDTO> results = service.search("search", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).name()).contains("Search");
    }

    @Test
    void emptyQueryListsCatalog() {
        List<PublicMcpServerDTO> results = service.search("", 100);
        assertThat(results.size()).isGreaterThan(5);
    }

    @Test
    void limitIsRespected() {
        assertThat(service.search("", 3)).hasSize(3);
    }

    @Test
    void unmatchedQueryReturnsEmpty() {
        assertThat(service.search("zzz-nonexistent-capability", 10)).isEmpty();
    }

    @Test
    void parseExternalMapsNpmPackageToNpx() {
        String body = """
                {
                  "servers": [
                    {
                      "server": {
                        "name": "io.github.acme/weather",
                        "description": "Weather lookups",
                        "version": "1.2.0",
                        "packages": [
                          {
                            "registryType": "npm",
                            "identifier": "@acme/weather-mcp",
                            "version": "1.2.0",
                            "environmentVariables": [
                              { "name": "WEATHER_API_KEY", "description": "key", "isSecret": true, "isRequired": true }
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        List<PublicMcpServerDTO> results = service.parseExternal(body);

        assertThat(results).hasSize(1);
        PublicMcpServerDTO server = results.get(0);
        assertThat(server.sourceId()).isEqualTo("io.github.acme/weather");
        assertThat(server.name()).isEqualTo("weather");
        assertThat(server.source()).isEqualTo(PublicMcpSource.EXTERNAL);

        PublicMcpInstallOptionDTO install = server.installs().get(0);
        assertThat(install.transport()).isEqualTo(McpTransport.STDIO);
        assertThat(install.command()).isEqualTo("npx");
        assertThat(install.args()).containsExactly("-y", "@acme/weather-mcp@1.2.0");
        assertThat(install.env()).hasSize(1);
        assertThat(install.env().get(0).name()).isEqualTo("WEATHER_API_KEY");
        assertThat(install.env().get(0).secret()).isTrue();
    }

    @Test
    void parseExternalMapsRemoteToHttp() {
        String body = """
                {
                  "servers": [
                    {
                      "server": {
                        "name": "io.example/remote",
                        "description": "Remote server",
                        "remotes": [
                          { "type": "streamable-http", "url": "https://mcp.example.com:8443/mcp" }
                        ]
                      }
                    }
                  ]
                }
                """;

        List<PublicMcpServerDTO> results = service.parseExternal(body);

        PublicMcpInstallOptionDTO install = results.get(0).installs().get(0);
        assertThat(install.transport()).isEqualTo(McpTransport.HTTP);
        assertThat(install.baseUrl()).isEqualTo("https://mcp.example.com:8443");
        assertThat(install.endpoint()).isEqualTo("/mcp");
    }

    @Test
    void parseExternalMapsDockerPackage() {
        String body = """
                {
                  "servers": [
                    {
                      "server": {
                        "name": "io.example/docker-server",
                        "packages": [
                          {
                            "registryType": "oci",
                            "identifier": "ghcr.io/example/server",
                            "version": "latest",
                            "environmentVariables": [ { "name": "TOKEN", "isSecret": true } ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        PublicMcpInstallOptionDTO install = service.parseExternal(body).get(0).installs().get(0);
        assertThat(install.command()).isEqualTo("docker");
        assertThat(install.args()).containsSubsequence("run", "-i", "--rm", "-e", "TOKEN", "ghcr.io/example/server:latest");
    }

    @Test
    void parseExternalIgnoresServerWithNoInstallable() {
        String body = """
                { "servers": [ { "server": { "name": "io.example/empty", "description": "no packages" } } ] }
                """;
        assertThat(service.parseExternal(body)).isEmpty();
    }

    @Test
    void parseExternalHandlesMalformedJson() {
        assertThat(service.parseExternal("not json")).isEmpty();
    }
}
