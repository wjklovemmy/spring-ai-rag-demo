package com.example.springairagdemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KnowledgeEmbeddingTaskEntity;
import com.example.springairagdemo.mapper.KnowledgeEmbeddingTaskMapper;
import com.example.springairagdemo.service.KnowledgeEmbeddingTaskService;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingTaskServiceImpl
        extends ServiceImpl<KnowledgeEmbeddingTaskMapper, KnowledgeEmbeddingTaskEntity>
        implements KnowledgeEmbeddingTaskService {
}
