package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KbAccessLogEntity;
import com.example.springairagdemo.mapper.KbAccessLogMapper;
import org.springframework.stereotype.Service;

@Service
public class KbAccessLogService extends ServiceImpl<KbAccessLogMapper, KbAccessLogEntity> {
}
