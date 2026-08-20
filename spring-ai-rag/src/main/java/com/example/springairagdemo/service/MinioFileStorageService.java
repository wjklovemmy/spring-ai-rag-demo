package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * MinIO 对象存储实现
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.storage", name = "type", havingValue = "minio")
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final RagConfigProperties ragConfig;

    public MinioFileStorageService(RagConfigProperties ragConfig) {
        this.ragConfig = ragConfig;
        var cfg = ragConfig.getStorage().getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
        log.info("MinIO 客户端已初始化: endpoint={}, bucket={}", cfg.getEndpoint(), cfg.getBucket());
    }

    /**
     * 初始化 Bucket：不存在则自动创建
     */
    @PostConstruct
    public void initBucket() {
        try {
            String bucket = ragConfig.getStorage().getMinio().getBucket();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                log.info("MinIO Bucket 已存在: {}", bucket);
            } else {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO Bucket 已自动创建: {}", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO Bucket 初始化失败", e);
            throw new RuntimeException("MinIO Bucket 初始化失败，请检查 MinIO 服务是否正常", e);
        }
    }

    @Override
    public void store(InputStream inputStream, String objectName, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(ragConfig.getStorage().getMinio().getBucket())
                .object(objectName)
                .stream(inputStream, -1, 50 * 1024 * 1024)  // 50MB part size
                .contentType(contentType)
                .build());
        log.info("文件已上传至 MinIO: bucket={}, object={}", ragConfig.getStorage().getMinio().getBucket(), objectName);
    }

    @Override
    public InputStream getInputStream(String objectName) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(ragConfig.getStorage().getMinio().getBucket())
                .object(objectName)
                .build());
    }

    @Override
    public boolean exists(String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(ragConfig.getStorage().getMinio().getBucket())
                    .object(objectName)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(ragConfig.getStorage().getMinio().getBucket())
                .object(objectName)
                .build());
        log.info("文件已从 MinIO 删除: bucket={}, object={}", ragConfig.getStorage().getMinio().getBucket(), objectName);
    }
}
