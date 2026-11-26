package com.whc.fivechess.component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.whc.fivechess.domain.ModerationLog;
import com.whc.fivechess.domain.User;
import com.whc.fivechess.mapper.ModerationLogMapper;
import com.whc.fivechess.mapper.UserMapper;
import com.whc.fivechess.security.ClientIpResolver;
import com.whc.fivechess.util.MyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 管理员与账号处置：
 * 管理员识别（按配置的管理员账号名）、禁言/封号状态读写、封号 IP 绑定查询，以及管理员操作记录写入。
 */
@Component
public class ModerationService {

    /** 被封号后在注册/登录时统一的提示文案 */
    public static final String BAN_MESSAGE =
            "您已被管理员封号，如有任何疑问请移步 https://blog.donghao.ltd/ 联系管理员";

    private static volatile ModerationService instance;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ModerationLogMapper moderationLogMapper;

    /** 管理员账号名，可通过 fivechess.admin.login 配置 */
    private volatile String adminLogin = "admin";

    @Value("${fivechess.admin.login:admin}")
    public void setAdminLoginValue(String login) {
        if (login != null && !login.trim().isEmpty()) {
            adminLogin = login.trim();
        }
    }

    public String getAdminLogin() {
        return adminLogin;
    }

    /** 是否为管理员账号名（大小写不敏感） */
    public boolean isAdminLogin(String login) {
        return login != null && login.equalsIgnoreCase(adminLogin);
    }

    /** 该用户 id 是否为管理员 */
    public boolean isAdminUserId(String userId) {
        User user = findById(userId);
        return user != null && isAdminLogin(user.getLogin());
    }

    // ===== 查询 =====

    public User findById(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        try {
            return userMapper.selectById(Integer.valueOf(userId));
        } catch (Exception e) {
            return null;
        }
    }

    public User findById(Integer userId) {
        if (userId == null) {
            return null;
        }
        try {
            return userMapper.selectById(userId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按账号查用户（历史脏数据时取第一条） */
    public User findByLogin(String login) {
        if (login == null || login.isEmpty()) {
            return null;
        }
        List<User> list = userMapper.selectList(new QueryWrapper<User>().eq("login", login));
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    public boolean isMuted(Integer userId) {
        User user = findById(userId);
        return user != null && Boolean.TRUE.equals(user.getMuted());
    }

    public boolean isBanned(Integer userId) {
        User user = findById(userId);
        return user != null && Boolean.TRUE.equals(user.getBanned());
    }

    /**
     * 该 IP 是否属于任一被封号账号（注册前的拦截点）。
     *
     * <p>判定依据是账号的 {@code register_ip}（注册出口）与 {@code last_ip}（最近一次登录成功出口），
     * 任一命中都算封禁。两个字段覆盖「在家注册、在公司登录」这类多出口用户：只封注册 IP 时
     * 换个地方即可继续注册，只封登录 IP 时「注册后从未登录」的老账号完全封不住。</p>
     *
     * <p>不另设 {@code ban_ip} / {@code ban_ip_last} 快照列：它们的值必然等于封号当时的这两个字段，
     * 多存一份只会带来「解封必须两边都清」的隐患。</p>
     *
     * <p>不能直接写 {@code WHERE register_ip = ?}：同一个来源可能有两种写法（IPv6 大小写/压缩形式、
     * 双栈下的 ::ffff:1.2.3.4），且 IPv6 同一线路会在 /64 内轮换隐私地址。这里统一取归一化比对键
     * （IPv4 精确、IPv6 按前缀）后比较，避免「换个写法/换条隐私地址就绕过封禁」。</p>
     *
     * <p>不设 {@code limit}：原来上限 1000 会让「第 1001 个被封账号」静默失效。封号是低频人工操作，
     * 且此处只取三个窄字段，全量比对的成本可以忽略。</p>
     *
     * @param ip 待检查的来源 IP（来自请求，可能为 "unknown"）
     * @return 该来源是否已被封禁；IP 非法或未知时返回 false（未知值不能当作封禁依据）
     */
    public boolean isBannedIp(String ip) {
        String key = ClientIpResolver.matchKey(ip);
        if (key == null) {
            return false;
        }
        QueryWrapper<User> query = new QueryWrapper<>();
        query.select("id", "register_ip", "last_ip").eq("banned", 1);
        List<User> banned = userMapper.selectList(query);
        if (banned == null || banned.isEmpty()) {
            return false;
        }
        for (User user : banned) {
            if (key.equals(ClientIpResolver.matchKey(user.getRegisterIp()))
                    || key.equals(ClientIpResolver.matchKey(user.getLastIp()))) {
                return true;
            }
        }
        return false;
    }

    // ===== 状态更新 =====

    /** 更新禁言状态，返回是否成功 */
    public boolean updateMuted(Integer userId, boolean muted) {
        if (userId == null) {
            return false;
        }
        try {
            User update = new User();
            update.setId(userId);
            update.setMuted(muted);
            return userMapper.updateById(update) > 0;
        } catch (Exception e) {
            System.out.println("更新禁言状态失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 封号：写 {@code banned=1} 与封号时间。
     *
     * <p>不再复制 IP：封禁判定直接读账号自己的 {@code register_ip} 与 {@code last_ip}，
     * 被封号后无法登录，这两个字段天然冻结，值与「封号那一刻的快照」完全一致。</p>
     *
     * @return 是否更新成功
     */
    public boolean updateBanned(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            UpdateWrapper<User> update = new UpdateWrapper<>();
            update.eq("id", userId)
                    .set("banned", 1)
                    .set("ban_time", MyUtil.now());
            return userMapper.update(null, update) > 0;
        } catch (Exception e) {
            System.out.println("更新封号状态失败：" + e.getMessage());
            return false;
        }
    }

    /** 解除封号：只需清标记与封号时间，来源 IP 作为账号历史保留 */
    public boolean updateUnbanned(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            UpdateWrapper<User> update = new UpdateWrapper<>();
            update.eq("id", userId).set("banned", 0).set("ban_time", null);
            return userMapper.update(null, update) > 0;
        } catch (Exception e) {
            System.out.println("解除封号失败：" + e.getMessage());
            return false;
        }
    }

    /** 当前被封号的账号列表（供管理员解封） */
    public List<User> listBanned() {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("banned", 1).orderByDesc("id").last("limit 200");
        return userMapper.selectList(query);
    }

    /** 写入一条管理员操作记录 */
    public void logAction(String action, Integer adminId, String adminLogin,
                          Integer targetId, String targetLogin, String detail) {
        if (moderationLogMapper == null) {
            return;
        }
        try {
            ModerationLog log = new ModerationLog();
            log.setAdminId(adminId);
            log.setAdminLogin(adminLogin);
            log.setTargetId(targetId);
            log.setTargetLogin(targetLogin);
            log.setAction(action);
            log.setDetail(detail);
            log.setCreatedAt(MyUtil.now());
            moderationLogMapper.insert(log);
        } catch (Exception e) {
            System.out.println("写入管理员操作记录失败：" + e.getMessage());
        }
    }

    /** 查询管理员操作记录（最近 N 条） */
    public List<ModerationLog> recentLogs(Integer limit) {
        if (moderationLogMapper == null) {
            return Collections.emptyList();
        }
        int size = (limit == null || limit <= 0) ? 100 : Math.min(limit, 500);
        QueryWrapper<ModerationLog> query = new QueryWrapper<>();
        query.orderByDesc("id").last("limit " + size);
        return moderationLogMapper.selectList(query);
    }
}