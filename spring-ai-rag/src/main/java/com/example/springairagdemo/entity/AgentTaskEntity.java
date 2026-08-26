package com.example.springairagdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 任务实体：一次提问的执行审计单元。
 * 记录问题/最终回答/状态/耗时/工具调用次数，支撑 Agent 执行审计、失败排查、耗时统计。
 */
@Data
@TableName("agent_task")
public class AgentTaskEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 会话 ID（chat_session.session_id） */
    @TableField("session_id")
    private String sessionId;

    /** 知识库 ID */
    @TableField("kb_id")
    private Long kbId;

    /** 用户问题 */
    @TableField("question")
    private String question;

    /** 最终回答（引用对齐后全文） */
    @TableField("answer")
    private String answer;

    /** 引用来源快照（JSON 数组字符串） */
    @TableField("sources")
    private String sources;

    /** LLM 实际输入（系统提示+问题，可观测性审计） */
    @TableField("prompt")
    private String prompt;

    /** LLM 模型名 */
    @TableField("model")
    private String model;

    /** 输入 token 数 */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** 输出 token 数 */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** 总 token 数 */
    @TableField("total_tokens")
    private Integer totalTokens;

    /** 状态：0 执行中 / 1 成功 / 2 失败 */
    @TableField("status")
    private Integer status;

    /** 工具调用步骤数 */
    @TableField("tool_count")
    private Integer toolCount;

    /** 总耗时（毫秒） */
    @TableField("cost_ms")
    private Long costMs;

    /** 失败原因 */
    @TableField("error_msg")
    private String errorMsg;

    /** 开始时间戳（毫秒，用于精确耗时统计） */
    @TableField("start_ms")
    private Long startMs;

    /** 开始时间 */
    @TableField("create_time")
    private Date createTime;

    /** 结束时间 */
    @TableField("finish_time")
    private Date finishTime;
}
