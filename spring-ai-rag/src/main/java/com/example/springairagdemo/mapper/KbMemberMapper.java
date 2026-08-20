package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.KbMemberEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbMemberMapper extends BaseMapper<KbMemberEntity> {
}
