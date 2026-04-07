package com.aidevplatform.service;

import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Handles file storage for requirement files and context documents.
 *
 * Supports two storage backends:
 *   local — saves files to the local filesystem (no external service required)
 *   minio — saves files to a MinIO/S3-compatible object store
 *
 * Configure via:
 *   storage.type=local|minio   (default: local)
 *   storage.upload-dir=./uploads  (local mode only)
 */
@Service
@Slf4j
public class FileStorageService {

    private static final String LOCAL = "local";

    @Value("${storage.type:local}")
    private String storageType;

    @Value("${storage.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${minio.bucket-name:aidevplatform}")
    private String bucketName;

    private final MinioClient minioClient;

    public FileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Store requirement text content for a module.
     * Returns the storage path (local path or MinIO object name).
     */
    public String storeRequirementText(UUID moduleId, String content) {
        String relativePath = "requirements/" + moduleId + "/requirement.txt";
        if (LOCAL.equalsIgnoreCase(storageType)) {
            return storeLocally(relativePath, content);
        } else {
            return storeToMinio(relativePath, content);
        }
    }

    /**
     * Store an uploaded file for a module.
     * Returns the storage path.
     */
    public String storeRequirementFile(UUID moduleId, String fileName, String content) {
        String relativePath = "requirements/" + moduleId + "/" + sanitizeFileName(fileName);
        if (LOCAL.equalsIgnoreCase(storageType)) {
            return storeLocally(relativePath, content);
        } else {
            return storeToMinio(relativePath, content);
        }
    }

    /**
     * Uploads a raw stream to storage and returns its path.
     */
    public String uploadFile(String objectName, InputStream inputStream, String contentType, long size) {
        if (LOCAL.equalsIgnoreCase(storageType)) {
            try {
                byte[] bytes = inputStream.readAllBytes();
                return storeLocally(objectName, new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read upload stream: " + e.getMessage(), e);
            }
        } else {
            return uploadToMinio(objectName, inputStream, contentType, size);
        }
    }

    /**
     * Downloads file content as a string.
     */
    public String downloadAsString(String path) {
        if (LOCAL.equalsIgnoreCase(storageType)) {
            return readLocally(path);
        } else {
            return downloadFromMinio(path);
        }
    }

    // -------------------------------------------------------------------------
    // Local filesystem storage
    // -------------------------------------------------------------------------

    private String storeLocally(String relativePath, String content) {
        Path target = resolveLocalPath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("File stored locally: {}", target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to store file locally: {}", target, e);
            throw new RuntimeException("Local file storage failed: " + e.getMessage(), e);
        }
    }

    private String readLocally(String path) {
        try {
            Path target = Paths.get(path).isAbsolute() ? Paths.get(path) : resolveLocalPath(path);
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read local file: {}", path, e);
            throw new RuntimeException("Local file read failed: " + e.getMessage(), e);
        }
    }

    private Path resolveLocalPath(String relativePath) {
        return Paths.get(uploadDir).resolve(relativePath).normalize();
    }

    // -------------------------------------------------------------------------
    // MinIO storage
    // -------------------------------------------------------------------------

    private String storeToMinio(String objectName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            return uploadToMinio(objectName, bais, "text/plain", bytes.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare MinIO upload: " + e.getMessage(), e);
        }
    }

    private String uploadToMinio(String objectName, InputStream inputStream, String contentType, long size) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("File uploaded to MinIO: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload file to MinIO: {}", objectName, e);
            throw new RuntimeException("MinIO upload failed: " + e.getMessage(), e);
        }
    }

    private String downloadFromMinio(String objectName) {
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to download file from MinIO: {}", objectName, e);
            throw new RuntimeException("MinIO download failed: " + e.getMessage(), e);
        }
    }

    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created MinIO bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket exists: {}", bucketName, e);
            throw new RuntimeException("MinIO bucket operation failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
