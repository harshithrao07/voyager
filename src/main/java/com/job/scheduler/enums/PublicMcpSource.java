package com.job.scheduler.enums;

/**
 * Where a public MCP catalog entry came from: the JSON catalog shipped in-repo
 * ({@link #BUNDLED}, always available/offline) or a live external registry
 * ({@link #EXTERNAL}, only when {@code scheduler.mcp.registry.external.enabled}).
 */
public enum PublicMcpSource {
    BUNDLED,
    EXTERNAL
}
