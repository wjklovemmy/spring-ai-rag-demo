package com.example.springairagdemo.service;

import com.example.springairagdemo.config.RagConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘文件存储实现（默认）
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final RagConfigProperties ragConfig;

    public LocalFileStorageService(RagConfigProperties ragConfig) {
        this.ragConfig = ragConfig;
    }

    @Override
    public void store(InputStream inputStream, String objectName, String contentType) throws Exception {
        Path targetPath = Paths.get(ragConfig.getDocument().getUploadDir(), objectName);
        Files.createDirectories(targetPath.getParent());
        try (OutputStream os = Files.newOutputStream(targetPath)) {
            inputStream.transferTo(os);
        }
        log.info("文件已写入本地磁盘: {}", targetPath);
    }

    @Override
    public InputStream getInputStream(String objectName) throws Exception {
        Path path = Paths.get(ragConfig.getDocument().getUploadDir(), objectName);
        return Files.newInputStream(path);
    }

    @Override
    public boolean exists(String objectName) {
        Path path = Paths.get(ragConfig.getDocument().getUploadDir(), objectName);
        return Files.exists(path);
    }

    @Override
    public void delete(String objectName) throws Exception {
        Path path = Paths.get(ragConfig.getDocument().getUploadDir(), objectName);
        Files.deleteIfExists(path);
        log.info("文件已从本地磁盘删除: {}", path);
    }
}
