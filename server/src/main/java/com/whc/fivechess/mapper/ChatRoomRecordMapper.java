package com.whc.fivechess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.whc.fivechess.domain.ChatRoomRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface ChatRoomRecordMapper extends BaseMapper<ChatRoomRecord> {
}