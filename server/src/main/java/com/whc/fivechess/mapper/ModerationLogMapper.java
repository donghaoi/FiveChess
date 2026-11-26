package com.whc.fivechess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.whc.fivechess.domain.ModerationLog;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface ModerationLogMapper extends BaseMapper<ModerationLog> {
}