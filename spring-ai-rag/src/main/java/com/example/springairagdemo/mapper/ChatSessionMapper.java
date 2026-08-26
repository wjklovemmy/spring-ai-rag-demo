package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天会话 Mapper
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {
}
