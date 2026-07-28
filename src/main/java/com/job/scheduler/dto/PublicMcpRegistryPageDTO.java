package com.job.scheduler.dto;

import java.util.List;

/** One cursor-addressable page from the public MCP registry. */
public record PublicMcpRegistryPageDTO(
        List<PublicMcpServerDTO> servers,
        String nextCursor
) {
}
