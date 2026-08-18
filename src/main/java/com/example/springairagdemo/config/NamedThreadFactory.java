package com.example.springairagdemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义线程工厂：给线程池线程命名（前缀 + 自增序号），
 * 并注册 UncaughtExceptionHandler，让线程内未捕获异常打印日志，便于问题定位。
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final ThreadFactory defaultFactory = java.util.concurrent.Executors.defaultThreadFactory();
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;

    public NamedThreadFactory(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = defaultFactory.newThread(r);
        thread.setName(namePrefix + threadNumber.getAndIncrement());
        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("线程 [{}] 抛出未捕获异常", t.getName(), e));
        return thread;
    }
}
