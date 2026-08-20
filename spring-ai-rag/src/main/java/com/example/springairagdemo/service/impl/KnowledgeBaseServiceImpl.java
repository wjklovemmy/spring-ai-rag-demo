package com.example.springairagdemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KnowledgeBaseEntity;
import com.example.springairagdemo.mapper.KnowledgeBaseMapper;
import com.example.springairagdemo.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

/**
 * 知识库 Service 实现
 */
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBaseEntity>
        implements KnowledgeBaseService {
}
