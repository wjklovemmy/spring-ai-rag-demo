package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.springairagdemo.entity.UserLongTermMemoryEntity;

import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆 MySQL 层 Service（Phase 2）：文本数据源的增删查。
 *
 * <p>语义召回（向量）、去重落库、注入文本组装等组合能力见 {@link MemoryService}；
 * 本接口只负责本表的持久化与按用户查询。
 */
public interface UserLongTermMemoryService extends IService<UserLongTermMemoryEntity> {

    /** 某用户长期记忆总数（不含逻辑删除） */
    long countByUser(Long userId);

    /** 某用户长期记忆列表（按更新时间倒序），category 非空时过滤；limit ≤ 0 不限制条数 */
    List<UserLongTermMemoryEntity> listByUser(Long userId, String category, int limit);

    /** 逻辑删除某条长期记忆（仅限本人），返回是否删除成功 */
    boolean deleteOwn(Long userId, Long memoryId);

    /** 逻辑删除某用户全部长期记忆（一键清除用），返回被清除条数 */
    int deleteAllByUser(Long userId);

    /** 取待向量补偿的记录（vector_status=0，不含逻辑删除），按更新时间升序，最多 limit 条 */
    List<UserLongTermMemoryEntity> listPendingVectorSync(int limit);

    /** 管理员维度统计（不分用户）：总数 / 今日新增 / 近 7 日新增 / 待向量条数 / 有记忆用户数 / 类别分布 */
    Map<String, Object> adminStats();

    /** 管理员分页查询记忆明细总条数（userId/category/keyword 条件可选） */
    long adminCount(Long userId, String category, String keyword);

    /** 管理员分页查询记忆明细（创建时间倒序），offset 从 0 开始，size ≤ 100 */
    List<UserLongTermMemoryEntity> adminList(Long userId, String category, String keyword, int offset, int size);
}
