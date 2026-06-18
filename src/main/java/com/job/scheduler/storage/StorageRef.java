package com.job.scheduler.storage;

public record StorageRef(
        String provider,
        String bucket,
        String key,
        String versionId,
        String contentType,
        long sizeBytes,
        String checksum
) {
}
