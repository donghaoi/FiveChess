package com.whc.fivechess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.whc.fivechess.domain.ChatPublicRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface ChatPublicRecordMapper extends BaseMapper<ChatPublicRecord> {
}