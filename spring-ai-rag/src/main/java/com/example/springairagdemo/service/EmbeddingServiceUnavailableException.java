package com.example.springairagdemo.service;

/**
 * 向量化服务（DashScope Embedding）不可用异常：
 * 由 {@link VectorStoreService} 在 Embedding 调用熔断/失败时抛出，
 * 上传任务的错误信息会归一化为「向量化服务暂时不可用，请稍后重试」。
 */
public class EmbeddingServiceUnavailableException extends RuntimeException {

    public EmbeddingServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
