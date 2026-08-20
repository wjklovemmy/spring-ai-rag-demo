package com.example.springairagdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springairagdemo.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
