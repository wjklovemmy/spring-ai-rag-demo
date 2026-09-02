package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.ChatSessionMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话长期记忆 Mapper
 */
@Mapper
public interface ChatSessionMemoryMapper extends BaseMapper<ChatSessionMemoryEntity> {
}
