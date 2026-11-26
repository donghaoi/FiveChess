package com.whc.fivechess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.whc.fivechess.domain.UserAvatar;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface UserAvatarMapper extends BaseMapper<UserAvatar> {
}
