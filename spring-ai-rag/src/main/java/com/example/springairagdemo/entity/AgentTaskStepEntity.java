package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 任务步骤轨迹实体：模型调用工具的每一步。
 * 按 task_id + id 顺序还原推理轨迹（running → done / error 成对出现）。
 */
@Data
@TableName("agent_task_step")
public class AgentTaskStepEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务 ID（agent_task.id） */
    @TableField("task_id")
    private Long taskId;

    /** 步骤类型：TOOL_CALL */
    @TableField("type")
    private String type;

    /** 工具名 */
    @TableField("tool_name")
    private String toolName;

    /** 状态：running / done / error */
    @TableField("status")
    private String status;

    /** 工具入参摘要 */
    @TableField("args")
    private String args;

    /** 工具返回结果 */
    @TableField("result")
    private String result;

    /** 该步耗时（毫秒，done 时回填：本次 done 距同工具 running 的时间差） */
    @TableField("latency_ms")
    private Long latencyMs;

    /** 发生时间 */
    @TableField("create_time")
    private Date createTime;
}
