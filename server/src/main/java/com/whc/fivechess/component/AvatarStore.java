package com.whc.fivechess.component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whc.fivechess.domain.UserAvatar;
import com.whc.fivechess.mapper.UserAvatarMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 头像二进制存储：读写 user_avatar 表。
 *
 * 设计要点：
 * 1. 只存数据库，不碰磁盘 —— 前后端分离部署时后端写文件既到不了前端静态目录，容器重启还会丢；
 * 2. 读多写少：热路径（登录、房间卡片）只查 version 这一列，不把 3MB 图片读进内存；
 * 3. 表缺失时降级而不是抛错 —— 正式库漏建表时光标不该影响登录下棋，只在日志里提示一次。
 */
@Component
public class AvatarStore {

    @Autowired
    private UserAvatarMapper avatarMapper;

    /**
     * 表不可用（未建表/无权限/瞬时故障）时进入熔断，避免每条请求都去撞数据库。
     * 只熔断一段时间而不是永久置位：数据库抖动一次就把头像功能关到进程重启，
     * 会让「头像传不上去」变成没有明显征兆的长期故障。
     */
    private static final long DEGRADE_MS = 60 * 1000L;
    private volatile long degradeUntil = 0L;

    /**
     * 查询头像版本号。
     *
     * @return 版本号（上传时间毫秒）；null 表示该用户没有上传过头像，或存储暂不可用
     */
    public Long versionOf(Integer userId){
        if(userId == null || degraded()){
            return null;
        }
        try{
            QueryWrapper<UserAvatar> query = new QueryWrapper<>();
            query.select("user_id", "version").eq("user_id", userId);
            UserAvatar row = avatarMapper.selectOne(query);
            return row == null ? null : row.getVersion();
        }catch(Exception e){
            markUnavailable(e);
            return null;
        }
    }

    /** 读取完整图片，仅 {@code GET /avatar/{id}} 使用 */
    public UserAvatar find(Integer userId){
        if(userId == null || degraded()){
            return null;
        }
        try{
            return avatarMapper.selectById(userId);
        }catch(Exception e){
            markUnavailable(e);
            return null;
        }
    }

    /**
     * 保存（覆盖）用户头像。并发下先更新、未命中再插入，插入撞主键时回退为更新。
     *
     * @return 实际保存的版本号
     */
    public long save(Integer userId, byte[] content, String contentType){
        long version = System.currentTimeMillis();
        UserAvatar row = new UserAvatar();
        row.setUserId(userId);
        row.setContent(content);
        row.setContentType(contentType);
        row.setVersion(version);
        row.setUpdatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        try{
            if(avatarMapper.updateById(row) == 0){
                try{
                    avatarMapper.insert(row);
                }catch(DuplicateKeyException race){
                    //并发上传同一账号：另一个请求刚插入，这里改成更新，保证最终只有一条记录
                    avatarMapper.updateById(row);
                }
            }
            return version;
        }catch(Exception e){
            markUnavailable(e);
            throw new IllegalStateException("头像保存失败：" + e.getMessage(), e);
        }
    }

    /** 删除用户头像（注销账号、测试清理用） */
    public void delete(Integer userId){
        if(userId == null || degraded()){
            return;
        }
        try{
            avatarMapper.deleteById(userId);
        }catch(Exception e){
            markUnavailable(e);
        }
    }

    /** 存储是否可用，供启动自检与测试使用 */
    public boolean available(){
        return !degraded();
    }

    /** 是否处于熔断期 */
    private boolean degraded(){
        return System.currentTimeMillis() < degradeUntil;
    }

    private void markUnavailable(Exception e){
        long now = System.currentTimeMillis();
        if(now >= degradeUntil){
            //下次熔断到期后允许重试；恢复成功则不再续期
            degradeUntil = now + DEGRADE_MS;
            System.out.println("[头像] user_avatar 表不可用（" + e.getClass().getSimpleName()
                    + "：" + e.getMessage() + "），头像上传功能已降级；"
                    + "请执行 数据库/chess.sql 中 user_avatar 建表语句后重启服务");
        }
    }
}
