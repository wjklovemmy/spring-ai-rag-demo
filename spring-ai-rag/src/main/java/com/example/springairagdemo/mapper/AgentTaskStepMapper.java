package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.AgentTaskStepEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 任务步骤轨迹 Mapper
 */
@Mapper
public interface AgentTaskStepMapper extends BaseMapper<AgentTaskStepEntity> {
}
