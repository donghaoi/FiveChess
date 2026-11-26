package com.whc.fivechess.component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whc.fivechess.domain.ChatPublicRecord;
import com.whc.fivechess.domain.ChatRoomRecord;
import com.whc.fivechess.mapper.ChatPublicRecordMapper;
import com.whc.fivechess.mapper.ChatRoomRecordMapper;
import com.whc.fivechess.util.MyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聊天记录留存：公共聊天与房间聊天分别写入两张表，供事后追溯与管理员查看。
 * 每条记录保存：谁（用户id+昵称）、什么时间、在哪个房间（房间聊天）、说了什么、以及发言时的实时 IP。
 * 注意 content 保存的是用户发送的原始内容（未过滤敏感词），而聊天窗口展示的仍是过滤后的内容。
 * 保留期可配置（fivechess.chat.retention-days，默认 30 天），过期记录由后台任务定期清理。
 */
@Component
public class ChatRecorder {

    @Autowired
    private ChatPublicRecordMapper chatPublicRecordMapper;
    @Autowired
    private ChatRoomRecordMapper chatRoomRecordMapper;

    /** 保留天数，超过则定期删除 */
    private volatile int retentionDays = 30;
    /** 单次查询返回的最大条数 */
    private static final int MAX_QUERY_LIMIT = 500;

    private ScheduledExecutorService cleaner;

    @Value("${fivechess.chat.retention-days:30}")
    public void setRetentionDaysValue(int days) {
        if (days > 0) {
            retentionDays = days;
        }
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    @PostConstruct
    public void startCleaner() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fc-chat-retention");
            thread.setDaemon(true);
            return thread;
        });
        //每小时执行一次过期清理
        cleaner.scheduleWithFixedDelay(this::cleanupExpired, 1, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stopCleaner() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
    }

    /**
     * 记录一条公共聊天消息（存原始内容）。
     */
    public void recordPublic(Integer player, String nick, String content, String ip) {
        if (chatPublicRecordMapper == null || content == null) {
            return;
        }
        try {
            ChatPublicRecord record = new ChatPublicRecord();
            record.setPlayer(player);
            record.setNick(nick);
            record.setContent(content);
            record.setIp(ip);
            record.setCreatedAt(MyUtil.now());
            chatPublicRecordMapper.insert(record);
        } catch (Exception e) {
            System.out.println("写入公共聊天记录失败：" + e.getMessage());
        }
    }

    /**
     * 记录一条房间聊天消息（存原始内容）。
     */
    public void recordRoom(Integer player, String nick, String roomId, String content, String ip) {
        if (chatRoomRecordMapper == null || content == null) {
            return;
        }
        try {
            ChatRoomRecord record = new ChatRoomRecord();
            record.setPlayer(player);
            record.setNick(nick);
            record.setRoomId(roomId);
            record.setContent(content);
            record.setIp(ip);
            record.setCreatedAt(MyUtil.now());
            chatRoomRecordMapper.insert(record);
        } catch (Exception e) {
            System.out.println("写入房间聊天记录失败：" + e.getMessage());
        }
    }

    /** 查询某个房间的最近聊天记录（管理员查看用） */
    public List<ChatRoomRecord> listByRoom(String roomId, Integer limit) {
        if (chatRoomRecordMapper == null || roomId == null) {
            return Collections.emptyList();
        }
        QueryWrapper<ChatRoomRecord> query = new QueryWrapper<>();
        query.eq("room_id", roomId).orderByDesc("id");
        query.last("limit " + normalizeLimit(limit));
        return chatRoomRecordMapper.selectList(query);
    }

    /** 查询最近的公共聊天记录（管理员查看用） */
    public List<ChatPublicRecord> listPublic(Integer limit) {
        if (chatPublicRecordMapper == null) {
            return Collections.emptyList();
        }
        QueryWrapper<ChatPublicRecord> query = new QueryWrapper<>();
        query.orderByDesc("id");
        query.last("limit " + normalizeLimit(limit));
        return chatPublicRecordMapper.selectList(query);
    }

    /** 立即清理两张表中的过期记录（供定时任务与测试调用），返回删除条数 */
    public int cleanupExpired() {
        long cutoffMillis = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
        String cutoff = MyUtil.format(new Date(cutoffMillis));
        int deleted = 0;
        try {
            if (chatPublicRecordMapper != null) {
                deleted += chatPublicRecordMapper.delete(new QueryWrapper<ChatPublicRecord>().lt("created_at", cutoff));
            }
        } catch (Exception e) {
            System.out.println("清理过期公共聊天记录失败：" + e.getMessage());
        }
        try {
            if (chatRoomRecordMapper != null) {
                deleted += chatRoomRecordMapper.delete(new QueryWrapper<ChatRoomRecord>().lt("created_at", cutoff));
            }
        } catch (Exception e) {
            System.out.println("清理过期房间聊天记录失败：" + e.getMessage());
        }
        return deleted;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, MAX_QUERY_LIMIT);
    }
}