package com.example.springairagdemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KnowledgeDocumentEntity;
import com.example.springairagdemo.mapper.KnowledgeDocumentMapper;
import com.example.springairagdemo.service.KnowledgeDocumentEntityService;
import org.springframework.stereotype.Service;

/**
 * 知识文档实体 Service 实现
 */
@Service
public class KnowledgeDocumentEntityServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocumentEntity>
        implements KnowledgeDocumentEntityService {
}
