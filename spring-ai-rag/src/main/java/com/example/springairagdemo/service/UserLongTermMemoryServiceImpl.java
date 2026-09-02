package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import com.example.springairagdemo.mapper.UserLongTermMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆 MySQL 层实现（Phase 2）
 */
@Service
@Slf4j
public class UserLongTermMemoryServiceImpl extends ServiceImpl<UserLongTermMemoryMapper, UserLongTermMemoryEntity>
        implements UserLongTermMemoryService {

    @Override
    public long countByUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        return lambdaQuery().eq(UserLongTermMemoryEntity::getUserId, userId).count();
    }

    @Override
    public List<UserLongTermMemoryEntity> listByUser(Long userId, String category, int limit) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserLongTermMemoryEntity> wrapper = new LambdaQueryWrapper<UserLongTermMemoryEntity>()
                .eq(UserLongTermMemoryEntity::getUserId, userId);
        if (category != null && !category.isBlank()) {
            wrapper.eq(UserLongTermMemoryEntity::getCategory, category);
        }
        wrapper.orderByDesc(UserLongTermMemoryEntity::getUpdateTime);
        if (limit > 0) {
            wrapper.last("limit " + limit);
        }
        return list(wrapper);
    }

    @Override
    public boolean deleteOwn(Long userId, Long memoryId) {
        if (userId == null || memoryId == null) {
            return false;
        }
        boolean updated = update(new LambdaUpdateWrapper<UserLongTermMemoryEntity>()
                .eq(UserLongTermMemoryEntity::getId, memoryId)
                .eq(UserLongTermMemoryEntity::getUserId, userId)
                .set(UserLongTermMemoryEntity::getDeleted, 1)
                .set(UserLongTermMemoryEntity::getDeletedBy, userId)
                .set(UserLongTermMemoryEntity::getDeleteTime, new Date()));
        if (updated) {
            log.info("用户长期记忆已逻辑删除: memoryId={}, userId={}", memoryId, userId);
        }
        return updated;
    }

    @Override
    public int deleteAllByUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        int rows = getBaseMapper().update(null, new LambdaUpdateWrapper<UserLongTermMemoryEntity>()
                .eq(UserLongTermMemoryEntity::getUserId, userId)
                .eq(UserLongTermMemoryEntity::getDeleted, 0)
                .set(UserLongTermMemoryEntity::getDeleted, 1)
                .set(UserLongTermMemoryEntity::getDeletedBy, userId)
                .set(UserLongTermMemoryEntity::getDeleteTime, new Date()));
        if (rows > 0) {
            log.info("用户长期记忆已全部逻辑删除: userId={}, rows={}", userId, rows);
        }
        return rows;
    }

    @Override
    public List<UserLongTermMemoryEntity> listPendingVectorSync(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // deleted 由 @TableLogic 自动附加 deleted=0，不会捞到逻辑删除的记录
        return lambdaQuery()
                .eq(UserLongTermMemoryEntity::getVectorStatus, 0)
                .orderByAsc(UserLongTermMemoryEntity::getUpdateTime)
                .orderByAsc(UserLongTermMemoryEntity::getId)
                .last("LIMIT " + Math.min(limit, 200))
                .list();
    }

    @Override
    public Map<String, Object> adminStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", lambdaQuery().count());
        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        stats.put("todayNew", lambdaQuery().ge(UserLongTermMemoryEntity::getCreateTime, todayStart).count());
        Date weekStart = new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        stats.put("weekNew", lambdaQuery().ge(UserLongTermMemoryEntity::getCreateTime, weekStart).count());
        stats.put("pendingVector", lambdaQuery().eq(UserLongTermMemoryEntity::getVectorStatus, 0).count());
        // 有记忆的用户数（逻辑删除记录自动排除）
        stats.put("usersWithMemory", getBaseMapper().selectMaps(new QueryWrapper<UserLongTermMemoryEntity>()
                .select("user_id")
                .groupBy("user_id")).size());
        // 类别分布：固定输出 5 类（缺省 0），附加其他值，便于前端直接渲染
        Map<String, Long> categoryDist = new LinkedHashMap<>();
        for (String c : List.of("fact", "preference", "interest", "goal", "event")) {
            categoryDist.put(c, 0L);
        }
        for (Map<String, Object> row : getBaseMapper().selectMaps(new QueryWrapper<UserLongTermMemoryEntity>()
                .select("category", "COUNT(*) AS cnt")
                .groupBy("category"))) {
            Object key = row.get("category");
            Object cnt = row.get("cnt");
            String cat = key == null ? "other" : String.valueOf(key);
            categoryDist.put(cat, cnt instanceof Number n ? n.longValue() : 0L);
        }
        stats.put("categoryDist", categoryDist);
        return stats;
    }

    private LambdaQueryWrapper<UserLongTermMemoryEntity> adminCondition(Long userId, String category, String keyword) {
        LambdaQueryWrapper<UserLongTermMemoryEntity> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(UserLongTermMemoryEntity::getUserId, userId);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(UserLongTermMemoryEntity::getCategory, category);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(UserLongTermMemoryEntity::getContent, keyword);
        }
        return wrapper;
    }

    @Override
    public long adminCount(Long userId, String category, String keyword) {
        return count(adminCondition(userId, category, keyword));
    }

    @Override
    public List<UserLongTermMemoryEntity> adminList(Long userId, String category, String keyword,
                                                    int offset, int size) {
        int safeOffset = Math.max(0, offset);
        int safeSize = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<UserLongTermMemoryEntity> wrapper = adminCondition(userId, category, keyword)
                .orderByDesc(UserLongTermMemoryEntity::getCreateTime)
                .orderByDesc(UserLongTermMemoryEntity::getId)
                .last("LIMIT " + safeOffset + "," + safeSize);
        return list(wrapper);
    }
}
