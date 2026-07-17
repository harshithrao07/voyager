package com.job.scheduler.service;

import java.util.Optional;

/**
 * Resolves a deployment-owned secret by reference.
 *
 * <p>Callers persist only the reference. The secret value remains in the
 * deployment tier (environment, mounted Kubernetes/Docker Secret, or a future
 * external secret manager) and is materialized only in backend memory.
 */
public interface SecretResolver {

    Optional<String> resolve(String secretRef);

    default String require(String secretRef) {
        String normalizedRef = SecretReferences.requireValidReference(secretRef);
        return resolve(normalizedRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No secret configured for reference: " + normalizedRef
                ));
    }
}
