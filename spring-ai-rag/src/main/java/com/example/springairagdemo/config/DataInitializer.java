package com.example.springairagdemo.config;

import com.example.springairagdemo.service.TaskRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * RAG 业务域启动初始化：
 * 冷启动兜底——服务启动时立即执行一次中断 Embedding 任务恢复
 * （逻辑见 {@link TaskRecoveryService#recoverInterruptedTasks()}，
 * 该服务同时供定时巡检等其它入口复用，多入口并发安全由 CAS 抢占保证）。
 * （内置 ADMIN 角色与引导管理员账号的初始化已随用户域迁移至
 * spring-ai-user 模块的 {@code UserDataInitializer}，由宿主应用统一扫描执行。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final TaskRecoveryService taskRecoveryService;

    @Override
    public void run(ApplicationArguments args) {
        taskRecoveryService.recoverInterruptedTasks();
    }
}
