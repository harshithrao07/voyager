package com.job.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.enums.PublicMcpSource;

import java.util.List;

/**
 * A candidate MCP server discovered from the public catalog for a capability the
 * assistant asked for. This is a recommendation only: Voyager never auto-registers
 * external endpoints (they carry their own trust and credentials); the user picks an
 * install option, reviews it, and registers it via the normal MCP server form.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublicMcpServerDTO(
        /** Stable identifier: the registry name (e.g. "io.github.owner/name") or bundled id. */
        String sourceId,
        String name,
        String description,
        String version,
        String repositoryUrl,
        PublicMcpSource source,
        List<PublicMcpInstallOptionDTO> installs,
        /** Trust level to preselect in the form; defaults to the safest, {@code UNTRUSTED}. */
        McpTrustLevel suggestedTrustLevel
) {
}
