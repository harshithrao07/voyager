package com.job.scheduler.dto;

import com.job.scheduler.enums.McpTrustLevel;

/**
 * One MCP Task in a generated workflow that grants elevated ({@code WRITE} or
 * {@code DESTRUCTIVE}) trust — i.e. the assistant wired in a mutating tool. Surfaced
 * so the user explicitly confirms it before the workflow is created/activated.
 *
 * @param stateName          the ASL state that makes the call
 * @param serverId           the MCP server the tool belongs to
 * @param toolName           the tool being called
 * @param grantedTrustLevel  the ceiling the Task grants via {@code ?trust=...}
 * @param serverDisplayName  the registered server's display name, when known
 * @param serverTrustLevel   the server's configured trust level, when registered
 */
public record ElevatedMcpToolDTO(
        String stateName,
        String serverId,
        String toolName,
        McpTrustLevel grantedTrustLevel,
        String serverDisplayName,
        McpTrustLevel serverTrustLevel
) {
}
