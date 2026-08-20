package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
}
