package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.KbAccessLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbAccessLogMapper extends BaseMapper<KbAccessLogEntity> {
}
