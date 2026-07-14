package com.job.scheduler.service;

import com.job.scheduler.entity.McpServer;

import java.util.Optional;

/**
 * Resolves the bearer credential for an MCP server at connect time.
 *
 * <p>{@link McpServer#getAuthTokenRef()} is only a <em>reference</em> (a name or
 * path) persisted in the database; the secret itself lives in the deployment
 * tier (environment variable, mounted file, or an external secret store) and is
 * never stored in the database. Implementations dereference that ref into the
 * actual token.
 *
 * <p>Swapping the backing store (env today, Vault/OAuth later) is a matter of
 * providing a different bean; callers depend only on this interface.
 */
public interface McpTokenResolver {

    /**
     * Resolve the secret for a server's {@code authTokenRef}.
     *
     * @return the token, or {@link Optional#empty()} when the server does not use
     *         bearer auth or no secret is configured for its ref
     * @throws IllegalStateException when the server requires a token but is
     *         misconfigured (missing ref, unreadable/empty token file)
     */
    Optional<String> resolve(McpServer server);
}
