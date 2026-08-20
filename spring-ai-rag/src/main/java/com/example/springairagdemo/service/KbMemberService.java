package com.example.springairagdemo.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springairagdemo.entity.KbMemberEntity;
import com.example.springairagdemo.mapper.KbMemberMapper;
import org.springframework.stereotype.Service;

@Service
public class KbMemberService extends ServiceImpl<KbMemberMapper, KbMemberEntity> {
}
