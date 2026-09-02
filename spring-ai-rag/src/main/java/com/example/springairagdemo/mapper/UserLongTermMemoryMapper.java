package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.UserLongTermMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户长期记忆 Mapper（Phase 2）
 */
@Mapper
public interface UserLongTermMemoryMapper extends BaseMapper<UserLongTermMemoryEntity> {
}
