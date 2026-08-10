package com.example.springairagdemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KnowledgeChunkEntity;
import com.example.springairagdemo.mapper.KnowledgeChunkMapper;
import com.example.springairagdemo.service.KnowledgeChunkEntityService;
import org.springframework.stereotype.Service;

/**
 * 知识 Chunk 实体 Service 实现
 */
@Service
public class KnowledgeChunkEntityServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunkEntity>
        implements KnowledgeChunkEntityService {
}
