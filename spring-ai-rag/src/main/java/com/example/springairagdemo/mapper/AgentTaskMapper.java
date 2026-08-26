package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.AgentTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 任务 Mapper
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskEntity> {
}
