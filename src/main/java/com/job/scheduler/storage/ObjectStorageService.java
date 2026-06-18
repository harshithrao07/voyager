package com.job.scheduler.storage;

public interface ObjectStorageService {
    StorageRef put(String key, byte[] data, String contentType);

    byte[] get(StorageRef ref);

    void delete(StorageRef ref);

    boolean exists(StorageRef ref);
}
