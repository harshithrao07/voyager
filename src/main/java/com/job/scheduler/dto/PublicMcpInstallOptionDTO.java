package com.job.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.job.scheduler.enums.McpTransport;

import java.util.List;

/**
 * One ready-to-register way to run a public MCP server (an npm package, a Docker
 * image, a remote HTTP endpoint, ...). Maps directly onto the register form: STDIO
 * options carry {@code command}/{@code args}, HTTP options carry
 * {@code baseUrl}/{@code endpoint}. The user still reviews trust and enters secrets
 * before anything is created.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicMcpInstallOptionDTO(
        /** Short human label for the picker, e.g. "npm (npx)", "Docker", "Remote (streamable-http)". */
        String label,
        McpTransport transport,
        // STDIO
        String command,
        List<String> args,
        // HTTP
        String baseUrl,
        String endpoint,
        List<PublicMcpEnvVarDTO> env
) {
}
