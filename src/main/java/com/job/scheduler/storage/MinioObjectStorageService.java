package com.job.scheduler.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "scheduler.storage.enabled", havingValue = "true")
public class MinioObjectStorageService implements ObjectStorageService {
    private static final String PROVIDER = "MINIO";
    private static final Set<String> MISSING_OBJECT_CODES = Set.of("NoSuchKey", "NoSuchObject", "NotFound");
    private static final Set<String> EXISTING_BUCKET_CODES = Set.of("BucketAlreadyExists", "BucketAlreadyOwnedByYou");

    private final MinioClient minioClient;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioObjectStorageService(
            MinioClient minioClient,
            @Value("${scheduler.storage.bucket}") String bucket
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public StorageRef put(String key, byte[] data, String contentType) {
        try {
            ensureBucket();
            var response = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .contentType(contentType)
                            .stream(new ByteArrayInputStream(data), (long) data.length, -1L)
                            .build()
            );
            return new StorageRef(
                    PROVIDER,
                    bucket,
                    key,
                    response.versionId(),
                    contentType,
                    data.length,
                    sha256(data)
            );
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not store object " + key, exception);
        }
    }

    @Override
    public byte[] get(StorageRef ref) {
        validateProvider(ref);
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(ref.bucket())
                        .object(ref.key())
                        .versionId(ref.versionId())
                        .build()
        )) {
            return response.readAllBytes();
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not read object " + ref.key(), exception);
        }
    }

    @Override
    public void delete(StorageRef ref) {
        validateProvider(ref);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(ref.bucket())
                            .object(ref.key())
                            .versionId(ref.versionId())
                            .build()
            );
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not delete object " + ref.key(), exception);
        }
    }

    @Override
    public boolean exists(StorageRef ref) {
        validateProvider(ref);
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(ref.bucket())
                            .object(ref.key())
                            .versionId(ref.versionId())
                            .build()
            );
            return true;
        } catch (ErrorResponseException exception) {
            if (MISSING_OBJECT_CODES.contains(exception.errorResponse().code())) {
                return false;
            }
            throw new ObjectStorageException("Could not inspect object " + ref.key(), exception);
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not inspect object " + ref.key(), exception);
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (ErrorResponseException exception) {
                if (!EXISTING_BUCKET_CODES.contains(exception.errorResponse().code())) {
                    throw exception;
                }
            }
        }
        bucketReady = true;
    }

    private void validateProvider(StorageRef ref) {
        if (!PROVIDER.equals(ref.provider())) {
            throw new IllegalArgumentException("Unsupported storage provider: " + ref.provider());
        }
    }

    private String sha256(byte[] data) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
