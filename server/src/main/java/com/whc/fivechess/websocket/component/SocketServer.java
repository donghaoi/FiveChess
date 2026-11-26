package com.whc.fivechess.websocket.component;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.whc.fivechess.component.AvatarService;
import com.whc.fivechess.component.ChatRecorder;
import com.whc.fivechess.component.ModerationService;
import com.whc.fivechess.component.TokenStore;
import com.whc.fivechess.domain.ChatPublicRecord;
import com.whc.fivechess.domain.ChatRoomRecord;
import com.whc.fivechess.domain.Game;
import com.whc.fivechess.domain.Record;
import com.whc.fivechess.domain.Step;
import com.whc.fivechess.domain.Steps;
import com.whc.fivechess.domain.User;
import com.whc.fivechess.mapper.GameMapper;
import com.whc.fivechess.mapper.RecordMapper;
import com.whc.fivechess.mapper.UserMapper;
import com.whc.fivechess.security.AbuseGuard;
import com.whc.fivechess.security.ClientIpResolver;
import com.whc.fivechess.security.FsServerConfigurator;
import com.whc.fivechess.util.MyUtil;
import com.whc.fivechess.util.PasswordUtil;
import com.whc.fivechess.util.SensitiveWordUtil;
import com.whc.fivechess.websocket.domain.Message;
import com.whc.fivechess.websocket.domain.Room;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@ServerEndpoint(value = "/socket/{id}", configurator = FsServerConfigurator.class)
@Component
public class SocketServer {

    ///用来保存,用户的session
    public static Map<String, Session> sessionMap = new ConcurrentHashMap<>();
    //用来保存对战房间
    public static Map<String, Room> roomMap = new ConcurrentHashMap<>();
    //用来保存用户id对应的房间号
    public static Map<String, String> playerRoomMap = new ConcurrentHashMap<>();
//在线用户ID-昵称映射：存储当前大厅在线用户，ConcurrentHashMap保证并发安全
    public static final Map<String, String> onlineUserNickMap = new ConcurrentHashMap<>();
    //在线用户ID-账号映射：仅用于在线列表按账号搜索（鉴权通过时查库缓存，下线时移除）
    public static final Map<String, String> onlineUserLoginMap = new ConcurrentHashMap<>();
    //已通过令牌鉴权的连接：连接id -> 鉴权时间
    private static final Map<String, Long> authedSessions = new ConcurrentHashMap<>();
    //已建立但尚未完成令牌鉴权的连接：会话id -> 建连信息。
    //必须按「会话id」而不是「用户id」记录：鉴权前连接里的 id 来自 URL 路径，
    //任何人都能填别人的 id，按用户id记录会让攻击连接超时后误关受害者的正常连接。
    private static final Map<String, PendingAuth> pendingAuthSessions = new ConcurrentHashMap<>();
    //所有已建立的连接：会话id -> 会话，供鉴权超时精确关闭对应连接
    private static final Map<String, Session> sessionRegistry = new ConcurrentHashMap<>();
    //房间内待响应的请求：key = 房间号 + "|" + 类型。
    //按类型分开存是必要的：双方可能同时各发一个请求（A 悔棋、B 求和），
    //只按房间存一条会互相覆盖，导致其中一个请求永远同意不了。
    private static final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    //心跳：已鉴权用户最后一次活动（心跳或任意消息）的时间
    private static final Map<String, Long> lastActiveMap = new ConcurrentHashMap<>();
    //因心跳超时被临时移出在线列表的用户（连接仍在，心跳恢复后自动回到在线列表）
    private static final Set<String> heartbeatTimedOut = ConcurrentHashMap.newKeySet();
    //心跳超时判定毫秒数（默认 30 秒；测试可通过 setHeartbeatTimeoutMs 调整）
    static volatile long heartbeatTimeoutMs = 30 * 1000L;
    //心跳巡检周期（毫秒）
    private static final long HEARTBEAT_SWEEP_MS = 1000L;
    //房间失联宽限期（毫秒）：心跳超时后再等这么久仍无任何活动，才把该玩家退出房间。
    //给「切后台/短时断网」留出恢复窗口，避免一断线就把对局和房间清掉。
    static volatile long roomStaleGraceMs = 5 * 60 * 1000L;
    //待处理的邀请：被邀请者id -> {inviterId, roomId, expireAt}
    private static final Map<String, JSONObject> pendingInvites = new ConcurrentHashMap<>();
//邀请未被处理即自动拒绝的超时（毫秒），默认 30 秒；测试可通过 setInviteTimeoutMs 调整
    static volatile long inviteTimeoutMs = 30 * 1000L;
    //连接断开后延迟下线（在线列表宽限期）：断线后不立刻把用户移出在线列表，
    //而是等待该时长；若用户在此期间重连（同一 userId）则取消下线。
    //这样其他用户不会看到“掉线又立刻上线”的闪烁（如某人刷新页面）。
    static volatile long offlineGraceMs = 1500L;
    //待执行的延迟下线任务：用户id -> 定时任务
    private static final Map<String, ScheduledFuture<?>> pendingOffline = new ConcurrentHashMap<>();
    //延迟下线使用的单线程调度器（守护线程，随应用退出）
    private static final ScheduledExecutorService offlineScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fc-offline-grace");
                t.setDaemon(true);
                return t;
            });
    //拒绝后的冷却时长（毫秒），默认 10 秒；测试可通过 setRejectCooldownMs 调整
    static volatile long rejectCooldownMs = 10 * 1000L;
    //拒绝冷却表："邀请者->被邀请者" -> 最近一次拒绝时间
    private static final Map<String, Long> rejectCooldownMap = new ConcurrentHashMap<>();
    //房间内「同意类」操作的并发去重时间戳。
    //双方几乎同时点击悔棋/重开/求和时，同一份请求会收到两次「同意」，若都执行就会：
    //悔棋一次退两步变成退四步、重开被连续重置两次（重新随机颜色两次）导致双方颜色错乱、
    //求和连写两条对局记录。这里按房间记录最近一次应用的时刻，窗口内只应用第一次。
    private static final long ACTION_DEDUP_MS = 500L;
    private static final Map<String, Long> lastRegretAt = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastResetAt = new ConcurrentHashMap<>();
    //最近一次结算发生在哪一局：用于区分「同一局的重复结算」与「结算后新一局的操作」。
    //只按时间去重会把新局开头（500ms 内）的认输误挡成「这一局已经结算过」。
    private static final Map<String, Long> lastResetVersion = new ConcurrentHashMap<>();
    //房间的「局序号」：每开一局（含和棋重开、双方同意重开）自增一次。
    //悔棋/求和/重开的请求会把发起时的局序号带给对手，对手点「同意」时原样带回；
    //服务端比对不一致就说明这条请求属于上一局（例如请求发出后棋局已被和棋重开），直接作废。
    //没有它就会出现「A 请求悔棋、B 同时求和且双方都同意」时，悔棋作用到刚重开的新局上。
    private static final Map<String, Long> boardVersionMap = new ConcurrentHashMap<>();

    /** 拒绝冷却表的键 */
    private static String rejectKey(String inviterId, String inviteeId){
        return inviterId + "->" + inviteeId;
    }

    /** 记录一次拒绝，返回冷却秒数（供前端开始倒计时） */
    private long markRejected(String inviterId, String inviteeId){
        rejectCooldownMap.put(rejectKey(inviterId, inviteeId), System.currentTimeMillis());
        return rejectCooldownMs / 1000;
    }

    /** 距离该邀请者可再次邀请该用户还剩多少秒；0 表示可以邀请 */
    private long rejectCooldownRemainSeconds(String inviterId, String inviteeId){
        Long at = rejectCooldownMap.get(rejectKey(inviterId, inviteeId));
        if(at == null){
            return 0L;
        }
        long remain = rejectCooldownMs - (System.currentTimeMillis() - at);
        return remain <= 0 ? 0L : (remain + 999) / 1000;
    }

    /** 调整拒绝冷却时长（毫秒），供测试使用 */
    public static void setRejectCooldownMs(long ms){
        rejectCooldownMs = ms;
    }

    /** 清空拒绝冷却记录，供测试隔离使用 */
    public static void clearRejectCooldowns(){
        rejectCooldownMap.clear();
    }

    //当天屏蔽表："邀请者->被邀请者" -> 屏蔽日期（yyyy-MM-dd），仅当天有效
    private static final Map<String, String> inviteBlockedDay = new ConcurrentHashMap<>();

    /** 当天日期键 */
    private static String todayKey(){
        return java.time.LocalDate.now().toString();
    }

    /** 清空当天屏蔽记录，供测试隔离使用 */
    public static void clearInviteBlocks(){
        inviteBlockedDay.clear();
    }

    /**
     * 该邀请者当天不可再邀请的被邀请者 id 列表
     */
    private List<String> blockedInviteeIds(String inviterId){
        List<String> ids = new ArrayList<>();
        String today = todayKey();
        String prefix = inviterId + "->";
        for(Map.Entry<String, String> entry : inviteBlockedDay.entrySet()){
            if(today.equals(entry.getValue()) && entry.getKey().startsWith(prefix)){
                ids.add(entry.getKey().substring(prefix.length()));
            }
        }
        return ids;
    }

    //房间未坐下第二位棋手时的占位id
    private static final String EMPTY_PLAYER = "-1";
    //棋盘为 18*18
    private static final int BOARD_SIZE = 18;
    //昵称最大长度，防止超长昵称占用过多内存
    private static final int MAX_NICK_LENGTH = 10;
    //管理员专用昵称：注册侧（Controller.RESERVED_ADMIN_NAMES）已禁止普通用户占用，
    //在线昵称上报（report-nick）必须同样拦住，否则登录后改个昵称就能在大厅与聊天里冒充管理员
    private static final String[] RESERVED_ADMIN_NICKS = {"管理员", "admin"};
    //私人房间密码长度：与前端输入约束保持一致
    private static final int MIN_ROOM_PASSWORD_LENGTH = 4;
    private static final int MAX_ROOM_PASSWORD_LENGTH = 20;
//单条聊天消息最大长度，超出直接丢弃
    private static final int MAX_CHAT_LENGTH = 2000;
    //房间号位数（不足补前导 0，例如 000001）
    private static final int ROOM_NO_WIDTH = 6;
    //房间号分配锁：并发创建房间时保证不会分配到同一个房间号
    private static final Object ROOM_NO_LOCK = new Object();
    //刷新后回填聊天窗口时下发的最近记录条数
    private static final int PUBLIC_HISTORY_LIMIT = 50;
    private static final int ROOM_HISTORY_LIMIT = 100;
    //少于 6 步（3回合）的对局不保存
    private static final int MIN_SAVE_STEPS = 6;
    //单房间步数上限：棋盘格数，写满即和棋，避免房间数据无限增长
    private static final int MAX_STEPS = BOARD_SIZE * BOARD_SIZE;
    //未完成令牌鉴权的连接允许存在的时长（毫秒），超时由定时任务断开
    private static final long AUTH_TIMEOUT_MS = 10 * 1000;
    //「请求-同意」配对的有效期（毫秒）：请求发出后超过该时长即作废，防止旧请求被翻出来复用
    private static final long ACTION_REQUEST_TTL_MS = 60 * 1000L;
    //会话属性名：该连接鉴权时使用的令牌，用于后续每条指令回查令牌是否仍然有效
    private static final String TOKEN_PROPERTY = "fc.token";

    /** 未鉴权连接：记录 URL 路径里的用户 id 与建连时间（此时尚未验证令牌） */
    private static final class PendingAuth {
        final String userId;
        final long at;
        PendingAuth(String userId,long at){ this.userId = userId; this.at = at; }
    }

    /** 房间内待响应的请求：只有被请求方在有效期内同意才生效 */
    private static final class PendingAction {
        final String type;    // regret / peace / restart
        final String from;    // 发起方
        final String to;      // 被请求方（只有他能同意）
        final long version;   // 发起时的局序号
        final long expireAt;
        PendingAction(String type,String from,String to,long version,long expireAt){
            this.type = type;
            this.from = from;
            this.to = to;
            this.version = version;
            this.expireAt = expireAt;
        }
    }

private static GameMapper gameMapper;
    private static RecordMapper recordMapper;
    private static TransactionTemplate transactionTemplate;
    private static TokenStore tokenStore;
    private static UserMapper userMapper;
    private static AbuseGuard abuseGuard;
    private static ChatRecorder chatRecorder;
    private static ModerationService moderationService;
    private static AvatarService avatarService;
    private static ScheduledExecutorService authCleaner;
    //会话id -> 客户端IP：用于连接关闭时释放单IP连接计数
    private static final Map<String, String> sessionIpMap = new ConcurrentHashMap<>();
    //被禁言的用户id（内存缓存，鉴权时从库同步、管理员操作时更新），用于快速拦截发言
    private static final Set<String> mutedUserIds = ConcurrentHashMap.newKeySet();
    //管理员隐身观察的房间：房间id -> 管理员id集合（不计入观战人数，房内用户看不到）
    private static final Map<String, Set<String>> adminObservers = new ConcurrentHashMap<>();
    /** 每个房间保留的系统事件上限，避免长期运行内存无限增长 */
    private static final int ROOM_EVENT_LIMIT = 200;

    @Autowired
    public void setGameMapper(GameMapper gameMapper){
        SocketServer.gameMapper = gameMapper;
    }

    /**
     * 事务模板：对局结算要同时写 game（棋盘二进制）与 record（战绩）两张表，
     * 两步非原子时会留下没有任何 record 引用的孤儿 game 行（占空间且无法被清理）。
     * 由 Spring 注入；单元测试缺少该 bean 时退化为逐条写入。
     */
    @Autowired(required = false)
    public void setTransactionTemplate(TransactionTemplate transactionTemplate){
        SocketServer.transactionTemplate = transactionTemplate;
    }

    @Autowired
    public void setAbuseGuard(AbuseGuard abuseGuard){
        SocketServer.abuseGuard = abuseGuard;
    }

    @Autowired
    public void setChatRecorder(ChatRecorder chatRecorder){
        SocketServer.chatRecorder = chatRecorder;
    }

    @Autowired
    public void setModerationService(ModerationService moderationService){
        SocketServer.moderationService = moderationService;
    }

    @Autowired
    public void setUserMapper(UserMapper userMapper){
        SocketServer.userMapper = userMapper;
    }
    @Autowired
    public void setRecordMapper(RecordMapper recordMapper){
        SocketServer.recordMapper = recordMapper;
    }
    @Autowired
    public void setTokenStore(TokenStore tokenStore){
        SocketServer.tokenStore = tokenStore;
    }

    @Autowired
    public void setAvatarService(AvatarService avatarService){
        SocketServer.avatarService = avatarService;
    }

    @PostConstruct
    public void startAuthCleaner(){
        authCleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ws-auth-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        //周期性断开长时间未完成鉴权的连接
        authCleaner.scheduleWithFixedDelay(this::closeAuthTimeoutSessions, 2, 2, TimeUnit.SECONDS);
        //周期巡检心跳：超过 heartbeatTimeoutMs 未活动的用户移出在线列表，心跳恢复后重新上线
        authCleaner.scheduleWithFixedDelay(this::sweepHeartbeat, HEARTBEAT_SWEEP_MS, HEARTBEAT_SWEEP_MS, TimeUnit.MILLISECONDS);
        //周期巡检邀请：超过 inviteTimeoutMs 未被处理则自动拒绝
        authCleaner.scheduleWithFixedDelay(this::sweepInvites, HEARTBEAT_SWEEP_MS, HEARTBEAT_SWEEP_MS, TimeUnit.MILLISECONDS);
        //周期巡检悔棋/求和/重开：过期即作废并告知发起方，避免请求静默消失后发起方一直干等
        authCleaner.scheduleWithFixedDelay(this::sweepPendingActions, HEARTBEAT_SWEEP_MS, HEARTBEAT_SWEEP_MS, TimeUnit.MILLISECONDS);
        //周期回收僵尸房间：心跳超时不会退房，长时间无活动的人需要被请出房间，
        //否则房间会永久留在内存并出现在大厅（别人进去等不到房主准备）
        authCleaner.scheduleWithFixedDelay(this::sweepStaleRooms, 60_000L, 60_000L, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stopAuthCleaner(){
        //先告诉所有在线用户「服务正在重启」，再把连接按 1012（Service Restart）关闭：
        //前端收到这条消息或这个关闭码即可立即弹出「服务已重启，请重新登录」，
        //不必等到下一次请求失败才发现登录态没了。
        notifyServerRestart();
        if(authCleaner!=null){
            authCleaner.shutdownNow();
        }
    }

    /**
     * 重启/停机前通知所有在线连接：服务即将重启，登录状态将失效。
     * 只做尽力而为的通知，任何一条发送失败都不影响其它连接与停机流程。
     */
    private void notifyServerRestart(){
        Map<String, Session> snapshot = new HashMap<>(sessionMap);
        if(snapshot.isEmpty()){
            return;
        }
        JSONObject notice = new JSONObject();
        notice.put("code",503);
        notice.put("type","server-restart");
        notice.put("content","服务正在重启，登录状态将失效，请稍后重新登录。");
        String payload = notice.toString();
        for(Map.Entry<String, Session> entry : snapshot.entrySet()){
            Session s = entry.getValue();
            if(s==null || !s.isOpen()){
                continue;
            }
            try{
                this.sendMessage(payload, s);
                s.close(new CloseReason(CloseReason.CloseCodes.SERVICE_RESTART, "server-restart"));
            }catch(Exception ignored){
                //连接可能已经断了，忽略
            }
        }
        System.out.println("服务重启前已通知 "+snapshot.size()+" 个在线连接\t"+ MyUtil.now());
    }

    /**
     * 断开长时间未完成令牌鉴权的连接
     */
    private void closeAuthTimeoutSessions(){
        long now = System.currentTimeMillis();
        for(Map.Entry<String, PendingAuth> entry : pendingAuthSessions.entrySet()){
            PendingAuth pending = entry.getValue();
            if(pending == null || now - pending.at < AUTH_TIMEOUT_MS){
                continue;
            }
            String sessionId = entry.getKey();
            pendingAuthSessions.remove(sessionId, pending);
            if(authedSessions.containsKey(pending.userId)){
                continue;
            }
            System.out.println("连接未在 "+(AUTH_TIMEOUT_MS/1000)+" 秒内完成令牌鉴权，已断开："+pending.userId);
            //只关闭「这条」连接：按会话id定位，绝不会误关同 id 的正常连接
            closeQuietly(sessionRegistry.get(sessionId));
        }
    }

/**
     * 延迟下线：连接断开后不立即把用户移出在线列表，而是等待宽限期（offlineGraceMs）；
     * 若宽限期内该用户重连（同一 userId），则取消本次下线。
     * 只有宽限期结束且确认没有活动连接时，才真正移出在线列表并广播。
     */
    private void scheduleOfflineRemoval(String id){
        cancelOfflineRemoval(id);
        ScheduledFuture<?> future = offlineScheduler.schedule(() -> {
            pendingOffline.remove(id);
            //宽限期内已重连：不下线
            Session current = sessionMap.get(id);
            if(current!=null && current.isOpen()){
                return;
            }
            //宽限期内没有重连：该用户确实离开了，退出其所在房间（房主离开则转移/解散房间）
            if(playerRoomMap.containsKey(id)){
                this.quitRoom(id);
            }
            if(!onlineUserNickMap.containsKey(id)){
                return;
            }
            onlineUserNickMap.remove(id);
            onlineUserLoginMap.remove(id);
            lastActiveMap.remove(id);
            heartbeatTimedOut.remove(id);
            sendOnlineUserList();
            System.out.println("用户："+id+"断线超过宽限期，已移出在线列表\t"+ MyUtil.now());
        }, offlineGraceMs, TimeUnit.MILLISECONDS);
        pendingOffline.put(id, future);
    }

    /**
     * 取消该用户的延迟下线（该用户已在宽限期内重连）
     */
    private void cancelOfflineRemoval(String id){
        ScheduledFuture<?> prev = pendingOffline.remove(id);
        if(prev!=null){
            prev.cancel(false);
        }
    }

    /**
     * 调整断线宽限期（毫秒），供测试使用
     */
    public static void setOfflineGraceMs(long ms){
        offlineGraceMs = ms;
    }

    /**
     * 心跳巡检：把超过 heartbeatTimeoutMs 未活动的用户移出在线列表；
     * 心跳恢复后（见 onMessage）再重新上线。仅在线集合发生变化时广播。
     */
    private void sweepHeartbeat(){
        long now = System.currentTimeMillis();
        boolean changed = false;
        for(Map.Entry<String, Long> entry : lastActiveMap.entrySet()){
            String uid = entry.getKey();
            if(!onlineUserNickMap.containsKey(uid)){
                continue;
            }
            boolean fresh = now - entry.getValue() <= heartbeatTimeoutMs;
            if(!fresh){
                if(heartbeatTimedOut.add(uid)){
                    changed = true;
                }
            }else{
                if(heartbeatTimedOut.remove(uid)){
                    changed = true;
                }
            }
        }
        if(changed){
            sendOnlineUserList();
        }
    }

    /**
     * 调整心跳超时（毫秒），供测试使用
     */
    public static void setHeartbeatTimeoutMs(long ms){
        heartbeatTimeoutMs = ms;
    }

    /**
     * 回收「全员长时间失联」的房间。
     *
     * 为什么需要：心跳超时只会把用户标成「离线」（大厅显示灰点），并不会退房；
     * 移动端切后台、网络半开时 TCP 连接仍然是 open，服务端收不到 close，
     * 于是房间会一直留在 roomMap 里、一直出现在大厅列表上。
     * 别人点进去永远等不到房主准备（房主其实早就不在了），房间变成永久占位的僵尸房间。
     *
     * 判定用「最后一次活动时间」而不是「心跳超时标记」：切后台几十秒回来是常见操作，
     * 只有超过心跳超时 + 宽限期（默认 5 分钟）都没任何活动，才认为这个人真的走了。
     * 房内所有人都走了才回收，仍有人在对局/观战时不打扰。
     */
    public void sweepStaleRooms(){
        if(roomMap.isEmpty()){
            return;
        }
        long staleBefore = System.currentTimeMillis() - heartbeatTimeoutMs - roomStaleGraceMs;
        List<String> victims = new ArrayList<>();
        for(Room room : roomMap.values()){
            for(String uid : new String[]{room.getPlayerId1(), room.getPlayerId2()}){
                if(uid==null || uid.trim().isEmpty() || EMPTY_PLAYER.equals(uid)){
                    continue;
                }
                Long last = lastActiveMap.get(uid);
                //从没记录过活跃时间（异常残留）或活跃时间早于阈值：视为已离开
                if(last == null || last < staleBefore){
                    victims.add(uid);
                }
            }
        }
        for(String uid : victims){
            if(playerRoomMap.containsKey(uid)){
                System.out.println("用户："+uid+" 长时间无活动，已自动退出其房间\t"+ MyUtil.now());
                this.quitRoom(uid);
                //连接可能还挂着（半开连接），必须主动告诉它已经不在房间里了，
                //否则前端会停在一个早已被回收的房间视图上，点任何操作都提示房间不存在
                this.sendMessageById(uid,
                        JSONUtil.toJsonStr(new Message<>(200,"room-closed","长时间无操作，已自动退出房间")));
            }
        }
        //回收后可能留下没有玩家的房间，交给孤儿清理兜底
        cleanupOrphanRooms();
    }

    /** 调整房间失联宽限期（毫秒），供测试使用 */
    public static void setRoomStaleGraceMs(long ms){
        roomStaleGraceMs = ms;
    }

    /** 当前心跳超时（毫秒），供测试还原现场 */
    public static long getHeartbeatTimeoutMsForTest(){
        return heartbeatTimeoutMs;
    }

    /** 当前房间失联宽限期（毫秒），供测试还原现场 */
    public static long getRoomStaleGraceMsForTest(){
        return roomStaleGraceMs;
    }

    /**
     * 邀请超时巡检：被邀请者在 inviteTimeoutMs 内未处理弹窗时自动拒绝，
     * 通知邀请者并把被邀请者的邀请弹窗关闭；邀请发起者已不在该房间时静默作废
     */
    private void sweepInvites(){
        long now = System.currentTimeMillis();
        //顺带清理已过期的「操作去重」时间戳，避免房间解散后这些房间号长期留在内存里
        long stale = now - 10 * 60 * 1000L;
        lastRegretAt.entrySet().removeIf(e -> e.getValue() < stale);
        lastResetAt.entrySet().removeIf(e -> e.getValue() < stale);
        //结算时的局序号跟随结算时间戳一起清理
        lastResetVersion.keySet().removeIf(id -> !lastResetAt.containsKey(id));
        //局序号只在房间还存在时有意义，房间解散后一并清掉，避免长期占用内存
        boardVersionMap.keySet().removeIf(id -> !roomMap.containsKey(id));
        for(Map.Entry<String, JSONObject> entry : pendingInvites.entrySet()){
            String inviteeId = entry.getKey();
            JSONObject pending = entry.getValue();
            if(pending.getLong("expireAt") > now){
                continue;
            }
            pendingInvites.remove(inviteeId);
            String inviterId = pending.getStr("inviterId");
            String roomId = pending.getStr("roomId");
            Room room = roomId==null ? null : roomMap.get(roomId);
            if(room==null || !isRoomMember(room,inviterId)){
                continue;
            }
            synchronized (room){
                if(room.getInvitedIds()!=null){
                    room.getInvitedIds().remove(inviteeId);
                }
            }
            //通知邀请者：对方超时未处理，邀请自动取消
            JSONObject rejected = new JSONObject();
            rejected.put("type","invite-rejected");
            rejected.put("roomId",roomId);
            rejected.put("byId",inviteeId);
            rejected.put("byName",onlineUserNickMap.getOrDefault(inviteeId,"对方"));
            rejected.put("reason","timeout");
            //超时未处理同样视为拒绝，进入冷却
            rejected.put("cooldown",markRejected(inviterId,inviteeId));
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(rejected));
            //通知被邀请者：关闭邀请弹窗
            JSONObject expired = new JSONObject();
            expired.put("type","invite-timeout");
            expired.put("roomId",roomId);
            expired.put("fromId",inviterId);
            this.sendMessageById(inviteeId,JSONUtil.toJsonStr(expired));
        }
        //清理已过期的拒绝冷却记录
        long expireBefore = now - rejectCooldownMs;
        for(Map.Entry<String, Long> cooldownEntry : rejectCooldownMap.entrySet()){
            if(cooldownEntry.getValue() < expireBefore){
                rejectCooldownMap.remove(cooldownEntry.getKey());
            }
        }
        //清理非当天的屏蔽记录
        String today = todayKey();
        for(Map.Entry<String, String> blockEntry : inviteBlockedDay.entrySet()){
            if(!today.equals(blockEntry.getValue())){
                inviteBlockedDay.remove(blockEntry.getKey());
            }
        }
    }

    /**
     * 悔棋 / 求和 / 重开请求的超时巡检。
     *
     * <p>请求只转发给对手一次，对手若因弱网掉线没收到弹窗，就不会有任何「拒绝」回执，
     * 发起方会一直停在「等待对方响应」——既不能重发，也等不到任何结论。
     * 这里在请求过期时统一作废，并主动告知发起方，让这段交互有明确收尾。</p>
     *
     * <p>顺带清理房间已解散的残留条目：待办是按房间记录的，房间没了就永远不会再被读到。</p>
     */
    private void sweepPendingActions(){
        long now = System.currentTimeMillis();
        for(Map.Entry<String, PendingAction> entry : pendingActions.entrySet()){
            PendingAction action = entry.getValue();
            if(action == null){
                //ConcurrentHashMap 不允许 null 值，这里只可能出现在并发移除的瞬间
                pendingActions.remove(entry.getKey());
                continue;
            }
            String key = entry.getKey();
            int sep = key.lastIndexOf('|');
            String roomId = (sep > 0) ? key.substring(0,sep) : null;
            boolean roomGone = roomById(roomId) == null;
            if(!roomGone && action.expireAt > now){
                continue;
            }
            //remove(key,value) 返回 true 才说明是本线程摘掉的，保证同一条请求只通知一次
            if(!pendingActions.remove(key,action)){
                continue;
            }
            if(roomGone){
                continue;
            }
            JSONObject msg = new JSONObject();
            msg.put("code",200);
            msg.put("type","action-invalid");
            msg.put("content","对手未在有效期内响应，本次请求已失效");
            this.sendMessageById(action.from,JSONUtil.toJsonStr(msg));
        }
    }

    /**
     * 调整邀请超时（毫秒），供测试使用
     */
    public static void setInviteTimeoutMs(long ms){
        inviteTimeoutMs = ms;
    }

    /**
     * 令牌鉴权：必须是当前连接对应的账号，且令牌仍然有效。
     * 失败时除 type=auth-fail 外还带上 reason，让前端能区分
     * 「服务重启」（restart）与「账号在别处登录」（superseded），给出不同文案。
     */
    private void auth(JSONObject data,String id,Session session){
        String userId = data.getStr("userId");
        String token = data.getStr("token");
        String reason = null;
        if(!Objects.equals(userId,id)){
            reason = TokenStore.REASON_SUPERSEDED;
        }else if(tokenStore == null){
            reason = TokenStore.REASON_MISSING;
        }else{
            reason = tokenStore.invalidReason(userId, token);
        }
        if(reason != null){
            JSONObject fail = new JSONObject();
            fail.put("code",401);
            fail.put("type","auth-fail");
            fail.put("content","登录状态无效，请重新登录");
            fail.put("reason",reason);
            this.sendMessage(fail.toString(),session);
            //鉴权失败直接断开，避免无效连接占用资源
            closeQuietly(session);
            return;
        }
        pendingAuthSessions.remove(session.getId());
        //封号与禁言状态以数据库为准：封号即使持有有效令牌也不允许连接，禁言写入内存缓存
        User authUser = null;
        if(moderationService != null){
            authUser = moderationService.findById(userId);
            if(authUser != null && Boolean.TRUE.equals(authUser.getBanned())){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(403,"banned",ModerationService.BAN_MESSAGE)),session);
                closeQuietly(session);
                return;
            }
            if(authUser != null && Boolean.TRUE.equals(authUser.getMuted())){
                mutedUserIds.add(userId);
            }else{
                mutedUserIds.remove(userId);
            }
        }
        //告知客户端自身的禁言状态：刷新后聊天窗口仍能保持冻结与提示
        this.sendMessage(
                JSONUtil.toJsonStr(new Message<>(200,"self-state",mutedUserIds.contains(id))),
                session);
        //令牌已校验通过，此时才允许接管该账号的会话槽并顶掉之前的连接。
        //顶号前先确认旧连接确实属于这个账号（sessionMap 中只放已鉴权的连接），
        //未鉴权的连接永远不会写进 sessionMap，因此不可能把别人顶下线。
        Session previous = sessionMap.put(id,session);
        if(previous != null && previous != session){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"kicked","你账号已经在别处登录")),previous);
            closeQuietly(previous);
            //旧连接被顶掉后其 onClose 可能不会及时触发，这里主动释放它占用的单 IP 连接计数
            releaseIpOfSession(previous);
            authedSessions.remove(id);
            //同一账号重连：取消除可能已排定的延迟下线，避免“掉线又立刻上线”的闪烁
            cancelOfflineRemoval(id);
        }
        //记住本次连接使用的令牌：后续每条指令都会回查，登出/顶号/过期后长连接立即失效
        try{
            session.getUserProperties().put(TOKEN_PROPERTY,token);
        }catch (Exception e){
            System.out.println("缓存连接令牌失败：" + e.getMessage());
        }
        authedSessions.put(id,System.currentTimeMillis());
        //鉴权成功说明连接仍然存活：取消除可能存在的延迟下线
        cancelOfflineRemoval(id);
        //鉴权成功即视为一次活动，并清除可能存在的心跳超时标记
        lastActiveMap.put(id,System.currentTimeMillis());
        heartbeatTimedOut.remove(id);
        //令牌续期：闲置过期从本次活动重新计算
        if(tokenStore != null){
            tokenStore.touch(userId);
        }
        cacheUserLogin(userId);
        //鉴权通过即完成「上线」：昵称取数据库里的注册昵称，不再等客户端再发一次 report-nick。
        //原来要等一个完整往返（auth-success → report-nick → 广播），网络稍差时在线列表里
        //就会出现「人已经进大厅了、名单里还没有他」的空窗。这里直接入名单并广播，
        //随后下发给自己的在线列表也已包含本人，不会先缺自己再补上。
        String authNick = (authUser != null) ? authUser.getName() : null;
        if(authNick == null || authNick.trim().isEmpty()){
            authNick = realNameOf(userId);
        }
        if(authNick != null && !authNick.trim().isEmpty()){
            authNick = authNick.trim();
            if(authNick.length() > MAX_NICK_LENGTH){
                authNick = authNick.substring(0,MAX_NICK_LENGTH);
            }
            if(!authNick.equals(onlineUserNickMap.get(id))){
                onlineUserNickMap.put(id, authNick);
                //此刻本连接的会话槽已登记，广播能覆盖到自己和所有已在线的用户
                sendOnlineUserList();
            }
        }
        this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"auth-success","鉴权成功")),session);
        //鉴权通过后才下发在线房间列表与在线用户列表
        this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"online-rooms",onlineRoomViews(id))),session);
        this.sendMessage(
                JSONUtil.toJsonStr(new Message<>(200,"online-user-list",onlineListData())),
                session);
        //下发当天屏蔽名单：该邀请者当天不可再邀请的用户
this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"invite-blocked-list",blockedInviteeIds(id))),session);
        //刷新页面/断线重连时若仍在房间或仍在观战，下发房间视图让前端恢复到原页面
        this.sendRoomRestore(id, session);
        //下发最近公共聊天记录：保证刷新后公共聊天窗口的内容不丢失
        this.sendMessage(
                JSONUtil.toJsonStr(new Message<>(200, "public-chat-history", publicChatHistoryJson(PUBLIC_HISTORY_LIMIT))),
                session);
        //补推断线期间错过的一次性请求（邀请、悔棋/求和/重开）。必须放在 room-restore 之后：
        //前端要先回到房间页面，弹窗才有地方落。
        this.resendPendingRequests(id, session);
    }

    /**
     * 断线重连后补推未过期的「一次性」请求：邀请、悔棋、求和、重开。
     *
     * <p>这四类消息都是点对点只发一次，服务端不留离线副本（{@link #sendMessageById} 在目标
     * 不在线时直接返回）。弱网下接收方只要短暂断线，这条消息就被整条丢弃：发起方永远停在
     * 「等待对方响应」，接收方重连后也再没有任何途径看到弹窗——这正是「对方说发了邀请，
     * 我这边从来没弹过窗」的原因。</p>
     *
     * <p>服务端本来就持有这两类请求的待办状态（{@code pendingInvites} / {@code pendingActions}，
     * 都带过期时间与发起方），所以重连时按待办补推一次即可，无需引入离线消息队列：
     * 只补「未过期、且发起方仍在同一房间」的请求，其余顺手作废，避免残留脏状态。</p>
     *
     * <p>补推是幂等的：前端收到新的邀请会先关掉旧弹窗再开（同一时间只保留一个），
     * 收到重复的悔棋/求和/重开请求也只会刷新一次弹窗，不会叠加。</p>
     */
    private void resendPendingRequests(String userId, Session session) {
        if (userId == null || session == null || !session.isOpen()) {
            return;
        }
        long now = System.currentTimeMillis();

        //① 未处理的邀请：被邀请者重连后重新弹窗
        JSONObject pendingInvite = pendingInvites.get(userId);
        if (pendingInvite != null) {
            String inviterId = pendingInvite.getStr("inviterId");
            String inviteRoomId = pendingInvite.getStr("roomId");
            Room inviteRoom = roomById(inviteRoomId);
            long expireAt = pendingInvite.getLong("expireAt", 0L);
            if (expireAt <= now || inviteRoom == null || !isRoomMember(inviteRoom, inviterId)) {
                //已过期，或邀请人自己已经离开房间：这条邀请不再有落点，直接作废
                pendingInvites.remove(userId);
            } else {
                JSONObject invite = new JSONObject();
                invite.put("type", "invite-received");
                invite.put("roomId", inviteRoomId);
                invite.put("fromId", inviterId);
                invite.put("fromName", onlineUserNickMap.get(inviterId));
                invite.put("roomOpen", inviteRoom.isOpen());
                //标记为「重连补推」：页面没刷新过时旧弹窗可能还在，前端据此跳过重复弹窗
                invite.put("resend", true);
                this.sendMessage(JSONUtil.toJsonStr(invite), session);
            }
        }

        //② 未响应的悔棋 / 求和 / 重开：被请求方重连后重新弹窗
        String currentRoomId = playerRoomMap.get(userId);
        for (Map.Entry<String, PendingAction> entry : pendingActions.entrySet()) {
            PendingAction action = entry.getValue();
            if (action == null || !Objects.equals(action.to, userId)) {
                continue;
            }
            //key 形如 "roomId|regret"，房间号取服务端记录值，不用任何客户端上报的字段
            String key = entry.getKey();
            int sep = key.lastIndexOf('|');
            String actionRoomId = (sep > 0) ? key.substring(0, sep) : null;
            if (action.expireAt <= now
                    || !Objects.equals(currentRoomId, actionRoomId)
                    || !isRoomMember(roomById(actionRoomId), action.from)) {
                //已过期、自己已不在该房间、或发起方已离开：作废，不再打扰
                pendingActions.remove(key, action);
                continue;
            }
            JSONObject request = new JSONObject();
            request.put("code", 200);
            request.put("type", action.type);
            request.put("to", userId);
            request.put("content", action.from);
            request.put("boardVersion", action.version);
            //标记为「重连补推」：页面没刷新过时旧弹窗可能还在，前端据此跳过重复弹窗
            request.put("resend", true);
            this.sendMessage(JSONUtil.toJsonStr(request), session);
        }
    }

    /**
     * 鉴权后下发房间恢复信息：刷新页面或断线重连时，若用户仍在房间内（含观战），
     * 让前端恢复到刷新前的房间/观战页面，而不是回到大厅首页。
     */
    private void sendRoomRestore(String userId, Session session){
        String roomId = playerRoomMap.get(userId);
        if(roomId==null){
            return;
        }
        Room room = roomById(roomId);
        if(room==null){
            playerRoomMap.remove(userId);
            return;
        }
        boolean player = isRoomMember(room,userId);
        boolean spectator = !player && room.getSpectators()!=null && room.getSpectators().containsKey(userId);
        if(!player && !spectator){
            return;
        }
        JSONObject msg = new JSONObject();
        msg.put("type","room-restore");
        msg.put("role", player ? "player" : "spectator");
        msg.put("roomId", roomId);
        synchronized (room){
            msg.put("roomView", roomView(room));
            msg.put("steps", room.getSteps() != null ? room.getSteps() : room.getFinalSteps());
            msg.put("color1", room.getColor1());
            msg.put("color2", room.getColor2());
            msg.put("readyId1", room.isReadyId1());
            msg.put("readyId2", room.isReadyId2());
        }
        this.sendMessage(JSONUtil.toJsonStr(msg), session);
        //恢复房间后一并下发该房间最近的聊天记录，保证刷新后房间聊天内容不丢失
        this.sendMessage(
                JSONUtil.toJsonStr(new Message<>(200, "room-chat-history", roomChatHistoryJson(roomId, ROOM_HISTORY_LIMIT))),
                session);
    }

    /**
     * 最近公共聊天记录（用于刷新后回填公共聊天窗口）。
     * 注意：库里存的是原文，下发到聊天窗口的内容仍按敏感词过滤，和实时聊天保持一致。
     */
    private List<JSONObject> publicChatHistoryJson(int limit) {
        List<JSONObject> list = new ArrayList<>();
        if (chatRecorder == null) {
            return list;
        }
        List<ChatPublicRecord> records = chatRecorder.listPublic(limit);
        for (int i = records.size() - 1; i >= 0; i--) {
            ChatPublicRecord record = records.get(i);
            JSONObject item = new JSONObject();
            item.put("player", record.getPlayer());
            item.put("nick", record.getNick());
            item.put("content", SensitiveWordUtil.filterMsg(record.getContent()));
            item.put("time", record.getCreatedAt());
            list.add(item);
        }
        return list;
    }

    /**
     * 最近房间聊天记录（用于刷新/重连恢复房间后回填房间聊天窗口），内容同样按敏感词过滤。
     */
    private List<JSONObject> roomChatHistoryJson(String roomId, int limit) {
        List<JSONObject> list = new ArrayList<>();
        if (chatRecorder == null || roomId == null) {
            return list;
        }
        List<ChatRoomRecord> records = chatRecorder.listByRoom(roomId, limit);
        for (int i = records.size() - 1; i >= 0; i--) {
            ChatRoomRecord record = records.get(i);
            JSONObject item = new JSONObject();
            item.put("player", record.getPlayer());
            item.put("nick", record.getNick());
            item.put("content", SensitiveWordUtil.filterMsg(record.getContent()));
            item.put("time", record.getCreatedAt());
            list.add(item);
        }
        return list;
    }

    /**
     * 鉴权通过时缓存该用户的账号，供在线列表按账号搜索（账号非数字或查库异常时忽略，不影响鉴权）
     */
    private void cacheUserLogin(String userId){
        if(userMapper==null || userId==null){
            return;
        }
        try{
            User user = userMapper.selectById(Integer.valueOf(userId));
            if(user!=null && user.getLogin()!=null){
                onlineUserLoginMap.put(userId,user.getLogin());
            }
        }catch(Exception e){
            //忽略：仅影响在线列表按账号搜索，不影响其它功能
        }
    }

/**
     * 用户的展示昵称：优先使用数据库中的注册昵称（注册时已保证唯一），
     * 取不到时再回退在线昵称。
     * 建房/加入房间/写入对局记录时使用该值，避免客户端上报任意昵称冒用他人身份。
     */
    private String displayNameOf(String userId){
        String real = realNameOf(userId);
        if(real!=null && !real.trim().isEmpty()){
            return real;
        }
        String nick = onlineUserNickMap.get(userId);
        return (nick==null || nick.trim().isEmpty()) ? "匿名用户" : nick;
    }

    /** 数据库中的账号昵称（取不到时返回 null） */
    private String realNameOf(String userId){
        if(userMapper==null || userId==null){
            return null;
        }
        try{
            User user = userMapper.selectById(Integer.valueOf(userId));
            return user==null ? null : user.getName();
        }catch(Exception e){
            return null;
        }
    }

    /**
     * 该昵称是否为管理员专用（"管理员" / "admin" 及其不可见字符变体）。
     * 与注册侧 Controller.RESERVED_ADMIN_NAMES 保持同一套判定，
     * 避免出现「注册时不让占、登录后改昵称却能占」的不一致。
     */
    private boolean isReservedAdminNick(String nick){
        if(nick == null){
            return false;
        }
        String normalized = MyUtil.stripInvisible(nick);
        for(String reserved : RESERVED_ADMIN_NICKS){
            if(reserved.equalsIgnoreCase(nick) || reserved.equalsIgnoreCase(normalized)){
                return true;
            }
        }
        return moderationService != null && moderationService.isAdminLogin(nick);
    }

    /**
     * 账号真实昵称的安全取值：查不到时退回在线名单，最终兜底「匿名用户」。
     * 不能直接返回 null —— 在线名单是 ConcurrentHashMap，写入 null 会抛空指针。
     */
    private String safeRealNameOf(String userId){
        String real = realNameOf(userId);
        if(real != null && !real.trim().isEmpty()){
            return real.trim();
        }
        String online = onlineUserNickMap.get(userId);
        return (online == null || online.trim().isEmpty()) ? "匿名用户" : online;
    }

    /**
     * 该昵称是否已被其他账号注册使用。
     * 用于阻止通过 report-nick 把自己显示成“管理员”或其他用户，造成身份混淆。
     */
    private boolean isNickOwnedByOther(String nick,String selfId){
        if(userMapper==null || nick==null || nick.isEmpty()){
            return false;
        }
        try{
            List<User> owners = userMapper.selectList(new QueryWrapper<User>().eq("name",nick));
            if(owners==null){
                return false;
            }
            for(User owner : owners){
                if(owner.getId()!=null && !Objects.equals(String.valueOf(owner.getId()),selfId)){
                    return true;
                }
            }
        }catch(Exception e){
            //查库失败时不阻断上报（保持可用），由前端转义与后续校验兜底
        }
        return false;
    }

    private void closeQuietly(Session session){
        try{
            if(session!=null && session.isOpen()){
                session.close();
            }
        }catch (Exception e){
            System.out.println("关闭连接失败："+e.getMessage());
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("id")String id){
        String clientIp = clientIpOf(session);
        //单 IP 连接数限制：超出上限直接拒绝并断开，避免单个来源开大量连接耗尽内存
        if(abuseGuard != null && clientIp != null && !abuseGuard.enterWs(clientIp)){
            this.sendMessage(
                    JSONUtil.toJsonStr(new Message<>(429,"ws-limit","当前网络连接数过多，请稍后再试！")),
                    session);
            closeQuietly(session);
            System.out.println("拒绝连接：IP " + clientIp + " 已超过单 IP 连接数上限\t" + MyUtil.now());
            return;
        }

        sessionRegistry.put(session.getId(),session);
        if(clientIp != null){
            sessionIpMap.put(session.getId(),clientIp);
        }
        //鉴权通过前既不写入 sessionMap，也不顶掉已有连接。
        //id 来自 URL 路径 /socket/{id}，此时尚未验证令牌：若在这里就 sessionMap.put(id,session)
        //并给旧连接发 kicked，任何人不带令牌也能凭别人的 id 把对方顶下线，
        //还能在自己的连接上收到本应发给对方的私信。接管动作统一放到 auth() 令牌校验通过之后。
        pendingAuthSessions.put(session.getId(),new PendingAuth(id,System.currentTimeMillis()));

        System.out.println("用户："+id+"已连接服务器（待鉴权）\t"+ MyUtil.now());
    }

    /** 读取握手阶段解析出的客户端 IP（取不到时返回 null，该连接不参与单 IP 限制） */
    private String clientIpOf(Session session){
        try{
            Object ip = session.getUserProperties().get(FsServerConfigurator.CLIENT_IP);
            return ip == null ? null : String.valueOf(ip);
        }catch(Exception e){
            return null;
        }
    }

    /** 释放某个连接占用的单 IP 连接计数（按会话 id 记录，重复释放不会多减） */
    private void releaseIpOfSession(Session session){
        if(session == null || abuseGuard == null){
            return;
        }
        String ip = sessionIpMap.remove(session.getId());
        if(ip != null){
            abuseGuard.leaveWs(ip);
        }
    }
    @OnError
    public void onError(Session session,Throwable error,@PathParam("id") String id){
System.out.println("用户："+id+"已异常下线！\t"+ MyUtil.now());
        System.out.println("异常原因：" + error);
        //无论是否为当前连接，都释放该会话占用的单 IP 连接计数
        releaseIpOfSession(session);
        //本连接的登记信息与鉴权待办一律按「会话id」清理，避免误删同 id 的其他连接
        sessionRegistry.remove(session.getId());
        pendingAuthSessions.remove(session.getId());
        //断开的管理员不再作为隐身观察者
        removeFromAdminObservers(id);
        //只有当前保存的连接就是出错的连接时才做清理，避免旧连接把新连接顶掉
        Session cacheSession = sessionMap.get(id);
        if(cacheSession!=null && cacheSession!=session){
            return;
        }
        sessionMap.remove(id);
        authedSessions.remove(id);
//异常掉线：同样延后到宽限期结束再退出房间与移出在线列表，给“立刻重连”留出恢复机会
        scheduleOfflineRemoval(id);
    }
    @OnClose
    public void onClose(Session session,@PathParam("id") String id){
System.out.println("用户："+id+"已正常下线\t"+ MyUtil.now());
        //无论是否为当前连接，都释放该会话占用的单 IP 连接计数
        releaseIpOfSession(session);
        //本连接的登记信息与鉴权待办一律按「会话id」清理
        sessionRegistry.remove(session.getId());
        pendingAuthSessions.remove(session.getId());
        //断开的管理员不再作为隐身观察者
        removeFromAdminObservers(id);
        //只有当前保存的连接就是关闭的连接时才做清理，避免旧连接把新连接顶掉
        Session cacheSession = sessionMap.get(id);
        if(cacheSession!=null && cacheSession!=session){
            return;
        }
        sessionMap.remove(id);
        authedSessions.remove(id);
//正常下线：退出房间与移出在线列表都延后到宽限期结束，
        //这样刷新页面这类的“断开后立刻重连”既不会丢房间，也不会让其他用户看到列表闪烁
        scheduleOfflineRemoval(id);
    }
    @OnMessage
    public void onMessage(String message,Session session,@PathParam("id") String id){
        JSONObject data;
        try{
            data = JSONUtil.parseObj(message);
        }catch (Exception e){
            //只记录长度与片段，不打印整段原文：
            //未鉴权连接也能触发这里，打印整段（最大 8KB）会被用来刷爆日志/污染日志
            System.out.println("忽略无法解析的消息（长度 " + message.length() + "）：" + brief(message));
            return;
        }
        if(data == null){
            return;
        }
        String type = data.getStr("type");
        if(type == null){
            System.out.println("忽略缺少type的消息（长度 " + message.length() + "）：" + brief(message));
            return;
        }
        //除鉴权外的所有指令都要求连接已通过令牌鉴权
        if(!"auth".equals(type) && !authedSessions.containsKey(id)){
            System.out.println("忽略未鉴权连接的指令：" + type);
            return;
        }
        //已鉴权的长连接也要逐条回查令牌：登出、异地登录顶号、闲置过期后，
        //旧连接不能再凭「建立时校验过一次」继续发言、下棋甚至执行管理员指令。
        if(!"auth".equals(type) && tokenStore != null){
            String sessionToken = null;
            try{
                Object cached = session.getUserProperties().get(TOKEN_PROPERTY);
                sessionToken = (cached == null) ? null : String.valueOf(cached);
            }catch (Exception e){
                sessionToken = null;
            }
            String tokenReason = tokenStore.invalidReason(id, sessionToken);
            if(tokenReason != null){
                JSONObject fail = new JSONObject();
                fail.put("code",401);
                fail.put("type","auth-fail");
                fail.put("content","登录状态无效，请重新登录");
                fail.put("reason",tokenReason);
                this.sendMessage(fail.toString(),session);
                System.out.println("连接令牌已失效，断开：" + id + "（" + tokenReason + "）");
                closeQuietly(session);
                return;
            }
        }
        //已鉴权连接：任何消息都视为一次心跳/活动；超时移出的用户恢复心跳后重新上线
        if(authedSessions.containsKey(id)){
            lastActiveMap.put(id,System.currentTimeMillis());
            if(heartbeatTimedOut.remove(id)){
                sendOnlineUserList();
            }
        }
        //单个消息处理异常不应影响连接和其它用户
        try{
            ///主要逻辑
            switch (type){
                case "auth":
                    this.auth(data,id,session);
                    break;
case "create-room":
                    //身份以当前已鉴权连接为准，忽略客户端上报的 user.id，防止冒用他人身份建房
                    this.createRoom(session,id);
                    break;
                case "quit-room":
                    //退出的只能是当前连接自己的房间，避免用别人的 userId 把其他玩家踢出房间
                    this.quitRoom(id);
                    break;
case "join-room":
                    //身份以当前已鉴权连接为准，忽略客户端上报的 user.id，防止以他人身份占座
                    this.joinRoom(data,session,id);
                    break;
                case "invite":
                    this.handleInvite(data,id);
                    break;
                case "invite-accept":
                    this.handleInviteAccept(data,id,session);
                    break;
                case "invite-reject":
                    this.handleInviteReject(data,id);
                    break;
                case "spectate":
                    this.handleSpectate(data,id,session);
                    break;
                case "toggle-room-open":
                    this.handleToggleRoomOpen(data,id);
                    break;
case "kick-player":
                    this.handleKick(data,id);
                    break;
                //【管理员】禁言 / 解除禁言 / 封号 / 隐身进房查看 / 解散房间（仅管理员可用，服务端二次校验身份）
                case "admin-mute":
                    this.handleAdminMute(data,id,true);
                    break;
                case "admin-unmute":
                    this.handleAdminMute(data,id,false);
                    break;
                case "admin-ban":
                    this.handleAdminBan(data,id);
                    break;
                case "admin-enter-room":
                    this.handleAdminEnterRoom(data,id,session);
                    break;
                case "admin-leave-room":
                    this.handleAdminLeaveRoom(data,id,session);
                    break;
                case "admin-dismiss-room":
                    this.handleAdminDismissRoom(data,id,session);
                    break;
                case "admin-unban":
                    this.handleAdminUnban(data,id,session);
                    break;
                case "admin-banned-list":
                    this.handleAdminBannedList(id,session);
                    break;
case "chat-message":
                    //禁言拦截：被管理员禁言的用户不能发言
                    if(mutedUserIds.contains(id)){
                        this.sendMessage(JSONUtil.toJsonStr(new Message<>(403,"muted","你已被管理员禁言，暂时无法发言")),session);
                        break;
                    }
                    //聊天频率限制：同一用户单位时间内发言条数超限则丢弃并提示
                    if(abuseGuard != null && !abuseGuard.allowChat(id)){
                        this.sendMessage(
                                JSONUtil.toJsonStr(new Message<>(429,"chat-limit","发言过于频繁，请稍后再试！")),
                                session);
                        break;
                    }
                    String raw = data.getStr("content");
                    if(raw==null||raw.trim().isEmpty()||raw.trim().length()>MAX_CHAT_LENGTH){
                        break;
                    }
//调用工具过滤敏感词
                    String safe = SensitiveWordUtil.filterMsg(raw.trim());
                    // Hutool JSONObject 使用put，不是set
                    data.put("content",safe);
                    this.sendRoomMessage(data,id);
                    //留存房间聊天记录：入库保存用户发送的原始内容（未过滤），供事后追溯与管理员查看。
                    //roomId 必须取服务端记录的房间，不能用报文里的值：否则任何登录用户都能
                    //往任意（甚至不存在的）房间伪造一条自己名下的发言记录。
                    String chatRoomId = playerRoomMap.get(id);
                    if(chatRoomId != null && isRoomMember(roomMap.get(chatRoomId),id)){
                        recordChat(id,chatRoomId,"room",raw.trim());
                    }
                    break;
                // 公共大厅聊天消息
case "public-chat-message":
                    // 禁言拦截：被管理员禁言的用户不能发言
                    if(mutedUserIds.contains(id)){
                        this.sendMessage(JSONUtil.toJsonStr(new Message<>(403,"muted","你已被管理员禁言，暂时无法发言")),session);
                        break;
                    }
                    // 聊天频率限制（公共聊天与房间聊天共用同一计数）
                    if(abuseGuard != null && !abuseGuard.allowChat(id)){
                        this.sendMessage(
                                JSONUtil.toJsonStr(new Message<>(429,"chat-limit","发言过于频繁，请稍后再试！")),
                                session);
                        break;
                    }
                    // 校验消息非空
                    String publicContent = data.getStr("content");
                    if (publicContent == null || publicContent.trim().isEmpty()
                            || publicContent.trim().length() > MAX_CHAT_LENGTH) {
                        break;
                    }
                    // 敏感词过滤
                    String safePublic = SensitiveWordUtil.filterMsg(publicContent.trim());
                    // 身份校验（防止伪造用户）
                    String senderId = id;
                    String senderNick = onlineUserNickMap.get(senderId);
                    if (senderNick == null) break;
                    // 组装公共消息
                    JSONObject publicMsg = new JSONObject();
                    publicMsg.put("type", "public-chat-message");
                    publicMsg.put("senderId", senderId);
                    publicMsg.put("senderNick", senderNick);
                    publicMsg.put("content", safePublic);
                    publicMsg.put("time", MyUtil.now());
// 全服广播
                    sendAllMessage(JSONUtil.toJsonStr(publicMsg));
                    //留存公共聊天记录：入库保存用户发送的原始内容（未过滤）
                    recordChat(senderId,null,"public",publicContent.trim());
                    break;
case "info-ready":
                    //先记录准备/取消准备状态（用于「游戏中/房间中」判定），被接受后再原样转发给对手
                    if(this.handleInfoReady(data,id)){
                        this.relayToOpponent(data,id,message);
                    }
                    break;
                case "regret":
                    if(this.relayActionRequest(data,id,"regret")){
                        this.notifySpectatorsNotice(id,nickOf(id)+" 发起悔棋请求");
                    }
                    break;
                case "regret-reject":
                    clearPendingAction(id);
                    this.relayToOpponent(data,id,message);
                    this.notifySpectatorsNotice(id,nickOf(id)+" 拒绝了悔棋请求");
                    break;
                case "peace":
                    if(this.relayActionRequest(data,id,"peace")){
                        this.notifySpectatorsNotice(id,nickOf(id)+" 请求和棋");
                    }
                    break;
                case "peace-reject":
                    clearPendingAction(id);
                    this.relayToOpponent(data,id,message);
                    this.notifySpectatorsNotice(id,nickOf(id)+" 拒绝了和棋请求");
                    break;
                case "restart":
                    if(this.relayActionRequest(data,id,"restart")){
                        this.notifySpectatorsNotice(id,nickOf(id)+" 发起重新开局");
                    }
                    break;
                case "restart-reject":
                    clearPendingAction(id);
                    this.relayToOpponent(data,id,message);
                    this.notifySpectatorsNotice(id,nickOf(id)+" 拒绝了重新开局");
                    break;
                case "start-game":
                    this.startGame(data.getStr("roomId"),id);
                    break;
                case "play":
                    this.play(data,id,session);
                    break;
                case "regret-agree":
                    this.regret(data,id);
                    break;
                case "surrender":
                    this.surrender(data,id);
                    break;
                case "peace-agree":
                    this.peaceAgree(data,id);
                    break;
                case "restart-agree":
                    this.restart(data,id);
                    break;
                //上报在线昵称
case "report-nick":
                    //前端上报当前登录用户昵称
                    String reportUid = data.getStr("userId");
                    String reportNick = data.getStr("nickName");
                    //安全校验：上报ID必须等于当前websocket连接路径ID，禁止伪造其他用户
                    if(Objects.equals(reportUid, id)){
                        if(reportNick==null||reportNick.trim().isEmpty()){
                            reportNick = "匿名用户";
                        }
                        reportNick = reportNick.trim();
                        //保留名与不可见字符：昵称会被直接展示在大厅、公共聊天和房间列表里。
                        //注册侧已禁止占用「管理员/admin」，这里必须同样拦住——否则登录后发一条
                        //report-nick 就能把自己显示成管理员；而零宽字符变体（"管理\u200B员"）
                        //在数据库里查不到同名账号，只靠「昵称是否被他人占用」是拦不住的。
                        //命中任何一项都不采纳，回退为账号的真实昵称。
                        if(MyUtil.containsInvisibleChar(reportNick)
                                || isReservedAdminNick(reportNick)
                                || isNickOwnedByOther(reportNick, id)){
                            reportNick = safeRealNameOf(id);
                        }
                        if(reportNick.length() > MAX_NICK_LENGTH){
                            reportNick = reportNick.substring(0,MAX_NICK_LENGTH);
                        }
                        String previousNick = onlineUserNickMap.get(reportUid);
                        onlineUserNickMap.put(reportUid, reportNick);
                        //昵称未变化时不重复全服广播，避免客户端高频上报造成广播放大
                        if(!Objects.equals(previousNick, reportNick)){
                            sendOnlineUserList();
                        }
                    }
                    break;
                case "heartbeat":
                    //心跳：活动时间已在进入分支前更新；再回一条心跳回执，
                    //让前端能判断「服务端是否还在响应」——进程被强杀时连接可能很久不报错，
                    //前端靠回执安静超时即可发现连接已死并立刻重连/提示。
                    //同时给登录令牌续期：页面开着（含挂机）就不会因闲置过期被踢下线
                    if(tokenStore != null){
                        tokenStore.touch(id);
                    }
                    this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"heartbeat-ack","pong")), session);
                    break;
                default:
                    //未知类型不打印原始报文，避免被用来刷日志或往日志里注入内容
                    System.out.println("忽略未知消息类型：" + brief(type) + "（报文长度 " + message.length() + "）");
            }
        }catch (Exception e){
            System.out.println("处理消息[" + type + "]时出现异常：");
            e.printStackTrace();
        }
    }

    /**
     * 日志用的短文本：截断过长内容并替换控制字符。
     * 客户端报文完全可控，直接打印会让攻击者往日志里注入内容或用超长报文把磁盘写满。
     */
    private static String brief(String text){
        if(text == null){
            return "null";
        }
        String shown = text.length() > 64 ? text.substring(0, 64) + "…" : text;
        StringBuilder sb = new StringBuilder(shown.length());
        for(int i = 0; i < shown.length(); i++){
            char c = shown.charAt(i);
            sb.append(Character.isISOControl(c) ? '?' : c);
        }
        return sb.toString();
    }

    /**
     * 判断用户是否为该房间内的棋手
     */
private boolean isRoomMember(Room room,String userId){
        if(room==null || userId==null){
            return false;
        }
        return Objects.equals(room.getPlayerId1(),userId) || Objects.equals(room.getPlayerId2(),userId);
    }

    /**
     * 按房间号取房间。房间号大多来自客户端报文，可能缺失（JSON 里没有该字段）。
     *
     * 为什么要统一走这里：roomMap 是 ConcurrentHashMap，get(null) 会直接抛
     * NullPointerException。一条 {"type":"play"} 这样不带 roomId 的报文就能触发，
     * 虽然会被 onMessage 的兜底 catch 挡住不影响服务，但每次都会打印一整段堆栈，
     * 既污染日志，也可被用来刷日志（未鉴权连接也能发）。
     */
    private static Room roomById(String roomId){
        return (roomId == null || roomId.isEmpty()) ? null : roomMap.get(roomId);
    }

/** 是否为该房间的管理员隐身观察者（供业务判断与测试使用） */
    public static boolean isAdminObserver(String roomId,String userId){
        if(roomId==null || userId==null){
            return false;
        }
        Set<String> observers = adminObservers.get(roomId);
        return observers!=null && observers.contains(userId);
    }

    /**
     * 判断 to 是否为 senderId 在该房间内的对手。
     * 用于替代直接信任客户端上报的 to 字段，避免消息被中继给无关用户。
     */
    private boolean isOpponent(Room room,String senderId,String to){
        if(room==null || senderId==null || to==null || Objects.equals(senderId,to)){
            return false;
        }
        String opponent = Objects.equals(room.getPlayerId1(),senderId) ? room.getPlayerId2() : room.getPlayerId1();
        return Objects.equals(opponent,to);
    }

    /**
     * 玩家之间的交互消息转发。
     * 只允许发送者把消息转发给与自己同房间的对手，客户端伪造的 to 一律丢弃。
     */
    private void relayToOpponent(JSONObject data,String senderId,String rawMessage){
        String to = data.getStr("to");
        String roomId = playerRoomMap.get(senderId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(!isOpponent(room,senderId,to)){
            return;
        }
this.sendMessageById(to,rawMessage);
    }

    /**
     * 当前这一局的序号；房间还没有对局记录时按 0 处理
     */
    private long versionOf(String roomId){
        Long v = boardVersionMap.get(roomId);
        return v==null ? 0L : v;
    }

    /**
     * 开新一局：局序号自增。必须在 synchronized(room) 内与棋局状态一同变更，
     * 否则可能出现「已重置棋盘但序号还没变」的窗口，让旧请求挤进新局。
     */
    private void bumpBoardVersion(String roomId){
        boardVersionMap.merge(roomId, 1L, Long::sum);
        //换局后上一局的悔棋/和棋/重开请求一律作废，避免旧请求落到新棋盘上
        clearPendingActionsOfRoom(roomId);
    }

    /**
     * 取请求里携带的局序号；旧版客户端（不带该字段）返回 -1，按兼容处理
     */
    private long requestVersion(JSONObject data){
        try{
            Object v = data.get("boardVersion");
            if(v==null){
                return -1L;
            }
            return Long.parseLong(String.valueOf(v).trim());
        }catch (Exception e){
            return -1L;
        }
    }

    /**
     * 请求已不属于当前这一局（棋局在和棋重开、双方同意重开之后已经换了一局）
     */
    private boolean isStaleRequest(JSONObject data,String roomId){
        long req = requestVersion(data);
        return req >= 0 && req != versionOf(roomId);
    }

    /**
     * 这一次结算是否属于「同一局的重复结算」（认输/求和/重开共用一套去重）。
     *
     * 只按 500ms 时间窗去重会有个副作用：和棋通过后服务端立刻开新局，
     * 新局开头的认输落在这个窗口里就会被误判成重复结算而失效。
     * 因此带上局序号判断——请求（或上一次结算）不在同一局时，说明棋局已经换过，不能挡。
     * 老客户端不带局序号，只能退回纯时间窗判断，保持兼容。
     */
    private boolean isDuplicateSettlement(String roomId,JSONObject data,long now){
        Long last = lastResetAt.get(roomId);
        if(last==null || now-last >= ACTION_DEDUP_MS){
            return false;
        }
        long req = requestVersion(data);
        if(req < 0){
            return true;
        }
        Long settledVersion = lastResetVersion.get(roomId);
        return settledVersion != null && settledVersion == versionOf(roomId);
    }

    /**
     * 请求携带的局序号是否早于当前这一局（即请求发出后棋局已经换过一局）。
     * 与 isStaleRequest 的区别：这里只认「更旧」，客户端未携带序号（-1）时按兼容放行，
     * 用于认输这类单方操作——它不需要双方回传同一个序号，只需确认没有落到新局上。
     */
    private boolean isOlderRequest(JSONObject data,String roomId){
        long req = requestVersion(data);
        return req >= 0 && req < versionOf(roomId);
    }

    /**
     * 告诉客户端「这条操作没有生效」，让它解除等待状态并给出提示，但不要动棋盘。
     * 用于请求过期（棋局已重开）或对手已离桌等无法执行的情况。
     *
     * roundOver 必须如实告诉客户端「本局到底有没有结束」：
     * 认输/求和这类操作在客户端点击后本地会先置为终局态，如果被服务端拒绝而客户端又不知道
     * 这一局其实还在进行（例如对手的和棋先生效并已开新局），界面会一直停在终局态无法继续，
     * 因此这里不能只给一句文案，必须带一个可机读的标记。
     */
    private void notifyActionInvalid(String userId,String reason,boolean roundOver){
        JSONObject msg = new JSONObject();
        msg.put("code",400);
        msg.put("type","action-invalid");
        msg.put("content",reason);
        msg.put("roundOver",roundOver);
        this.sendMessageById(userId,JSONUtil.toJsonStr(msg));
    }

    /**
     * 新一局开始时的下发内容：除了本人颜色，还带上当前局序号。
     * 客户端保存该序号，在认输等单方操作里回传，服务端据此丢弃「上一局遗留、延迟到达」的请求，
     * 避免上一局的认输落到刚开的新局上直接判负。
     */
    private String newGameMessage(String type,String roomId,long version,String color){
        JSONObject msg = new JSONObject();
        msg.put("code",200);
        msg.put("type",type);
        msg.put("content",color);
        msg.put("boardVersion",version);
        return JSONUtil.toJsonStr(msg);
    }

    /**
     * 转发「悔棋 / 求和 / 重开」这类需要对手同意的请求。
     * 与普通转发不同的是会带上当前局序号，供对手「同意」时回传，
     * 服务端据此拦掉跨局生效的过期请求。
     *
     * 这里刻意不在转发阶段按房间状态拦截：转发本身不改变任何状态，
     * 真正的拦截统一放在对应的 agree 处理里（那里同时校验状态与局序号），
     * 这样「发起请求」和「同意生效」只有一处判定，不会出现两个地方规则不一致。
     */
    private boolean relayActionRequest(JSONObject data,String senderId,String type){
        String roomId = playerRoomMap.get(senderId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        String to = data.getStr("to");
        if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return false;
        }
        long version;
        synchronized (room){
            version = versionOf(roomId);
        }
        //登记这条待响应请求：只有被请求方在有效期内点「同意」才生效。
        //否则任何一方都能单方面发一条 agree 把对手的制胜手悔掉、强制和棋或强制重开。
        pendingActions.put(actionKey(roomId,type),new PendingAction(type,senderId,to,version,
                System.currentTimeMillis()+ACTION_REQUEST_TTL_MS));
        //沿用原始报文的字段，补上局序号后转发；前端按 type 分发，结构不变
        data.put("type",type);
        data.put("boardVersion",version);
        this.sendMessageById(to,JSONUtil.toJsonStr(data));
        return true;
    }

    /**
     * 清除本房间的待响应请求：拒绝、离开房间时调用，避免旧请求残留。
     * 房间号取服务端映射，不用报文里的值，防止误删其它房间的待办。
     */
    private void clearPendingAction(String userId){
        String roomId = playerRoomMap.get(userId);
        if(roomId != null){
            clearPendingActionsOfRoom(roomId);
        }
    }

    /** 清除某房间全部类型的待响应请求 */
    private void clearPendingActionsOfRoom(String roomId){
        pendingActions.remove(actionKey(roomId,"regret"));
        pendingActions.remove(actionKey(roomId,"peace"));
        pendingActions.remove(actionKey(roomId,"restart"));
    }

    private static String actionKey(String roomId,String type){
        return roomId + "|" + type;
    }

    /**
     * 校验「同意」是否对得上一条真实存在、未过期、且由当前对手发起的请求。
     * 通过后由调用方在真正执行结算时清除，避免同一条请求被重复同意。
     */
    private boolean hasPendingAction(String roomId,String type,String senderId,String counterpart){
        String key = actionKey(roomId,type);
        PendingAction pending = pendingActions.get(key);
        if(pending == null){
            return false;
        }
        if(System.currentTimeMillis() > pending.expireAt){
            pendingActions.remove(key,pending);
            return false;
        }
        //同意方必须是当初的被请求方，且报文里的对手必须是当初的发起方
        boolean match = type.equals(pending.type)
                && Objects.equals(senderId,pending.to)
                && Objects.equals(counterpart,pending.from);
        if(match){
            //一次性消费：同一条请求只能被同意一次，防止重放
            pendingActions.remove(key,pending);
        }
        return match;
    }

/**
     * 记录房内成员的准备/取消准备状态：双方都准备后在线状态才记为「游戏中」，否则为「房间中」。
     * 返回 false 表示该消息不应转发给对手（不在房间、或对局已开始后试图取消准备）。
     */
    private boolean handleInfoReady(JSONObject data,String senderId){
        String roomId = playerRoomMap.get(senderId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room == null){
            return false;
        }
        boolean ready = Boolean.TRUE.equals(data.getBool("content"));
        boolean changed;
        //这次准备是否直接促成了新一局开始（开局消息在锁外统一发送）
        boolean started = false;
        synchronized (room){
            //对局已经真正开始后不允许再取消准备，否则会把进行中的对局状态改回「房间中」
            if(!ready && room.getSteps()!=null){
                return false;
            }
            if(Objects.equals(room.getPlayerId1(),senderId)){
                changed = room.isReadyId1() != ready;
                room.setReadyId1(ready);
            }else if(Objects.equals(room.getPlayerId2(),senderId)){
                changed = room.isReadyId2() != ready;
                room.setReadyId2(ready);
}else{
                return false;
            }
            //房间状态与准备状态保持同一口径：
            //当前没有进行中的对局（首局或上一局已结算）时，双方都准备就由服务端直接开新一局，
            //对局结束后不需要再点「重开」；只有一方准备则回到「房间中」。
            //对局进行中（steps 非空）状态保持「游戏中」，准备状态不会回退。
            boolean hasOpponent = room.getPlayerId2()!=null && !EMPTY_PLAYER.equals(room.getPlayerId2());
            if(hasOpponent && room.getSteps()==null){
                if(room.isReadyId1() && room.isReadyId2()){
                    beginNewGameLocked(room);
                    started = true;
                }else{
                    room.setState("room");
                }
            }
        }
        if(changed){
            addRoomEvent(room,"系统消息："+displayNameOf(senderId)+(ready ? " 已准备" : " 取消准备"));
            //准备状态变化同步给正在巡查该房间的管理员（昵称旁的准备标签）
            notifyAdminRoomView(room);
            //观战者昵称旁的准备状态也必须实时更新（观战只读，仅同步房间视图）
            broadcastSpectatorUpdate(room);
        }
//准备状态同时影响在线列表状态与大厅卡片（游戏中/房间中），统一刷新一次。
        //已经开局的由 announceNewGame 统一刷新，避免同一次准备广播两遍
        if(started){
            announceNewGame(room);
        }else if(changed){
            refreshLobby();
        }
        //重复上报同一个准备状态（连点、网络重发）不产生任何广播、也不转发给对手：
        //这类请求不改变任何状态，若每次都刷新在线列表，高频发送一条空操作就能被放大成全服广播
        return changed || started;
    }

    /**
     * 房间对外视图：只包含前端需要的字段，不下发房间内的完整步数
     */
private JSONObject roomView(Room room){
        JSONObject view = new JSONObject();
        //头像在房间锁外解析（可能查库），避免持锁做 IO 影响房内操作
        String avatar1 = avatarService == null ? AvatarService.DEFAULT_AVATAR : avatarService.avatarOf(room.getPlayerId1());
        String avatar2 = avatarService == null ? AvatarService.DEFAULT_AVATAR : avatarService.avatarOf(room.getPlayerId2());
        //房间状态由多个连接线程并发修改，统一在房间锁内读取，保证下发视图一致
        synchronized (room){
            view.put("id",room.getId());
            view.put("playerId1",room.getPlayerId1());
            view.put("playerId2",room.getPlayerId2());
            view.put("playerName1",room.getPlayerName1());
            view.put("playerName2",room.getPlayerName2());
            //双方头像：大厅卡片按用户展示各自的头像，而不是所有人共用一张
            view.put("playerAvatar1",avatar1);
            view.put("playerAvatar2",avatar2);
            view.put("state",room.getState());
            view.put("start",room.getStart());
            //房间号：大厅卡片、房间内标题栏与按号搜索都读取该字段
            view.put("roomNo",room.getRoomNo());
            //房间是否公开，以及当前观战人数
            view.put("open",room.isOpen());
            //是否真正开局（有棋局数据）
            view.put("started",room.getSteps()!=null);
            view.put("spectatorCount",room.getSpectators()==null ? 0 : room.getSpectators().size());
            //准备状态：房内昵称旁与管理员巡查视图都读取该字段
            view.put("readyId1",room.isReadyId1());
            view.put("readyId2",room.isReadyId2());
            //实时比分：房间内（玩家、观战者、巡查管理员）统一按这一份数据展示
            view.put("scoreId1",room.getScoreId1());
            view.put("scoreId2",room.getScoreId2());
            //当前局序号：客户端据此判断自己手上的操作请求是否还属于这一局
            view.put("boardVersion",versionOf(room.getId()));
            //房内成员是否为管理员：房主据此隐藏「踢出玩家」，服务端同时兜底拒绝
            view.put("playerAdmin1",moderationService != null && moderationService.isAdminUserId(room.getPlayerId1()));
            view.put("playerAdmin2",moderationService != null && moderationService.isAdminUserId(room.getPlayerId2()));
        }
        return view;
    }

/**
     * 大厅在线房间视图：
     * 所有房间（含私人房间）都下发给所有用户，私人房间不再从大厅列表中剔除，
     * 仅由前端在大厅卡片上以「私人房间」状态标识区分；非受邀者的加入仍由 doJoinRoom 拒绝
     */
    private List<JSONObject> onlineRoomViews(String viewerId){
        List<JSONObject> views = new ArrayList<>();
        for(Room room : roomMap.values()){
            JSONObject view = roomView(room);
            //被房主踢出过的用户：大厅卡片直接标记，点击加入时不再弹密码框，直接提示无法加入
            view.put("kicked",room.getKickedIds()!=null && room.getKickedIds().contains(viewerId));
            views.add(view);
        }
        return views;
    }

    /**
     * 给每个连接下发同一份在线房间列表（所有房间对所有人可见）
     */
    private void broadcastOnlineRooms(){
        for(Map.Entry<String,Session> entry : sessionMap.entrySet()){
            this.sendMessage(
                    JSONUtil.toJsonStr(new Message<>(200,"online-rooms",onlineRoomViews(entry.getKey()))),
                    entry.getValue());
        }
    }

    /**
     * 房间或用户状态发生变化后刷新大厅：
     * 在线用户列表（含状态与房主标记）+ 各连接可见的房间列表
     */
    private void refreshLobby(){
        cleanupOrphanRooms();
        sendOnlineUserList();
        broadcastOnlineRooms();
    }

    /**
     * 清理没有任何用户再关联的“孤儿房间”。
     * 建房/退房/断线重连并发时存在短暂竞态：房间已写入 roomMap，但 playerRoomMap 已被
     * 另一次退房/重连改写，会导致房间永远留在内存里（稳定性用例会判定为泄漏）。
     * 这里以 playerRoomMap 的当前归属为准，房间内已无有效玩家时直接回收并通知巡查管理员。
     */
    private void cleanupOrphanRooms(){
        List<Room> rooms = new ArrayList<>(roomMap.values());
        for(Room room : rooms){
            String id = room.getId();
            String p1 = room.getPlayerId1();
            if(p1 == null || p1.trim().isEmpty() || EMPTY_PLAYER.equals(p1)){
                if(roomMap.remove(id) != null){
                    clearPendingActionsOfRoom(id);
                    adminObservers.remove(id);
                    notifyAdminRoomClosed(id,"房内已无玩家，房间已自动回收");
                }
                continue;
            }
            String p2 = room.getPlayerId2();
            boolean hasPlayer2 = p2 != null && !p2.trim().isEmpty() && !EMPTY_PLAYER.equals(p2);
            boolean ownerMappedHere = Objects.equals(room.getId(),playerRoomMap.get(p1));
            boolean guestMappedHere = hasPlayer2 && Objects.equals(room.getId(),playerRoomMap.get(p2));
            if(!ownerMappedHere && !guestMappedHere){
                if(roomMap.remove(id) != null){
                    clearPendingActionsOfRoom(id);
                    adminObservers.remove(id);
                    notifyAdminRoomClosed(id,"房内已无玩家，房间已自动回收");
                }
            }
        }
    }

    //处理重新开局
    private void restart(JSONObject data,String senderId){
        String roomId = data.getStr("roomId");
        //发起人身份以当前连接为准，避免伪造 from
        String from = senderId;
        String to = data.getStr("to");
        Room room = roomById(roomId);
        if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return;
        }
        //必须确有对方发起的重开请求，且未过期、未跨局
        if(!hasPendingAction(roomId,"restart",senderId,to)){
            notifyActionInvalid(senderId,"对方并未发起重开请求，或请求已失效",false);
            return;
        }
        //房间内的对局数据会被两个玩家的线程同时读写，统一用房间对象加锁
        String staleReason = null;
        boolean roundOver = false;
        long newVersion = 0L;
        synchronized (room){
            //重开只在对局进行中有效：对方已经认输/和棋结算后再执行重开，
            //会把已经结束的房间强行拉回「对局中」，一端以为在下新局、另一端以为已认输
            if(!"playing".equals(room.getState())){
                staleReason = "对局已结束，重新开局请求已失效";
                roundOver = true;
            }else if(isStaleRequest(data,roomId)){
                //这条「同意」针对的是上一局的请求（期间已经和棋重开或双方重开过）
                staleReason = "该请求已失效：棋局已经重新开始";
            }else{
            //双方几乎同时同意重开时只重置一次：连续重置两次会重新随机两次颜色，
            //发起方收到 restart-agree、响应方收到 start-game，两端可能拿到同一种颜色而无法对局
            long now = System.currentTimeMillis();
            if(isDuplicateSettlement(roomId, data, now)){
                return;
            }
            lastResetAt.put(roomId, now);
            //结算发生在哪一局：必须在棋局重置（局序号自增）之前记录
            lastResetVersion.put(roomId, versionOf(roomId));
            //丢弃数据
            room.setSteps(new ArrayList<>());
            //新开局时间
            room.setStart(MyUtil.now());
room.setWinCheck(0);
            room.setState("playing");
            //重开是双方同意的，视为双方已准备，在线状态保持「游戏中」
            room.setReadyId1(true);
            room.setReadyId2(true);
            //重新开局重新随机分配黑白棋子
            assignColors(room);
            //进入新的一局：上一局发出的悔棋/求和请求随之作废
            bumpBoardVersion(roomId);
            newVersion = versionOf(roomId);
            }
        }
        if(staleReason!=null){
            notifyActionInvalid(senderId, staleReason, roundOver);
            return;
        }
System.out.println("房间："+roomId+"\t重新开局");
        //按身份下发各自颜色：响应方（from）收到 start-game、发起方（to）收到 restart-agree；
        //颜色取本人真实颜色（color1 属于 playerId1、color2 属于 playerId2），避免按 from/to 错配导致双方颜色对调
        String fromColor = Objects.equals(from, room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        String toColor = Objects.equals(to, room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        this.sendMessageById(from,newGameMessage("start-game",roomId,newVersion,fromColor));
        this.sendMessageById(to,newGameMessage("restart-agree",roomId,newVersion,toColor));
//观战者按新的对局重置棋盘，并看到重开已获同意
        sendSpectatorReset(room);
        //管理员巡查视图同步清空上一局（含五连高亮）并按新局步数重绘，避免停留在旧画面
        notifyAdminRoomView(room);
        //重开后大厅卡片回到「游戏中」
        refreshLobby();
        this.notifySpectatorsNotice(senderId, nickOf(senderId)+" 同意重新开局，新的一局开始");
    }
//处理和棋：和棋通过后自动重新开局并向双方提示
    private void peaceAgree(JSONObject data,String senderId){
        String roomId = data.getStr("roomId");
        String to = data.getStr("to");
        Room room = roomById(roomId);
        if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return;
        }
        //必须确有对方发起的和棋请求，否则单方即可强制判和并换局
        if(!hasPendingAction(roomId,"peace",senderId,to)){
            notifyActionInvalid(senderId,"对方并未发起和棋请求，或请求已失效",false);
            return;
        }
        String id1 = null,id2 = null,color1 = null,color2 = null;
        String staleReason = null;
        boolean roundOver = false;
        long newVersion = 0L;
        synchronized (room){
            //和棋只在对局进行中有效：终局后再同意和棋会多写一条对局记录并重置棋盘
            if(!"playing".equals(room.getState())){
                staleReason = "对局已结束，和棋请求已失效";
                roundOver = true;
            }else if(isStaleRequest(data,roomId)){
                //对方发出和棋请求之后，棋局已经重开过一局：这条「同意」不能再结算新局
                staleReason = "该请求已失效：棋局已经重新开始";
            }else{
            //双方几乎同时同意和棋时只处理第一次，避免同一局连写两条记录、棋盘被连着重置两次
            long now = System.currentTimeMillis();
            if(isDuplicateSettlement(roomId, data, now)){
                return;
            }
            lastResetAt.put(roomId, now);
            //结算发生在哪一局：必须在棋局重置（局序号自增）之前记录
            lastResetVersion.put(roomId, versionOf(roomId));
            //不足 3 回合的对局不保存记录
            if(room.getSteps()!=null && room.getSteps().size()>=MIN_SAVE_STEPS){
                System.out.println("开始保存对局记录···");
                saveRecord(room,0,"peace");
            }
            //和棋通过后直接重开一局：清空步数、重新随机分配颜色、回到对局中
            id1 = room.getPlayerId1();
            id2 = room.getPlayerId2();
            assignColors(room);
            color1 = room.getColor1();
            color2 = room.getColor2();
            room.setSteps(new ArrayList<>());
            room.setStart(MyUtil.now());
            room.setWinCheck(0);
            room.setState("playing");
            room.setReadyId1(true);
            room.setReadyId2(true);
            //和棋即换新局：上一局发出的悔棋/求和请求随之作废
            bumpBoardVersion(roomId);
            newVersion = versionOf(roomId);
            }
        }
        if(staleReason!=null){
            notifyActionInvalid(senderId, staleReason, roundOver);
            return;
        }
        //通知双方：本局和棋，已自动重新开局（各自拿到属于自己的颜色）
        this.sendMessageById(id1,newGameMessage("peace-restart",roomId,newVersion,color1));
        this.sendMessageById(id2,newGameMessage("peace-restart",roomId,newVersion,color2));
//观战者按新的对局重置棋盘，并看到和棋已自动重开
        sendSpectatorReset(room);
        //管理员巡查视图同步清空上一局并按新局重绘
        notifyAdminRoomView(room);
        refreshLobby();
        this.notifySpectatorsNotice(senderId, "双方和棋，已自动重新开局");
    }
    //认输
    private void surrender(JSONObject data,String senderId){
        String roomId = data.getStr("roomId");
        String to = data.getStr("to");
        Room room = roomById(roomId);
if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return;
        }
//未开局时没有可保存的棋谱：仍按原逻辑把认输通知转发给对手，但不写入对局记录
        Integer winId;
        try{
            winId = Integer.valueOf(to);
        }catch (Exception e){
            winId = null;
        }

        String staleReason = null;
        boolean roundOver = false;
        synchronized (room){
            //认输只在对局进行中有效：双方同时认输时只处理第一次，
            //否则同一局会写入两条对局记录，双方还会各自收到一次「对手认输」
            if(!"playing".equals(room.getState())){
                staleReason = "对局已结束，本次认输未生效";
                roundOver = true;
            }else if(isOlderRequest(data,roomId)){
                //这条认输是上一局发出的，只是到达得晚：那时棋局已经结算并开了新局，
                //若照着执行会直接判新局输赢，必须丢弃
                staleReason = "上一局已经结束，本次认输未生效";
            }else{
                //结算类操作（认输 / 和棋）共用同一个去重时间戳：一方认输、另一方同时同意和棋时，
                //后到的那次结算会被挡掉，避免同一局同时写入「认输」和「和棋」两条记录。
                //去重时同时比对「上次结算发生在哪一局」：和棋通过后房间立刻开新局，
                //若只看时间窗，新局开头的认输会被误判成「这一局已结算过」。
                long now = System.currentTimeMillis();
                if(isDuplicateSettlement(roomId, data, now)){
                    staleReason = "这一局已经结算过了（对手的和棋或认输已先生效）";
                }else{
                    lastResetAt.put(roomId, now);
                    //认输不会自增局序号（下一局开始时才自增），这里记录当前局，
                    //用于把「同一局的重复认输」与「新一局的认输」区分开
                    lastResetVersion.put(roomId, versionOf(roomId));
                    if(room.getSteps()!=null){
                        //如果小于3回合 丢弃数据
                        if(room.getSteps().size()<MIN_SAVE_STEPS){
                            room.setSteps(null);
                            //这一局没有写成记录：连同上一位对手留下的终局快照一起清掉，
                            //否则重连/观战会把上一局的完整棋盘当成当前局面下发，界面看起来像对局还在进行
                            room.setFinalSteps(null);
                            room.setState("room");
                        }else{
                            //保存数据
                            System.out.println("开始保存对局记录···");
                            //认输即分出胜负，胜方计入本房间实时比分；记录没写成则不计分
                            if(saveRecord(room,winId,"surrender")){
                                addScore(room,winId);
                            }
                            room.setState("room");
                        }
                        //认输后本局结束，双方需重新准备才能再开一局
                        room.setReadyId1(false);
                        room.setReadyId2(false);
                    }
                }
            }
        }
        if(staleReason!=null){
            notifyActionInvalid(senderId, staleReason, roundOver);
            return;
        }
this.sendMessageById(to,JSONUtil.toJsonStr(data));
        sendSpectatorReset(room);
        //认输后双方准备状态被重置，巡查视图、在线列表与大厅卡片立即回落到「房间中/未准备」；
        //观战者昵称旁的准备标签同样要同步
        broadcastSpectatorUpdate(room);
        notifyAdminRoomView(room);
        refreshLobby();
        //观战者也能看到是谁认输
        this.notifySpectatorsNotice(senderId, nickOf(senderId)+" 认输");
        //认输的一方不会收到 surrender 回执，这里统一通知双方回到准备状态：
        //双方再次准备即自动开始下一局，不需要再点「重开」
        sendRoundReset(room);
    }

    //悔棋
    private void regret(JSONObject data,String senderId){
        String roomId = data.getStr("roomId");
        String to = data.getStr("to");
        Room room = roomById(roomId);
        if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return;
        }
        //必须确有对方发起的悔棋请求，否则单方即可撤掉对手的制胜手
        if(!hasPendingAction(roomId,"regret",senderId,to)){
            notifyActionInvalid(senderId,"对方并未发起悔棋请求，或请求已失效",false);
            return;
        }
        int backNum;
        try{
            backNum = Integer.parseInt(data.getStr("backNum"));
        }catch (Exception e){
            return;
        }
        if(backNum!=1 && backNum!=2){
            return;
        }
        int removed = 0;
        String staleReason = null;
        boolean roundOver = false;
        synchronized (room){
            //悔棋只在对局进行中有效：终局后再回退会把已经判定胜负的棋盘改乱
            if(!"playing".equals(room.getState()) || room.getSteps()==null){
                staleReason = "对局已结束，悔棋请求已失效";
                roundOver = true;
            }else if(isStaleRequest(data,roomId)){
                //关键：对方发出悔棋请求后，棋局可能已经和棋重开/双方重开过。
                //不校验局序号就会把「上一局的悔棋」作用到刚开局的新棋盘上。
                staleReason = "该悔棋请求已失效：棋局已经重新开始";
            }else{
                long now = System.currentTimeMillis();
                Long last = lastRegretAt.get(roomId);
                if(last!=null && now-last < ACTION_DEDUP_MS){
                    //双方几乎同时同意同一次悔棋：只应用第一次，第二次回退 0 步
                    removed = 0;
                }else{
                    lastRegretAt.put(roomId, now);
                    int size = room.getSteps().size();
                    //剩余步数不足时不做回退（避免下标越界），同样按 0 步回给客户端
                    removed = Math.min(backNum, size);
                    for(int i=0; i<removed; i++){
                        room.getSteps().remove(room.getSteps().size()-1);
                    }
                }
            }
        }
        if(staleReason!=null){
            //只告诉同意方「这次悔棋没有执行」，不改动棋盘：
            //若这里再回一个 removed=0，前端会据此重算先后手，反而把新开的局搅乱
            notifyActionInvalid(senderId, staleReason, roundOver);
            return;
        }
        //回退步数以服务端为准下发：发起方与响应方按同一个数字回退，
        //避免双方同时同意时一端退两步、另一端退四步导致棋盘与服务端不一致
        this.sendMessageById(to,
                JSONUtil.toJsonStr(new Message<>(200,"regret-agree",removed)));
        this.sendMessageById(senderId,
                JSONUtil.toJsonStr(new Message<>(200,"regret-applied",removed)));
        //观战者也能看到悔棋已获同意
        this.notifySpectatorsNotice(senderId, nickOf(senderId)+" 同意悔棋");
    }

    /**
     * 下棋指令：落子范围、重复落子、先后手与胜负都由服务端判定
     */
    private void play(JSONObject data,String senderId,Session session){
        String to = data.getStr("to");
        Room room = roomById(data.getStr("roomId"));
        if(!isRoomMember(room,senderId) || !isOpponent(room,senderId,to)){
            return;
        }
        Integer x,y,playerId;
        try{
            x = Integer.valueOf(data.getStr("x"));
            y = Integer.valueOf(data.getStr("y"));
            //落子方以当前连接身份为准，客户端上报的 from 不作为记录依据
            playerId = Integer.valueOf(senderId);
        }catch (Exception e){
            //坐标或棋手id非法，直接丢弃
            return;
        }
        //棋盘范围校验
        if(x<0 || x>=BOARD_SIZE || y<0 || y>=BOARD_SIZE){
            return;
        }
        synchronized (room){
            if(room.getSteps()==null){
                return;
            }
            //棋盘写满后不再接受落子
            if(room.getSteps().size() >= MAX_STEPS){
                return;
            }
            //同一位置不允许重复落子
            for(Step step : room.getSteps()){
                if(x.equals(step.getX()) && y.equals(step.getY())){
                    return;
                }
            }
            //先后手由服务端判定：开局黑棋先手，之后双方交替
            if(!Objects.equals(expectedPlayer(room),senderId)){
                //不是该自己落子：通知客户端撤回本次落子并丢弃
                JSONObject reject = new JSONObject();
                reject.put("type","play-reject");
                reject.put("x",x);
                reject.put("y",y);
                reject.put("content","还没有轮到你落子");
                this.sendMessage(JSONUtil.toJsonStr(reject),session);
                return;
            }
            //棋子颜色以服务端记录为准，避免客户端伪造
            String color = Objects.equals(room.getPlayerId1(),senderId)
                    ? room.getColor1() : room.getColor2();
room.getSteps().add(new Step(playerId,x,y,color));
            //落子颜色以服务端记录为准后再转发，保证双方看到的棋子一致
            data.put("color",color);
            //判定胜负
if(hasFiveInRow(room.getSteps(),x,y,playerId)){
                System.out.println("房间："+room.getId()+"\t棋手："+senderId+" 连成五子");
                //先取出连五坐标（saveRecord 会清空房间步数），供双方与观战者按同一份数据渲染
                List<int[]> line = fiveInRowLine(room.getSteps(),x,y,playerId);
                //先转发这最后一手：保证双方（含落败方）与观战者的棋盘都显示最后一颗落子
                this.sendMessageById(to,JSONUtil.toJsonStr(data));
                this.notifySpectators(room,JSONUtil.toJsonStr(data));
                room.setState("over");
                //只有真正落库的一局才计分：记录没写成时不能加分，否则房内比分与历史战绩会对不上
                if(saveRecord(room,playerId,"normal")){
                    addScore(room,playerId);
                }
                //一局结束后双方回到未准备：再次同时准备即自动开始下一局，不再需要点「重开」；
                //同时避免在线状态继续显示“游戏中”
room.setReadyId1(false);
                room.setReadyId2(false);
                //终局状态与准备状态同步给观战者，避免其昵称旁仍显示「已准备」
                broadcastSpectatorUpdate(room);
                //连同五子连线坐标一起下发：双方与观战者据同一份数据渲染，端上效果一致
                this.sendGameOver(room,playerId,x,y,line);
                //结算结果下发后再把两端界面退回准备状态（双方各自按新颜色重新准备）
                sendRoundReset(room);
                return;
            }
            //棋盘写满仍未连成五子则判和
            if(room.getSteps().size() >= MAX_STEPS){
                System.out.println("房间："+room.getId()+"\t棋盘已写满，判为和棋");
                //满盘判和同样先转发最后一手，双方棋盘保持完整
                this.sendMessageById(to,JSONUtil.toJsonStr(data));
                this.notifySpectators(room,JSONUtil.toJsonStr(data));
room.setState("over");
                saveRecord(room,0,"peace");
                room.setReadyId1(false);
                room.setReadyId2(false);
                broadcastSpectatorUpdate(room);
                this.sendGameOver(room,0,x,y,null);
                sendRoundReset(room);
                return;
            }
        }
        //转发给对手
        this.sendMessageById(to,JSONUtil.toJsonStr(data));
        //观战者同步看到落子
        this.notifySpectators(room,JSONUtil.toJsonStr(data));
    }

/**
     * 通知双方对局结果：winId 为获胜方，0 表示和棋；line 为连成五子的坐标（无连五时为 null）
     */
    private void sendGameOver(Room room,Integer winId,int x,int y,List<int[]> line){
        JSONObject over = new JSONObject();
        over.put("type","game-over");
        over.put("winId",winId);
        over.put("x",x);
        over.put("y",y);
        if(line != null){
            cn.hutool.json.JSONArray arr = new cn.hutool.json.JSONArray();
            for(int[] cell : line){
                JSONObject point = new JSONObject();
                point.put("x",cell[0]);
                point.put("y",cell[1]);
                arr.put(point);
            }
            over.put("line",arr);
        }
        String json = JSONUtil.toJsonStr(over);
        this.sendMessageById(room.getPlayerId1(),json);
        this.sendMessageById(room.getPlayerId2(),json);
//观战者同步收到对局结果
        this.notifySpectators(room,json);
        addRoomEvent(room,"系统消息：对局结束，"+(winId == 0 ? "本局和棋" : displayNameOf(String.valueOf(winId))+" 获胜"));
        //终局后准备状态被重置：巡查视图、在线列表与大厅卡片立即从「游戏中」回落到「房间中」
        notifyAdminRoomView(room);
        refreshLobby();
    }

    /**
     * 一局结束后通知双方：本局已结算，双方准备状态已清零，再次同时准备即自动开始下一局。
     * 与 game-over / surrender 分开发送——那两个消息负责展示胜负结果，
     * 这条只负责把两端界面从「对局中」退回「准备」状态，因此认输、五连、满盘判和共用同一套收尾。
     */
    private void sendRoundReset(Room room){
        if(room==null){
            return;
        }
        JSONObject reset = new JSONObject();
        reset.put("type","round-reset");
        reset.put("roomView",roomView(room));
        String json = JSONUtil.toJsonStr(reset);
        this.sendMessageById(room.getPlayerId1(),json);
        this.sendMessageById(room.getPlayerId2(),json);
    }

    /**
     * 累加本房间的实时比分：只给真正结算的一局加分，和棋（winId=0）不计分。
     * 与对局记录保持同一口径——不足 3 回合被判无效、未写入记录的对局同样不计分，
     * 因此调用点紧跟在 saveRecord 之后；比分必须在房间视图广播之前改好，
     * 否则玩家与观战者会拿到新旧不一致的比分。
     */
    private void addScore(Room room,Integer winId){
        if(room==null || winId==null || winId == 0){
            return;
        }
        String winner = String.valueOf(winId);
        synchronized (room){
            if(Objects.equals(room.getPlayerId1(),winner)){
                room.setScoreId1(room.getScoreId1()+1);
            }else if(Objects.equals(room.getPlayerId2(),winner)){
                room.setScoreId2(room.getScoreId2()+1);
            }
        }
    }

    /**
     * 比分只对「当前这一对玩家」有意义：有人离开、换人或被踢时清零，
     * 避免把与上一个对手的战绩带到下一个对手身上。
     * 调用点同样必须在房间锁内（或房间已无人竞争时）。
     */
    private void resetScore(Room room){
        if(room==null){
            return;
        }
        room.setScoreId1(0);
        room.setScoreId2(0);
    }

    /**
     * 当前该谁落子：开局由黑棋先手，之后双方交替
     */
    private String expectedPlayer(Room room){
        List<Step> steps = room.getSteps();
        if(steps==null || steps.isEmpty()){
            return blackPlayer(room);
        }
        Step last = steps.get(steps.size()-1);
        return Objects.equals(String.valueOf(last.getPlayer()),room.getPlayerId1())
                ? room.getPlayerId2() : room.getPlayerId1();
    }

    /**
     * 执黑的棋手
     */
    private String blackPlayer(Room room){
        return "black".equals(room.getColor1()) ? room.getPlayerId1() : room.getPlayerId2();
    }

    /**
     * 开局随机分配黑白棋子，并记录到房间
     */
    private void assignColors(Room room){
        Integer random = new Random().nextInt(2);
        room.setColor1(random==0?"white":"black");
        room.setColor2(random==0?"black":"white");
    }

/**
     * 连成五子的坐标列表：用于双方与观战者按同一份数据绘制终局高亮，保证各端棋盘效果一致
     * 未连成五子时返回 null
     */
    private List<int[]> fiveInRowLine(List<Step> steps,int x,int y,Integer playerId){
        int[][] directions = {{1,0},{0,1},{1,1},{1,-1}};
        for(int[] direction : directions){
            int dx = direction[0];
            int dy = direction[1];
            int neg = countStones(steps,x,y,playerId,-dx,-dy);
            int pos = countStones(steps,x,y,playerId,dx,dy);
            if(neg + 1 + pos >= 5){
                List<int[]> line = new ArrayList<>();
                for(int k = neg; k >= 1; k--){
                    line.add(new int[]{x - dx*k, y - dy*k});
                }
                line.add(new int[]{x,y});
                for(int k = 1; k <= pos; k++){
                    line.add(new int[]{x + dx*k, y + dy*k});
                }
                return line;
            }
        }
        return null;
    }

    /**
     * 判断这一步是否连成五子（从落子点向四个方向统计）
     */
    private boolean hasFiveInRow(List<Step> steps,int x,int y,Integer playerId){
        int[][] directions = {{1,0},{0,1},{1,1},{1,-1}};
        for(int[] direction : directions){
            int count = 1
                    + countStones(steps,x,y,playerId,direction[0],direction[1])
                    + countStones(steps,x,y,playerId,-direction[0],-direction[1]);
            if(count >= 5){
                return true;
            }
        }
        return false;
    }

    private int countStones(List<Step> steps,int x,int y,Integer playerId,int dx,int dy){
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while(hasStone(steps,cx,cy,playerId)){
            count++;
            cx += dx;
            cy += dy;
        }
        return count;
    }

    private boolean hasStone(List<Step> steps,int x,int y,Integer playerId){
        for(Step step : steps){
            if(step.getX()==x && step.getY()==y && Objects.equals(step.getPlayer(),playerId)){
                return true;
            }
        }
        return false;
    }

    //开始游戏
    private void startGame(String roomId,String senderId){
        Room room = roomById(roomId);
        if(!isRoomMember(room,senderId)){
            return;
        }
        synchronized (room){
            if(room.getPlayerId1()==null || room.getPlayerId2()==null
                    || EMPTY_PLAYER.equals(room.getPlayerId2())){
                return;
            }
            //对局已开始后重复收到 start-game（双方客户端同时判断“都准备好了”，或旧版客户端仍主动发起）
            //时直接忽略，否则第二次会把刚开好的棋局清空并重新随机颜色，造成双方棋盘/颜色错乱
            if(room.getSteps()!=null){
                return;
            }
            //必须有服务端记录的双人准备状态，防止单个客户端单独把对方拉入对局
            if(!room.isReadyId1() || !room.isReadyId2()){
                this.sendMessageById(senderId,JSONUtil.toJsonStr(
                        new Message<>(400,"start-game-fail","双方都准备后才能开始对局")));
                return;
            }
            beginNewGameLocked(room);
        }
        announceNewGame(room);
    }

    /**
     * 真正开始新的一局：清空棋盘、随机分配黑白、重置结算状态并自增局序号。
     * 调用方必须已持有房间锁——棋盘与局序号要和准备状态一起原子地变更，
     * 否则会出现「已经开局但局序号还没变」的窗口，让上一局的悔棋/求和请求落到新局上。
     */
    private void beginNewGameLocked(Room room){
        room.setSteps(new ArrayList<>()); //初始化
        room.setFinalSteps(null);
        room.setStart(MyUtil.now());
        room.setWinCheck(0);
        //能开局说明双方都已准备，据此记录准备状态
        room.setReadyId1(true);
        room.setReadyId2(true);
        room.setState("playing");
        //重新随机分配黑白棋子，颜色由服务端记录
        assignColors(room);
        //新的一局开始：局序号自增，上一局残留的悔棋/求和请求随之作废
        bumpBoardVersion(room.getId());
    }

    /**
     * 新一局开始的统一广播：双方各自拿到自己的颜色，观战者与巡查管理员重置棋盘。
     * 首发开局与「对局结束后双方再次准备」都走这里，保证两条入口的下发内容完全一致。
     */
    private void announceNewGame(Room room){
        if(room==null){
            return;
        }
        String id1,id2,color1,color2;
        long version;
        synchronized (room){
            //开局与广播之间若有结算插入（例如对手恰好在那一瞬认输），这一局已经不存在了：
            //此时不能再下发「开局」，否则两端会显示一局服务端并不存在的对局——棋盘能点、落子全被丢弃
            if(!"playing".equals(room.getState()) || room.getSteps()==null){
                return;
            }
            id1 = room.getPlayerId1();
            id2 = room.getPlayerId2();
            color1 = room.getColor1();
            color2 = room.getColor2();
            version = versionOf(room.getId());
        }
        this.sendMessageById(id1,newGameMessage("start-game",room.getId(),version,color1));
        this.sendMessageById(id2,newGameMessage("start-game",room.getId(),version,color2));
//观战者按新的对局重置棋盘
        sendSpectatorReset(room);
//管理员巡查视图同步对局状态变化
        notifyAdminRoomView(room);
        addRoomEvent(room,"系统消息：双方已准备，对局开始");
        refreshLobby();
    }
    //房间消息
    private void sendRoomMessage(JSONObject data,String senderId){
        String roomId = data.getStr("roomId");
        Room room = roomById(roomId);
        //房间可能刚被解散（与巡查管理员发言并发），此时没有可下发的对象，直接丢弃
        if(room == null){
            return;
        }
//房间成员与管理员隐身观察者都可以在该房间发言
        if(!isRoomMember(room,senderId) && !isAdminObserver(roomId,senderId)) return;
        //身份与昵称以服务端记录为准，避免客户端伪造发送者。
        //管理员隐身巡查时发言也使用管理员自己的昵称，方便房内用户识别管理员身份。
        try{
            data.put("fromId",Integer.valueOf(senderId));
        }catch (NumberFormatException e){
            //连接id非数字（异常客户端），保持客户端上报值
        }
        String nick = null;
        if(Objects.equals(room.getPlayerId1(),senderId)){
            nick = room.getPlayerName1();
        }else if(Objects.equals(room.getPlayerId2(),senderId)){
            nick = room.getPlayerName2();
        }else{
            //管理员隐身查看时发言：使用其服务端权威昵称（管理员账号名）
            nick = displayNameOf(senderId);
        }
        if(nick != null) data.put("fromName",nick);
        String id1 = room.getPlayerId1();
        String id2 = room.getPlayerId2();
if(id1!=null)   this.sendMessageById(id1,JSONUtil.toJsonStr(data));
        if(id2!=null)   this.sendMessageById(id2,JSONUtil.toJsonStr(data));
        //观战者与管理员隐身观察者都能看到房间消息
        this.notifySpectators(room,JSONUtil.toJsonStr(data));
    }
//加入房间
private void joinRoom(JSONObject data,Session session,String userId){
        //昵称以服务端在线记录/数据库为准，不信任客户端上报值；密码仅用于私人房间校验
        this.doJoinRoom(userId,displayNameOf(userId),data.getStr("roomId"),
                data.getStr("password"),session);
    }

    /**
     * 加入房间（邀请接受后也走这里）：
     * 房主自己、房内已坐满、已在该房间内都会被单独处理。
     * 返回 true 表示最终已在目标房间中（含本来就在该房间的情况）。
     */
    private boolean doJoinRoom(String userId,String name,String roomId,String password,Session session){
        //查找有没有这个房间的信息
        Room room = roomById(roomId);
        if(room==null) {
            this.sendMessage(
                    JSONUtil.toJsonStr(
                            new Message<>(400,"join-room-fail","房间已不存在！")),
                    session);
            return false;
        }
//若用户已在另一个房间（作为玩家或观战者），先退出原房间，避免同时挂在两个房间造成
        //旧房间仍把它当成员、新房间也已占座的“幽灵成员”问题
        String previousRoomId = playerRoomMap.get(userId);
        if(previousRoomId!=null && !Objects.equals(previousRoomId,roomId)){
            this.quitRoom(userId);
        }
        //不能加入自己创建的房间，否则会与自己开局并占用房间数据
        if(Objects.equals(userId,room.getPlayerId1())){
            this.sendMessage(
                    JSONUtil.toJsonStr(
                            new Message<>(400,"join-room-fail","不能加入自己创建的房间！")),
                    session);
            return false;
        }
        //已经在该房间内（例如受邀后重复触发），直接返回成功视图
        if(isRoomMember(room,userId)){
            this.sendMessage(
                    JSONUtil.toJsonStr(new Message<>(200,"join-room-success",roomView(room))),
                    session);
            return true;
        }
        //被房主踢出的用户直接拒绝，且优先于密码校验：
        //这类用户即使不带密码也应看到「已被移出」，而不是被要求输入密码。
        //kickedIds 本身是并发集合，锁外读取即可。
        if(room.getKickedIds()!=null && room.getKickedIds().contains(userId)){
            this.sendMessage(
                    JSONUtil.toJsonStr(
                            new Message<>(400,"join-room-fail","你已被房主移出该房间，无法再次加入！")),
                    session);
            return false;
        }
        //私人房间：受邀用户可直接进入，其他用户必须提供正确的房间密码。
        //密码校验走 PBKDF2（单次约几十毫秒），必须在房间锁之外完成：
        //否则任何已登录用户只要连续发 join-room 试错，就能长期占住这个房间的锁，
        //房内玩家连落子、准备这些 synchronized(room) 操作都会被卡住（房间级拒绝服务）。
        //同时按账号限制尝试次数，避免被用来暴力猜密码或打满 CPU。
        if(!room.isOpen()){
            boolean invited = room.getInvitedIds()!=null && room.getInvitedIds().contains(userId);
            if(!invited){
                String candidate = (password==null) ? null : password.trim();
                if(candidate==null || candidate.isEmpty()){
                    this.sendMessage(
                            JSONUtil.toJsonStr(
                                    new Message<>(400,"join-room-fail","该房间为私人房间，请输入房间密码后再加入！")),
                            session);
                    return false;
                }
                if(abuseGuard != null && !abuseGuard.allowRoomPasswordTry(userId)){
                    this.sendMessage(
                            JSONUtil.toJsonStr(
                                    new Message<>(429,"join-room-fail","房间密码尝试过于频繁，请稍后再试！")),
                            session);
                    return false;
                }
                String passwordHash = room.getPrivatePasswordHash();
                if(passwordHash==null || !PasswordUtil.matches(candidate,passwordHash)){
                    this.sendMessage(
                            JSONUtil.toJsonStr(
                                    new Message<>(400,"join-room-fail","房间密码错误，请重新输入！")),
                            session);
                    return false;
                }
            }
        }
        //检查房间是否已经坐满
        boolean roomFull;
        synchronized (room){
            //被房主踢出过的用户不允许再次加入该房间
            if(room.getKickedIds()!=null && room.getKickedIds().contains(userId)){
this.sendMessage(
                        JSONUtil.toJsonStr(
                                new Message<>(400,"join-room-fail","你已被房主移出该房间，无法再次加入！")),
                        session);
                return false;
            }
            if(room.getPlayerId2()!=null && !EMPTY_PLAYER.equals(room.getPlayerId2())){
                roomFull = true;
            }else if("playing".equals(room.getState())){
                roomFull = true;
            }else{
roomFull = false;
                //如果此前在该房间观战，转为玩家后要从观战名单移除，避免同时被当作玩家和观战者
                if(room.getSpectators()!=null){
                    room.getSpectators().remove(userId);
                }
                //更新房间的信息
                room.setPlayerId2(userId);
                room.setPlayerName2(name);
                //换了对手：比分从头开始计
                resetScore(room);
                //上一局的终局快照属于前一位对手，新房客入座后不应再被当成当前局面下发
                room.setFinalSteps(null);
                //两人已坐下但尚未双方准备：对外状态为「房间中」，等两人都准备后才进入「游戏中」
                room.setState("room");
                //新成员加入，双方都需重新准备（未准备时状态记为「房间中」）
                room.setReadyId1(false);
                room.setReadyId2(false);
                //加入成功即消耗掉该用户的邀请标记
                if(room.getInvitedIds()!=null){
                    room.getInvitedIds().remove(userId);
                }
                //座位登记必须与 room 的占座在同一次锁内完成：
                //原先这一行在锁外执行，若用户恰好在这一瞬间断开，quitRoom 会因
                //playerRoomMap 里还查不到他而直接返回，房间却已把他记为 player2，
                //于是留下一个「人不在、座位却占着」的幽灵成员——房间永远显示满员，
                //房主也无法开局，只能等房主退出解散。ConcurrentHashMap.put 无阻塞风险，
                //放进锁内不会拉长锁持有时间。
                playerRoomMap.put(userId,roomId);
            }
        }
        if(roomFull){
this.sendMessage(
                    JSONUtil.toJsonStr(
                            new Message<>(400,"join-room-fail","该房间已经满了！")),
                    session);
            return false;
        }
        //接受邀请进入房间后，不再作为其它房间的隐身巡查观察者，避免旧巡查页与新房内视图并存
        removeFromAdminObservers(userId);
        //通知房主有人加入房间了
        Message<JSONObject> message = new Message<>(200,"someone-join-room",roomView(room));
        this.sendMessageById(room.getPlayerId1(),JSONUtil.toJsonStr(message));
        //通知申请者加入房间成功了
        message.setType("join-room-success");
        this.sendMessage(JSONUtil.toJsonStr(message),session);
//房内成员变化，观战者同步刷新房间视图
broadcastSpectatorUpdate(room);
        //把成员变化同步给正在隐身巡查该房间的管理员，避免其视图停留在「等待加入···」
        notifyAdminRoomView(room);
        addRoomEvent(room,"系统消息：棋手 "+name+" 已加入房间");
        //将在线的全部房间发给全部在线用户
        this.refreshLobby();
        return true;
    }

    //退出房间
    private void quitRoom(String id){
        //先检查
        String roomId = playerRoomMap.get(id);
        if(roomId==null) return;
Room room = roomById(roomId);
        if(room==null){
            playerRoomMap.remove(id);
            adminObservers.remove(roomId);
            return;
        }
        //观战者退出：只从观战名单移除，不影响对局双方
        if(!isRoomMember(room,id)
                && room.getSpectators()!=null && room.getSpectators().containsKey(id)){
synchronized (room){
                room.getSpectators().remove(id);
                playerRoomMap.remove(id);
            }
            broadcastSpectatorUpdate(room);
            notifyAdminRoomView(room);
            refreshLobby();
            return;
        }
        System.out.println("用户:"+id+"退出了房间:"+roomId);
        synchronized (room){
            if(Objects.equals(room.getPlayerId1(), id)){
                //房主退了  如果有房客则 房客成为房主
                playerRoomMap.remove(id);

                String player2 = room.getPlayerId2();
                if(player2!=null && !EMPTY_PLAYER.equals(player2)){
                    room.setPlayerId1(player2);
                    room.setPlayerName1(room.getPlayerName2());
room.setPlayerId2(EMPTY_PLAYER);
                    room.setPlayerName2("");
                    //老房主离开、房客升为房主：这一对搭档已经散了，比分清零
                    resetScore(room);
                    room.setState("waiting");
                    room.setWinCheck(0);
                    room.setSteps(null);
                    //搭档已换人：上一局的终局快照不再属于这个房间，清掉避免重连时看到旧棋盘
                    room.setFinalSteps(null);
                    room.setStart(null);
                    room.setColor1(null);
                    room.setColor2(null);
                    //老房主离开后回到未准备状态（状态记为「房间中」）
                    room.setReadyId1(false);
                    room.setReadyId2(false);
                    //告诉房客
this.sendMessageById(player2,
                            JSONUtil.toJsonStr(new Message<>(200,"become-owner",roomView(room))));
                    notifyAdminRoomView(room);
                    addRoomEvent(room,"系统消息："+displayNameOf(id)+" 离开了房间，"+room.getPlayerName1()+" 成为房主");
                    //房主
                    System.out.println("房客成为了房间:"+roomId+"的房主");
                }else{
                    //房间解散：解除观战者关联并通知其房间已关闭
                    if(room.getSpectators()!=null){
                        for(String spectatorId : new ArrayList<>(room.getSpectators().keySet())){
                            playerRoomMap.remove(spectatorId);
                            this.sendMessageById(spectatorId,
                                    JSONUtil.toJsonStr(new Message<>(200,"room-closed","房间已解散")));
                        }
room.getSpectators().clear();
                    }
                    roomMap.remove(roomId);
                    clearPendingActionsOfRoom(roomId);
                    //房间已解散：通知正在巡查该房间的管理员退出查看并回到大厅
                    notifyAdminRoomClosed(roomId,"房内玩家已全部离开，房间已解散");
                }
            }else{
                playerRoomMap.remove(id);
                //房客退了
                room.setPlayerId2(EMPTY_PLAYER);
                room.setPlayerName2("");
                //房客离开：房间只剩房主，比分不再有意义
                resetScore(room);
                room.setStart(null);
                room.setState("waiting");
                room.setSteps(null);
                //搭档已离开：上一局的终局快照不再属于这个房间，清掉避免重连时看到旧棋盘
                room.setFinalSteps(null);
                room.setWinCheck(0);
room.setColor1(null);
                room.setColor2(null);
                //房客离开，双方回到未准备状态（状态记为「房间中」）
                room.setReadyId1(false);
                room.setReadyId2(false);
                //告诉房主房客退了

this.sendMessageById(room.getPlayerId1(),
                        JSONUtil.toJsonStr(new Message<>(200,"client-leave",roomView(room))));
                notifyAdminRoomView(room);
                addRoomEvent(room,"系统消息："+displayNameOf(id)+" 离开了房间");
            }
        }

//一方离开房间后对局无法继续：先同步最新房间视图（离开者不再显示为在房/已准备），
        //再提示观战者由前端询问“继续观看/离开”（房间已解散的情况由 room-closed 处理）
        Room remaining = roomMap.get(roomId);
        if(remaining != null){
            broadcastSpectatorUpdate(remaining);
            endSpectate(remaining, "一方已离开房间，对局结束");
        }

        //将在线的全部房间发给全部在线用户
        this.refreshLobby();
    }
    //创建房间
/**
     * 分配房间号：6 位数字，从 000001 开始。
     * 每次都基于当前房间集合找最小空闲号，因此某个房间空了或被解散后，
     * 它的房间号会被下一个新建房间优先复用，而不是一直往后顺延。
     * 调用方必须在 ROOM_NO_LOCK 同步块内执行，避免并发创建拿到同一个房间号。
     */
    private String allocateRoomNo(){
        Set<Integer> used = new HashSet<>();
        for(Room room : roomMap.values()){
            String no = room.getRoomNo();
            if(no == null || no.isEmpty()){
                continue;
            }
            try{
                used.add(Integer.valueOf(no));
            }catch(NumberFormatException ignored){
                //非数字房间号（历史遗留）不参与占位
            }
        }
        int candidate = 1;
        while(used.contains(candidate)){
            candidate++;
        }
        return String.format("%0" + ROOM_NO_WIDTH + "d",candidate);
    }

private void createRoom(Session session,String userId){
        //昵称以服务端在线记录/数据库为准，不信任客户端上报值
        String displayName = displayNameOf(userId);
        //检查用户是否已经在某个房间中
        String oldRoomId = playerRoomMap.get(userId);
        if(oldRoomId!=null){
            Room oldRoom = roomMap.get(oldRoomId);
            if(oldRoom!=null && !isRoomMember(oldRoom,userId)){
                //仅作为观战者时新建房间：只退出观战，不能把被观战的房间解散、把房内玩家踢走
                synchronized (oldRoom){
                    if(oldRoom.getSpectators()!=null){
                        oldRoom.getSpectators().remove(userId);
                    }
                    playerRoomMap.remove(userId);
                }
                broadcastSpectatorUpdate(oldRoom);
                refreshLobby();
            }else if(oldRoom!=null){
                //房内玩家新建房间：保持原有产品行为——解散旧房间并通知另一位玩家
                String otherPlayer;
                synchronized (oldRoom){
                    otherPlayer = Objects.equals(oldRoom.getPlayerId1(),userId)
                            ? oldRoom.getPlayerId2() : oldRoom.getPlayerId1();
                    if(oldRoom.getPlayerId1()!=null) playerRoomMap.remove(oldRoom.getPlayerId1());
                    if(oldRoom.getPlayerId2()!=null) playerRoomMap.remove(oldRoom.getPlayerId2());
                    //旧房间的观战者一并解除关联并收到通知
                    if(oldRoom.getSpectators()!=null){
                        for(String spectatorId : new ArrayList<>(oldRoom.getSpectators().keySet())){
                            playerRoomMap.remove(spectatorId);
                        }
                        oldRoom.getSpectators().clear();
                    }
                    roomMap.remove(oldRoom.getId());
                    clearPendingActionsOfRoom(oldRoom.getId());
                    adminObservers.remove(oldRoom.getId());
                }
                //通知原房间内的另一位玩家，避免其页面停留在已解散的房间
                if(otherPlayer!=null && !EMPTY_PLAYER.equals(otherPlayer)){
                    this.sendMessageById(otherPlayer,
                            JSONUtil.toJsonStr(new Message<>(200,"room-closed","房间已解散")));
                }
            }else{
                playerRoomMap.remove(userId);
            }
        }
//创建新的房间：房间号由服务端自动分配（6 位数字，优先复用最小空闲号）
        Room room = new Room();
        room.setPlayerId1(userId);
        room.setPlayerName1(displayName);
        synchronized(ROOM_NO_LOCK){
            room.setRoomNo(allocateRoomNo());
            //将房间保存到map并且关联上用户id
            roomMap.put(room.getId(),room);
            playerRoomMap.put(userId,room.getId());
        }
        //把创建好的房间视图（含房间号）返回给创建者
        this.sendMessage(
                JSONUtil.toJsonStr(
                        new Message<>(200,"create-room-success",roomView(room))),
                session);
        System.out.println("用户:"+userId+"创建了新房间:"+room.getId()+"(房间号:"+room.getRoomNo()+")");

        //将在线的全部房间发给全部在线用户
        this.refreshLobby();
    }

    /**
     * 保存对局：序列化步数写入 game 表，并写入 record 表
     * 保存完成后清空房间对局数据
     * 调用方需持有房间对象锁
     */
    /**
     * 保存本局对局记录（game + record 同一事务）。
     *
     * @return true 表示记录已落库，或本来就没有可保存的步数；false 表示落库失败。
     *         失败时会把房间的实时步数清掉：调用方此时已把房间置为「已结束」，
     *         若步数仍留在房间里，start-game 会认为这一局还没打完而直接返回，
     *         房间就再也开不了新局，只能等房主退出解散（只能靠重启服务恢复）。
     *         宁可丢这一局的棋谱，也不能把房间卡死。
     */
    private boolean saveRecord(Room room,Integer win,String type){
        if(room==null || room.getSteps()==null || room.getSteps().isEmpty()){
            return true;
        }
        Integer player1,player2;
        try{
            //双方都必须是真实的用户id；房间内只剩一人（player2 为占位符）时不做保存
            player1 = Integer.valueOf(room.getPlayerId1());
            player2 = Integer.valueOf(room.getPlayerId2());
        }catch (NumberFormatException e){
            System.out.println("房间对局双方信息不完整，本次不保存对局记录：" + e.getMessage());
            resetToPlayable(room);
            return false;
        }
        Steps steps = new Steps(new ArrayList<>(room.getSteps()));
        ByteArrayOutputStream bos = MyUtil.getByteArrayOutputStream();
        ObjectOutputStream out = null;
        try{
            out = new ObjectOutputStream(bos);
            out.writeObject(steps);
            out.flush();
        }catch (IOException e){
            e.printStackTrace();
            resetToPlayable(room);
            return false;
        }finally{
            try{
                if (out != null) {
                    out.close();
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        byte[] gameData = bos.toByteArray();
        String gameId = UUID.randomUUID().toString();
        Game game = new Game(gameId,gameData);
        //记录也保存到数据
        Record record = new Record(null,player1,room.getPlayerName1(),
                player2,room.getPlayerName2(),
                room.getStart(),MyUtil.now(),win,
                steps.getSteps().size(),gameId,type);
        //两张表必须在同一事务里写：game 写成功但 record 失败时，会留下一条没有任何战绩引用的
        //棋盘数据（占空间、进不了历史记录、也没人清理）。失败时整体回滚，调用方保持本局可重试。
        try{
            if(transactionTemplate != null){
                transactionTemplate.execute(status -> {
                    gameMapper.insert(game);
                    recordMapper.insert(record);
                    return null;
                });
            }else{
                gameMapper.insert(game);
                recordMapper.insert(record);
            }
        }catch(Exception e){
            System.out.println("对局记录保存失败（已回滚，不写入半条数据）：" + e.getMessage());
            resetToPlayable(room);
            return false;
        }
        System.out.println("对局记录已保存。");
        //保存终局棋盘快照后再清空实时步数：已结束的对局仍可被观战并看到完整棋盘
        room.setFinalSteps(new ArrayList<>(room.getSteps()));
        //清空房间对局记录
        room.setSteps(null);
        room.setWinCheck(0);
        return true;
    }

    /**
     * 记录没写成时把房间恢复成「可以再开一局」的状态。
     * 只清实时步数，不写终局快照：没有入库的棋谱不该被当成可复盘的终局局面下发。
     */
    private void resetToPlayable(Room room){
        if(room == null){
            return;
        }
        room.setSteps(null);
        room.setFinalSteps(null);
        room.setWinCheck(0);
    }

    /**
     * 主动断开某个用户已鉴权的连接。退出登录时调用：HTTP 侧撤销令牌后，
     * 已建立的 WebSocket 不会自动失效，不主动断开的话该连接仍能继续发言、下棋。
     * 未鉴权连接不占用 sessionMap，因此不会误关别人的连接。
     */
    public static void closeUserSessions(String userId,String reason){
        if(userId == null || userId.trim().isEmpty()){
            return;
        }
        Session session = sessionMap.get(userId);
        if(session == null){
            return;
        }
        try{
            JSONObject fail = new JSONObject();
            fail.put("code",401);
            fail.put("type","auth-fail");
            fail.put("content","登录状态无效，请重新登录");
            fail.put("reason",reason);
            synchronized (session){
                if(session.isOpen()){
                    session.getBasicRemote().sendText(fail.toString());
                }
            }
        }catch (Exception e){
            //连接可能已关闭，忽略
        }
        try{
            if(session.isOpen()){
                session.close();
            }
        }catch (Exception e){
            //忽略：关闭失败时由心跳与鉴权超时兜底
        }
    }

    private void sendMessageById(String id,String message){
        if(id==null) return;
        //只投递给已鉴权的连接：未鉴权连接不占用 sessionMap，这里再加一道保险
        if(!authedSessions.containsKey(id)){
            return;
        }
        //直接按 id 取连接，避免在线用户较多时每条消息都遍历整个 sessionMap
        Session target = sessionMap.get(id);
        if(target!=null){
            this.sendMessage(message,target);
        }
        //如果收信人不在线，则将信息进行缓存（暂不实现离线缓存）
    }

    private void sendMessage(String message,Session toSession){
        if(toSession==null || !toSession.isOpen()){
            return;
        }
        //同一个 session 的写入并非线程安全，并发广播时会抛
        //IllegalStateException: The remote endpoint was in state [TEXT_FULL_WRITING]
        //因此按 session 加锁串行发送
        synchronized (toSession){
            try {
                if(toSession.isOpen()){
                    toSession.getBasicRemote().sendText(message);
                }
            }catch (Exception e){
                if(e instanceof IllegalStateException){
                    System.out.println("session 正在关闭，消息发送失败：" + e.getMessage());
                }else{
                    e.printStackTrace();
                }
            }
        }
    }

    // ==================== 在线状态与房主身份 ====================

/**
     * 用户的游戏状态：inGame/游戏中、inRoom/房间中、idle/空闲
     * 房内已坐下对手且双方都已准备才记为“游戏中”，任一方未准备（或仅自己一人在房内）记为“房间中”
     */
    private String statusOf(String userId){
        if(userId == null){
            return "idle";
        }
        String roomId = playerRoomMap.get(userId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room == null){
            return "idle";
        }
//状态字段由多个连接线程在 synchronized(room) 内修改，这里同样加锁读取，
        //否则在线列表广播可能读到过期的准备状态（偶发显示“房间中”）
synchronized (room){
            //状态只由“是否两人都在房内且双方都已准备”决定：终局后准备状态已重置，自然回落到房间中
            if(isRoomMember(room,userId)){
                boolean hasOpponent = room.getPlayerId2()!=null && !EMPTY_PLAYER.equals(room.getPlayerId2());
                boolean bothReady = room.isReadyId1() && room.isReadyId2();
                return (hasOpponent && bothReady) ? "inGame" : "inRoom";
            }
            if(room.getSpectators()!=null && room.getSpectators().containsKey(userId)){
                //观战者单独归为“观战中”，与普通房间中区分
                return "spectating";
            }
            return "idle";
        }
    }

    /**
     * 用户是否为当前所在房间的房主
     */
    private boolean isRoomOwner(String userId){
        String roomId = playerRoomMap.get(userId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        return room != null && Objects.equals(room.getPlayerId1(),userId);
    }

    /**
     * 在线用户明细：id、昵称、状态、是否房主
     */
    private List<JSONObject> onlineUserInfos(){
        List<JSONObject> list = new ArrayList<>();
        for(Map.Entry<String,String> entry : onlineUserNickMap.entrySet()){
            String uid = entry.getKey();
            //心跳超时的用户临时移出在线列表（连接仍在，心跳恢复后自动回到列表）
            if(heartbeatTimedOut.contains(uid)){
                continue;
            }
JSONObject info = new JSONObject();
            info.put("id",uid);
            info.put("nick",entry.getValue());
            //账号：仅用于前端在线列表按账号模糊搜索，界面不展示
            info.put("login",onlineUserLoginMap.get(uid));
            info.put("status",statusOf(uid));
            info.put("owner",isRoomOwner(uid));
            //是否被禁言：管理员据此在在线列表切换「禁言 / 取消禁言」
            info.put("muted",mutedUserIds.contains(uid));
            list.add(info);
        }
        return list;
    }

    /**
     * 在线列表消息体：保留原有 userList / onlineCount，并补充 userInfos（含状态与房主标记）
     */
    private Map<String,Object> onlineListData(){
        List<String> nickNameList = new ArrayList<>();
        for(Map.Entry<String,String> entry : onlineUserNickMap.entrySet()){
            if(!heartbeatTimedOut.contains(entry.getKey())){
                nickNameList.add(entry.getValue());
            }
        }
        Map<String,Object> resultData = new HashMap<>(4);
        resultData.put("userList",nickNameList);
        resultData.put("onlineCount",nickNameList.size());
        resultData.put("userInfos",onlineUserInfos());
        return resultData;
    }



    // ==================== 邀请 ====================

    /**
     * 邀请他人加入自己的房间：
     * 只有房间成员可发起；只能邀请空闲或房间中（非游戏中）的在线用户
     */
    private void handleInvite(JSONObject data,String inviterId){
        String targetId = data.getStr("targetId");
        String roomId = playerRoomMap.get(inviterId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room==null || !isRoomMember(room,inviterId)){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","只有在房间中才能邀请他人")));
            return;
        }
        if(targetId==null || !onlineUserNickMap.containsKey(targetId)){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","对方已不在线")));
            return;
        }
        if(Objects.equals(targetId,inviterId)){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","不能邀请自己")));
            return;
        }
        //房内已经坐下对手时无法再邀请
        if(room.getPlayerId2()!=null && !EMPTY_PLAYER.equals(room.getPlayerId2())){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","房间已满，无法邀请")));
            return;
        }
        if(isRoomMember(room,targetId)
                || (room.getSpectators()!=null && room.getSpectators().containsKey(targetId))){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","对方已经在房间中")));
            return;
        }
        String targetStatus = statusOf(targetId);
        if(!"idle".equals(targetStatus) && !"inRoom".equals(targetStatus)
                && !"spectating".equals(targetStatus)){
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","对方正在游戏中，无法邀请")));
            return;
        }
        //当天屏蔽：对方选择「拒绝并不再接受该用户邀请」后，该邀请者当天无法再邀请该用户
        if(todayKey().equals(inviteBlockedDay.get(rejectKey(inviterId,targetId)))){
            JSONObject blockedFail = new JSONObject();
            blockedFail.put("code",400);
            blockedFail.put("type","invite-fail");
            blockedFail.put("content","对方已拒绝并在今天不再接受你的邀请");
            blockedFail.put("targetId",targetId);
            blockedFail.put("blocked",true);
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(blockedFail));
            return;
        }
        //拒绝冷却：被拒绝后 rejectCooldownMs 内不允许再次邀请同一用户，返回剩余秒数供前端倒计时
        long cooldown = rejectCooldownRemainSeconds(inviterId,targetId);
        if(cooldown > 0){
            JSONObject fail = new JSONObject();
            fail.put("code",400);
            fail.put("type","invite-fail");
            fail.put("content","该用户刚拒绝了邀请，请 "+cooldown+" 秒后再试");
            fail.put("targetId",targetId);
            fail.put("cooldown",cooldown);
            this.sendMessageById(inviterId,JSONUtil.toJsonStr(fail));
            return;
        }
        synchronized (room){
            room.getInvitedIds().add(targetId);
        }
        String inviterNick = onlineUserNickMap.get(inviterId);
        JSONObject invite = new JSONObject();
        invite.put("type","invite-received");
        invite.put("roomId",room.getId());
        invite.put("fromId",inviterId);
        invite.put("fromName",inviterNick);
        invite.put("roomOpen",room.isOpen());
        this.sendMessageById(targetId,JSONUtil.toJsonStr(invite));
        //回执：告诉邀请者邀请已发出
        JSONObject sent = new JSONObject();
        sent.put("type","invite-sent");
        sent.put("roomId",room.getId());
        sent.put("targetId",targetId);
        sent.put("targetNick",onlineUserNickMap.get(targetId));
        this.sendMessageById(inviterId,JSONUtil.toJsonStr(sent));
//记录待处理邀请：inviteTimeoutMs 内未被处理则自动拒绝。
        //同一被邀请者只保留最新一条邀请，并从旧邀请的房间邀请名单中移除，避免旧邀请长期有效。
        JSONObject pending = new JSONObject();
        pending.put("inviterId",inviterId);
        pending.put("roomId",room.getId());
        pending.put("expireAt",System.currentTimeMillis() + inviteTimeoutMs);
        JSONObject previous = pendingInvites.put(targetId,pending);
        if(previous != null && !Objects.equals(previous.getStr("roomId"),room.getId())){
            Room previousRoom = roomMap.get(previous.getStr("roomId"));
            if(previousRoom != null){
                synchronized (previousRoom){
                    if(previousRoom.getInvitedIds()!=null){
                        previousRoom.getInvitedIds().remove(targetId);
                    }
                }
            }
        }
    }

    /**
     * 接受邀请：被邀请者进入邀请者的房间（已在别的房间则先退出原房间）
     */
private void handleInviteAccept(JSONObject data,String inviteeId,Session session){
        String roomId = data.getStr("roomId");
        String fromId = data.getStr("fromId");
        //只接受服务端真实记录、且邀请人与房间都匹配的待处理邀请；过期/伪造/已被处理的邀请一律拒绝
        JSONObject pending = pendingInvites.get(inviteeId);
        if(pending == null
                || !Objects.equals(roomId,pending.getStr("roomId"))
                || !Objects.equals(fromId,pending.getStr("inviterId"))){
            this.sendMessageById(inviteeId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","该邀请已失效")));
            return;
        }
        pendingInvites.remove(inviteeId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room==null || !isRoomMember(room,fromId)){
            this.sendMessageById(inviteeId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","该邀请已失效")));
            return;
        }
        boolean invited;
        synchronized (room){
            invited = room.getInvitedIds()!=null && room.getInvitedIds().contains(inviteeId);
        }
        if(!invited){
            this.sendMessageById(inviteeId,JSONUtil.toJsonStr(
                    new Message<>(400,"invite-fail","该邀请已失效")));
            return;
        }
//邀请回执展示当前在线昵称；房间内成员昵称由 doJoinRoom 使用服务端权威昵称写入
        String nick = nickOf(inviteeId);
        //接受即加入该房间（若在别的房间，doJoinRoom 会先退出原房间；私人房间因已在受邀名单内可直接进入）
        boolean joined = this.doJoinRoom(inviteeId,nick,roomId,null,session);
        if(!joined){
            //加入失败（房间已满/已不存在等）时不能向邀请者谎报“已接受”
            return;
        }
        //告知邀请者对方已接受（房间内会同时收到 someone-join-room）
        JSONObject accepted = new JSONObject();
        accepted.put("type","invite-accepted");
        accepted.put("roomId",roomId);
        accepted.put("byId",inviteeId);
        accepted.put("byName",nick);
        this.sendMessageById(fromId,JSONUtil.toJsonStr(accepted));
    }

    /**
     * 拒绝邀请：清除邀请标记并告知邀请者
     */
private void handleInviteReject(JSONObject data,String inviteeId){
        String roomId = data.getStr("roomId");
        String fromId = data.getStr("fromId");
        //只处理服务端真实记录、且邀请人与房间都匹配的待处理邀请。
        //客户端 30 秒超时与后台巡检可能同时触发，这里保证重复的拒绝只生效一次。
        JSONObject pending = pendingInvites.get(inviteeId);
        if(pending == null
                || !Objects.equals(roomId,pending.getStr("roomId"))
                || !Objects.equals(fromId,pending.getStr("inviterId"))){
            return;
        }
        pendingInvites.remove(inviteeId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room==null){
            return;
        }
        synchronized (room){
            if(room.getInvitedIds()!=null){
                room.getInvitedIds().remove(inviteeId);
            }
        }
        //只允许把“被拒绝”回执发给该房间内的成员，避免伪造消息骚扰他人
        if(!isRoomMember(room,fromId)){
            return;
        }
        //「拒绝并不再接受该用户邀请」：记录当天屏蔽，当天内该邀请者无法再邀请该用户
        boolean blockedForToday = Boolean.TRUE.equals(data.getBool("block"));
        if(blockedForToday){
            inviteBlockedDay.put(rejectKey(fromId,inviteeId),todayKey());
        }
        JSONObject rejected = new JSONObject();
        rejected.put("type","invite-rejected");
        rejected.put("roomId",roomId);
        rejected.put("byId",inviteeId);
        rejected.put("byName",onlineUserNickMap.get(inviteeId));
        //前端倒计时归零时主动发来的拒绝，标记为超时，便于邀请者区分提示
        if(Boolean.TRUE.equals(data.getBool("timeout"))){
            rejected.put("reason","timeout");
        }
        //被拒绝后进入冷却，并把冷却秒数回传，供邀请者按钮显示倒计时
        rejected.put("cooldown",markRejected(fromId,inviteeId));
        if(blockedForToday){
            rejected.put("blocked",true);
        }
        this.sendMessageById(fromId,JSONUtil.toJsonStr(rejected));
    }

    // ==================== 观战 ====================

    /**
     * 观战：进入目标用户所在的对局房间，只读接收棋盘与结果广播，不影响对局判定
     */
    private void handleSpectate(JSONObject data,String viewerId,Session session){
        String targetId = data.getStr("targetId");
        if(targetId==null || Objects.equals(targetId,viewerId)){
            this.sendMessageById(viewerId,JSONUtil.toJsonStr(
                    new Message<>(400,"spectate-fail","无法观战该用户")));
            return;
        }
        String targetRoomId = playerRoomMap.get(targetId);
        Room room = (targetRoomId==null) ? null : roomMap.get(targetRoomId);
//进行中的对局可以直接观战；已经结束（over）的对局也允许进入，
        //由前端提示“对局已结束，是否继续观看”，玩家可选择继续观看终局棋盘或离开。
        boolean viewable = room != null
                && ("playing".equals(room.getState()) || "over".equals(room.getState()));
        if(room==null || !isRoomMember(room,targetId)
                || room.getPlayerId2()==null || EMPTY_PLAYER.equals(room.getPlayerId2())
                || !viewable){
            this.sendMessageById(viewerId,JSONUtil.toJsonStr(
                    new Message<>(400,"spectate-fail","该用户当前没有可观看的对局")));
            return;
        }
        if(isRoomMember(room,viewerId)){
            this.sendMessageById(viewerId,JSONUtil.toJsonStr(
                    new Message<>(400,"spectate-fail","你已在对局中，无法观战")));
            return;
        }
        if(room.getSpectators()!=null && room.getSpectators().containsKey(viewerId)){
            this.sendMessageById(viewerId,JSONUtil.toJsonStr(
                    new Message<>(400,"spectate-fail","你已经在观战该对局")));
            return;
        }
        //观战者若在别的房间，先退出原房间
        String oldRoomId = playerRoomMap.get(viewerId);
        if(oldRoomId!=null && !Objects.equals(oldRoomId,room.getId())){
            this.quitRoom(viewerId);
        }
//观战者昵称：在线昵称可能尚未上报，回退数据库昵称，避免 ConcurrentHashMap 拒绝 null 值
        String nick = displayNameOf(viewerId);
        JSONObject ok = new JSONObject();
        synchronized (room){
            room.getSpectators().put(viewerId,nick);
            playerRoomMap.put(viewerId,room.getId());
            ok.put("type","spectate-success");
            ok.put("roomView",roomView(room));
            //把已经落下的棋子一并下发，保证观战者能看到当前局面
            ok.put("steps",room.getSteps() != null ? room.getSteps() : room.getFinalSteps());
            ok.put("color1",room.getColor1());
            ok.put("color2",room.getColor2());
        }
        this.sendMessage(JSONUtil.toJsonStr(ok),session);
        System.out.println("用户:"+viewerId+"开始观战房间:"+room.getId());
        //房间内成员刷新观战人数
        broadcastSpectatorUpdate(room);
        refreshLobby();
    }

    /**
     * 向房间内的观战者广播消息
     */
private void notifySpectators(Room room,String json){
        if(room==null){
            return;
        }
        if(room.getSpectators()!=null){
            for(String spectatorId : new ArrayList<>(room.getSpectators().keySet())){
                this.sendMessageById(spectatorId,json);
            }
        }
        //管理员隐身观察者同样实时收到该房间的内容（落子、终局、操作说明、房间消息）
        Set<String> observers = adminObservers.get(room.getId());
        if(observers!=null){
            for(String adminId : observers){
                this.sendMessageById(adminId,json);
            }
        }
    }

/**
     * 把房间最新视图同步给正在隐身巡查该房间的管理员。
     * 房间成员加入/离开、准备、状态变化时调用，避免管理员视图停留在旧的「等待加入···」。
     */
    /**
     * 记录一条房间系统事件，并实时推送给正在隐身巡查该房间的管理员。
     * 事件只保存在内存中（随房间释放），用于巡查时完整还原房间消息，不写入聊天记录表。
     */
    private void addRoomEvent(Room room,String content){
        if(room == null || content == null || content.trim().isEmpty()){
            return;
        }
        JSONObject event = new JSONObject();
        event.put("time",MyUtil.now());
        event.put("content",content);
        room.getEvents().add(event);
        while(room.getEvents().size() > ROOM_EVENT_LIMIT){
            room.getEvents().remove(0);
        }
        notifyAdminRoomEvent(room,event);
    }

    /** 把房间系统事件实时同步给正在巡查该房间的管理员 */
    private void notifyAdminRoomEvent(Room room,JSONObject event){
        if(room == null || event == null){
            return;
        }
        Set<String> observers = adminObservers.get(room.getId());
        if(observers == null || observers.isEmpty()){
            return;
        }
        JSONObject msg = new JSONObject();
        msg.put("type","admin-room-event");
        msg.put("roomId",room.getId());
        msg.put("event",event);
        String json = JSONUtil.toJsonStr(msg);
        for(String adminId : new ArrayList<>(observers)){
            this.sendMessageById(adminId,json);
        }
    }

    private void notifyAdminRoomView(Room room){
        if(room == null){
            return;
        }
        Set<String> observers = adminObservers.get(room.getId());
        if(observers == null || observers.isEmpty()){
            return;
        }
        JSONObject msg = new JSONObject();
        msg.put("type","admin-room-update");
        msg.put("roomId",room.getId());
        msg.put("roomView",roomView(room));
        //附带当前棋局步数，便于巡查视图在成员变化后仍能正确重绘棋盘
        msg.put("steps",room.getSteps() == null ? null : new ArrayList<>(room.getSteps()));
        String json = JSONUtil.toJsonStr(msg);
        for(String adminId : new ArrayList<>(observers)){
            this.sendMessageById(adminId,json);
        }
    }

    /**
     * 房间被解散且不再存在时，通知正在巡查该房间的管理员退出查看并回到大厅。
     */
    private void notifyAdminRoomClosed(String roomId,String content){
        if(roomId == null){
            return;
        }
        Set<String> observers = adminObservers.remove(roomId);
        if(observers == null || observers.isEmpty()){
            return;
        }
        JSONObject msg = new JSONObject();
        msg.put("type","admin-room-closed");
        msg.put("roomId",roomId);
        msg.put("content",content == null ? "房间已解散" : content);
        String json = JSONUtil.toJsonStr(msg);
        for(String adminId : new ArrayList<>(observers)){
            this.sendMessageById(adminId,json);
        }
    }

    /** 用户昵称（取不到时给个兜底，避免观战提示里出现 null） */
    private String nickOf(String userId){
        String nick = onlineUserNickMap.get(userId);
        return nick==null ? "对方" : nick;
    }

    // ===================== 管理员能力 =====================

    /** 管理员权限校验：非管理员返回 false 并向其回执 */
    private boolean requireAdmin(String senderId,Session session){
        if(moderationService == null || !moderationService.isAdminUserId(senderId)){
            if(session != null){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(403,"admin-fail","无管理员权限")),session);
            }
            return false;
        }
        return true;
    }

    /** 取在线用户的客户端 IP（离线或未解析到返回 null） */
    private String ipOfUser(String userId){
        Session session = sessionMap.get(userId);
        if(session == null){
            return null;
        }
        return sessionIpMap.get(session.getId());
    }

/** 断开后不再作为隐身观察者 */
    private void removeFromAdminObservers(String userId){
        if(userId == null){
            return;
        }
        for(Set<String> observers : adminObservers.values()){
            observers.remove(userId);
        }
    }

/** 立即下线某个用户（不经过断线宽限期）：退出房间、移出在线列表并清理状态 */
    private void forceOffline(String id){
        cancelOfflineRemoval(id);
        if(playerRoomMap.containsKey(id)){
            this.quitRoom(id);
        }
        removeFromAdminObservers(id);
        onlineUserNickMap.remove(id);
        onlineUserLoginMap.remove(id);
        lastActiveMap.remove(id);
        heartbeatTimedOut.remove(id);
        mutedUserIds.remove(id);
    }

    /** 禁言 / 解除禁言（仅管理员） */
    private void handleAdminMute(JSONObject data,String senderId,boolean muted){
        Session session = sessionMap.get(senderId);
        if(!requireAdmin(senderId,session)){
            return;
        }
        String targetId = data.getStr("targetId");
        if(targetId == null || targetId.isEmpty() || targetId.equals(senderId)){
            return;
        }
        User target = moderationService.findById(targetId);
        if(target == null || moderationService.isAdminLogin(target.getLogin())){
            return;
        }
        if(!moderationService.updateMuted(target.getId(),muted)){
            if(session != null){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","操作失败，请稍后重试")),session);
            }
            return;
        }
        if(muted){
            mutedUserIds.add(targetId);
        }else{
            mutedUserIds.remove(targetId);
        }
        User admin = moderationService.findById(senderId);
        moderationService.logAction(muted ? "mute" : "unmute",
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getLogin(),
                target.getId(),target.getLogin(),
                muted ? "禁言用户" : "解除禁言");
        //通知被操作用户
        this.sendMessageById(targetId,JSONUtil.toJsonStr(new Message<>(200,muted ? "muted" : "unmuted",
                muted ? "你已被管理员禁言，暂时无法发言" : "你已被解除禁言")));
        //公共聊天通报：把处置结果公示给全服，起到警戒作用
        broadcastPublicNotice(muted
                ? "【管理员】用户「" + target.getName() + "」因违规发言已被禁言，请大家自觉遵守聊天规范。"
                : "【管理员】用户「" + target.getName() + "」已被解除禁言。");
        //刷新在线列表：管理员的按钮状态随之切换
        sendOnlineUserList();
        if(session != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-action-ok",
                    (muted ? "已禁言 " : "已解除禁言 ") + target.getName())),session);
        }
    }

    /** 封号（仅管理员）：立即断开该用户，并绑定其 IP 阻止再注册 */
    private void handleAdminBan(JSONObject data,String senderId){
        Session session = sessionMap.get(senderId);
        if(!requireAdmin(senderId,session)){
            return;
        }
        String targetId = data.getStr("targetId");
        if(targetId == null || targetId.isEmpty() || targetId.equals(senderId)){
            return;
        }
        User target = moderationService.findById(targetId);
        if(target == null || moderationService.isAdminLogin(target.getLogin())){
            return;
        }
        //封禁按账号自带的两个来源 IP 判定，封号时无需另存一份快照：
        //  ① register_ip：注册那一刻的出口，落库后不变，用户离线也能取到；
        //  ② last_ip：最近一次登录成功的出口，封号后无法再登录，等于已被冻结。
        //两者经常不是同一个地址（在家注册、在公司登录），只拦一个换个地方就能继续注册。
        String registerIp = ClientIpResolver.normalizeLiteral(target.getRegisterIp());
        String lastIp = ClientIpResolver.normalizeLiteral(target.getLastIp());
        //注册与登录在同一出口（含同一条 IPv6 /64）时，回执与公示里只显示一份，避免重复。
        //这里只影响文案，不改数据库：两个字段本身本就指向同一个来源。
        if(registerIp != null && lastIp != null && ClientIpResolver.sameSource(registerIp, lastIp)){
            lastIp = null;
        }
        boolean bound = registerIp != null || lastIp != null;
        if(!moderationService.updateBanned(target.getId())){
            if(session != null){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","封号失败，请稍后重试")),session);
            }
            return;
        }
        //令牌作废：该用户无法再次登录
        if(tokenStore != null){
            tokenStore.revoke(targetId);
        }
        User admin = moderationService.findById(senderId);
        moderationService.logAction("ban",
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getLogin(),
                target.getId(),target.getLogin(),
                "封号并禁止来源=" + (bound
                        ? ipSummary(registerIp,lastIp)
                        : "无（该账号没有注册/登录 IP 记录，仅封账号）"));
        //立即通知并断开该用户（前端收到 banned 后清登录态并跳回登录页）
        Session targetSession = sessionMap.get(targetId);
        if(targetSession != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(403,"banned",ModerationService.BAN_MESSAGE)),targetSession);
            closeQuietly(targetSession);
        }
        //立即移出在线列表与所在房间，不等断线宽限期
        forceOffline(targetId);
        sendOnlineUserList();
        //公共聊天通报：把封号处置公示给全服，起到警戒作用。
        //没绑到 IP 时不能宣称「IP 已被禁止」——否则管理员会误以为注册入口已堵住。
        broadcastPublicNotice(bound
                ? "【管理员】用户「" + target.getName() + "」因严重违规已被封号，其账号与 IP 已被禁止登录/注册。"
                : "【管理员】用户「" + target.getName() + "」因严重违规已被封号。");
        if(session != null){
            //回执里明确告知 IP 绑定结果：没绑到时管理员需要知道「只封了账号」
            String okText = bound
                    ? "已封号 " + target.getName() + "，已禁止注册：" + ipSummary(registerIp,lastIp)
                    : "已封号 " + target.getName() + "，但未取到该用户的 IP（仅封账号，IP 未限制）";
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-action-ok",okText)),session);
        }
    }

    /** 把「注册 IP / 登录 IP」拼成一句便于阅读的说明；为空的项不出现 */
    private static String ipSummary(String registerIp,String lastIp){
        StringBuilder sb = new StringBuilder();
        if(registerIp != null){
            sb.append("注册IP=").append(withBanKey(registerIp));
        }
        if(lastIp != null){
            if(sb.length() > 0){
                sb.append("、");
            }
            sb.append("登录IP=").append(withBanKey(lastIp));
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * IP 后面补上「实际封禁比对的范围」。
     *
     * <p>IPv6 用户在同一线路内会不断轮换隐私地址（每次登录看起来都不一样），
     * 而封禁按 /64 前缀生效。只显示原值会让管理员误以为「没封对」或「IP 每次都在变」，
     * 带上比对范围就能一眼看出这两条其实是同一段。</p>
     */
    private static String withBanKey(String ip){
        String key = ClientIpResolver.matchKey(ip);
        //IPv4 的比对键就是地址本身，不重复显示
        if(key == null || key.isEmpty() || key.equals(ip)){
            return ip;
        }
        return ip + "（比对 " + key + "）";
    }

    /** 管理员进入任意房间（含私人房间）隐身查看：不计入观战人数，房内用户无感知 */
    private void handleAdminEnterRoom(JSONObject data,String senderId,Session session){
        if(!requireAdmin(senderId,session)){
            return;
        }
        String roomId = data.getStr("roomId");
        Room room = roomById(roomId);
        if(room == null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","房间已不存在")),session);
            return;
        }
        adminObservers.computeIfAbsent(roomId,k -> ConcurrentHashMap.newKeySet()).add(senderId);
        JSONObject msg = new JSONObject();
        msg.put("type","admin-room-view");
        msg.put("roomId",roomId);
        synchronized (room){
            msg.put("roomView",roomView(room));
            msg.put("steps",room.getSteps());
            msg.put("color1",room.getColor1());
            msg.put("color2",room.getColor2());
        }
        //附带房间的系统事件（加入/离开/准备/开局/终局等），供巡查消息窗口完整展示
        List<JSONObject> events = new ArrayList<>();
        for(JSONObject e : room.getEvents()){
            events.add(e);
        }
        msg.put("events",events);
        //附带房间的历史聊天记录（谁、时间、内容）
        List<JSONObject> chats = new ArrayList<>();
        if(chatRecorder != null){
            List<ChatRoomRecord> records = chatRecorder.listByRoom(roomId,100);
            for(int i = records.size() - 1; i >= 0; i--){
                ChatRoomRecord record = records.get(i);
                JSONObject item = new JSONObject();
                item.put("player",record.getPlayer());
                item.put("nick",record.getNick());
                //展示给管理员的房间聊天窗口与普通成员一致：库里存原文，界面展示过滤后的内容
                item.put("content",SensitiveWordUtil.filterMsg(record.getContent()));
                item.put("time",record.getCreatedAt());
                chats.add(item);
            }
        }
        msg.put("chatRecords",chats);
        this.sendMessage(JSONUtil.toJsonStr(msg),session);
    }

    /** 管理员退出某房间的隐身观察 */
    private void handleAdminLeaveRoom(JSONObject data,String senderId,Session session){
        if(!requireAdmin(senderId,session)){
            return;
        }
        String roomId = data.getStr("roomId");
        if(roomId != null){
            Set<String> observers = adminObservers.get(roomId);
            if(observers != null){
                observers.remove(senderId);
                if(observers.isEmpty()){
                    adminObservers.remove(roomId);
                }
            }
        }
        if(session != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-leave-ok","已退出房间查看")),session);
        }
    }

    /** 管理员解散房间：通知房内所有用户并让其回到大厅 */
    private void handleAdminDismissRoom(JSONObject data,String senderId,Session session){
        if(!requireAdmin(senderId,session)){
            return;
        }
        String roomId = data.getStr("roomId");
        Room room = roomById(roomId);
        if(room == null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","房间已不存在")),session);
            return;
        }
        List<String> members = new ArrayList<>();
        synchronized (room){
            if(room.getPlayerId1() != null && !EMPTY_PLAYER.equals(room.getPlayerId1())){
                members.add(room.getPlayerId1());
            }
            if(room.getPlayerId2() != null && !EMPTY_PLAYER.equals(room.getPlayerId2())){
                members.add(room.getPlayerId2());
            }
            if(room.getSpectators() != null){
                members.addAll(new ArrayList<>(room.getSpectators().keySet()));
            }
        }
        //通知房内所有人并解除其房间关联，前端收到 room-closed 后回到大厅
        for(String member : members){
            this.sendMessageById(member,
                    JSONUtil.toJsonStr(new Message<>(200,"room-closed","房间已被管理员解散，已返回大厅")));
            playerRoomMap.remove(member);
        }
roomMap.remove(roomId);
        clearPendingActionsOfRoom(roomId);
        //其他管理员若正在隐身查看该房间，也应收到房间已关闭并回到大厅，避免停留在失效视图
        Set<String> observers = adminObservers.remove(roomId);
        if(observers != null){
            for(String adminId : new ArrayList<>(observers)){
                if(!Objects.equals(adminId,senderId)){
                    this.sendMessageById(adminId,
                            JSONUtil.toJsonStr(new Message<>(200,"admin-room-closed","房间已被管理员解散，已返回大厅")));
                }
            }
        }
        User admin = moderationService.findById(senderId);
        moderationService.logAction("dismiss-room",
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getLogin(),
                null,null,"解散房间 " + roomId);
        //清理该房间的聊天记录关联由留存策略统一处理，这里仅刷新大厅房间列表
        refreshLobby();
        if(session != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-action-ok","已解散房间")),session);
        }
    }

    /** 解除封号（仅管理员）：清除封号标记与绑定的 IP，该账号与其 IP 恢复可注册/登录 */
    private void handleAdminUnban(JSONObject data,String senderId,Session session){
        if(!requireAdmin(senderId,session)){
            return;
        }
        String targetId = data.getStr("targetId");
        User target = moderationService.findById(targetId);
        if(target == null || !Boolean.TRUE.equals(target.getBanned())){
            if(session != null){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","该账号未被封号")),session);
            }
            return;
        }
        if(!moderationService.updateUnbanned(target.getId())){
            if(session != null){
                this.sendMessage(JSONUtil.toJsonStr(new Message<>(400,"admin-fail","解除封号失败，请稍后重试")),session);
            }
            return;
        }
        User admin = moderationService.findById(senderId);
        moderationService.logAction("unban",
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getLogin(),
                target.getId(),target.getLogin(),"解除封号");
        //公共聊天通报：解封同样公示
        broadcastPublicNotice("【管理员】用户「" + target.getName() + "」已被解除封号。");
        if(session != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-action-ok","已解除封号 "+target.getName())),session);
        }
    }

    /** 已封号账号列表（仅管理员，供解封操作） */
    private void handleAdminBannedList(String senderId,Session session){
        if(!requireAdmin(senderId,session)){
            return;
        }
        List<JSONObject> list = new ArrayList<>();
        for(User user : moderationService.listBanned()){
            JSONObject item = new JSONObject();
            item.put("id",user.getId());
            item.put("login",user.getLogin());
            item.put("name",user.getName());
            item.put("nick",user.getName());
            item.put("registerIp",user.getRegisterIp());
            item.put("lastIp",user.getLastIp());
            item.put("banTime",user.getBanTime());
            list.add(item);
        }
        if(session != null){
            this.sendMessage(JSONUtil.toJsonStr(new Message<>(200,"admin-banned-list",list)),session);
        }
    }

    /**
     * 留存一条聊天记录（房间或公共）。
     * content 传入用户发送的原始内容（过滤敏感词之前），入库保存原文，供事后追溯；
     * 聊天窗口展示的仍是过滤后的内容。同时记录发言者当次发言的实时 IP。
     */
    private void recordChat(String senderId,String roomId,String scope,String rawContent){
        if(chatRecorder==null || rawContent==null || rawContent.trim().isEmpty()){
            return;
        }
        Integer player = null;
        try{
            player = Integer.valueOf(senderId);
        }catch(Exception ignored){
            //连接id非数字（异常客户端）时仅记录昵称与内容
        }
        String nick = onlineUserNickMap.get(senderId);
        if(nick==null){
            nick = nickOf(senderId);
        }
        String ip = ipOfUser(senderId);
        if("public".equals(scope)){
            chatRecorder.recordPublic(player,nick,rawContent,ip);
        }else{
            chatRecorder.recordRoom(player,nick,roomId,rawContent,ip);
        }
    }

    /**
     * 向观战者下发一条操作说明：观战者据此看到双方悔棋/认输/求和/重开等操作及其结果
     */
    private void notifySpectatorsNotice(String playerId,String content){
        String roomId = playerRoomMap.get(playerId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room == null){
            return;
        }
        JSONObject msg = new JSONObject();
        msg.put("type","spectate-notice");
        msg.put("content",content);
        notifySpectators(room,JSONUtil.toJsonStr(msg));
    }

    /**
     * 观战结束：提示观战者并让其自动退出（解除观战关联）
     */
    private void endSpectate(Room room,String content){
        if(room==null || room.getSpectators()==null || room.getSpectators().isEmpty()){
            return;
        }
        JSONObject end = new JSONObject();
        end.put("type","spectate-end");
        end.put("content",content);
        String json = JSONUtil.toJsonStr(end);
        //保留观战者与房间关联：由前端弹出“继续观看 / 离开”，
        //选择继续的观战者留在只读视图；选择离开时前端发送 quit-room 解除关联。
        for(String spectatorId : new ArrayList<>(room.getSpectators().keySet())){
            this.sendMessageById(spectatorId,json);
        }
    }

    /**
     * 观战人数变化时通知房间内的玩家与其它观战者
     */
    private void broadcastSpectatorUpdate(Room room){
        if(room==null){
            return;
        }
        JSONObject update = new JSONObject();
        update.put("type","spectator-update");
        update.put("roomView",roomView(room));
        String json = JSONUtil.toJsonStr(update);
        if(room.getPlayerId1()!=null) this.sendMessageById(room.getPlayerId1(),json);
        if(room.getPlayerId2()!=null) this.sendMessageById(room.getPlayerId2(),json);
        notifySpectators(room,json);
    }

    /**
     * 新一局开始时让观战者清空并同步棋盘
     */
    private void sendSpectatorReset(Room room){
        if(room==null || room.getSpectators()==null || room.getSpectators().isEmpty()){
            return;
        }
        JSONObject reset = new JSONObject();
        reset.put("type","spectate-reset");
        reset.put("roomView",roomView(room));
        reset.put("steps",room.getSteps());
        reset.put("color1",room.getColor1());
        reset.put("color2",room.getColor2());
        notifySpectators(room,JSONUtil.toJsonStr(reset));
    }

    /**
     * 房主切换房间可见性：公开时任何人可加入，私人时仅受邀者可加入
     */
    private void handleToggleRoomOpen(JSONObject data,String senderId){
        String roomId = playerRoomMap.get(senderId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room==null || !isRoomMember(room,senderId) || !Objects.equals(room.getPlayerId1(),senderId)){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"room-open-fail","只有房主可以设置房间可见性")));
            return;
        }
boolean open;
        String requested = data.getStr("open");
        String password = data.getStr("password");
        open = (requested==null) ? !room.isOpen() : Boolean.parseBoolean(requested);
        //密码哈希（PBKDF2）在锁外算好：这个散列要跑几十毫秒，放在 synchronized(room) 里
        //会阻塞该房间的落子/准备等操作。
        String newPasswordHash = null;
        if(!open){
            String candidate = (password==null) ? null : password.trim();
            if(candidate!=null && !candidate.isEmpty()){
                if(candidate.length()<MIN_ROOM_PASSWORD_LENGTH
                        || candidate.length()>MAX_ROOM_PASSWORD_LENGTH){
                    this.sendMessageById(senderId,JSONUtil.toJsonStr(
                            new Message<>(400,"room-open-fail",
                                    "房间密码长度必须为4-20位")));
                    return;
                }
                //仅保存加盐哈希，明文密码不进入房间对象、日志或下发给任何客户端
                newPasswordHash = PasswordUtil.hash(candidate);
            }
        }
        String rejectMessage = null;
        synchronized (room){
            if(!open){
                String candidate = (password==null) ? null : password.trim();
                boolean candidateEmpty = (candidate==null || candidate.isEmpty());
                boolean alreadyPrivateWithPassword = !room.isOpen()
                        && room.getPrivatePasswordHash()!=null;
                if(candidateEmpty && !alreadyPrivateWithPassword){
                    //从公开切到私人必须有密码；已经是私人且已有密码时允许幂等调用
                    rejectMessage = "设为私人房间必须设置房间密码（4-20位）";
                }else if(newPasswordHash!=null){
                    room.setPrivatePasswordHash(newPasswordHash);
                }
            }
            if(rejectMessage==null){
                room.setOpen(open);
                if(open){
                    //切回公开后清除旧密码，再次设私人时必须重新设置
                    room.setPrivatePasswordHash(null);
                }
            }
        }
        if(rejectMessage!=null){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"room-open-fail",rejectMessage)));
            return;
        }
        JSONObject changed = new JSONObject();
        changed.put("type","room-open-changed");
        changed.put("roomView",roomView(room));
        String json = JSONUtil.toJsonStr(changed);
        if(room.getPlayerId1()!=null) this.sendMessageById(room.getPlayerId1(),json);
        if(room.getPlayerId2()!=null) this.sendMessageById(room.getPlayerId2(),json);
notifySpectators(room,json);
        notifyAdminRoomView(room);
        //房间可见性变化会影响大厅列表
        refreshLobby();
    }

    /**
     * 房主踢人：只有房主可以踢出房内成员，被踢用户不能在再次加入该房间
     */
    private void handleKick(JSONObject data,String senderId){
        String targetId = data.getStr("targetId");
        String roomId = playerRoomMap.get(senderId);
        Room room = (roomId==null) ? null : roomMap.get(roomId);
        if(room==null || !Objects.equals(room.getPlayerId1(),senderId)){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"kick-fail","只有房主可以踢出成员")));
            return;
        }
        if(targetId==null){
            return;
        }
        if(Objects.equals(targetId,room.getPlayerId1())){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"kick-fail","不能踢出自己")));
            return;
        }
        //管理员不能被房主踢出：服务端兜底，前端同时隐藏该按钮
        if(moderationService != null && moderationService.isAdminUserId(targetId)){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"kick-fail","不能踢出管理员")));
            return;
        }
        boolean removed = false;
        synchronized (room){
            if(Objects.equals(room.getPlayerId2(),targetId)){
                //拉黑并移出房间，房间回到等待状态
                room.getKickedIds().add(targetId);
                if(room.getInvitedIds()!=null){
                    room.getInvitedIds().remove(targetId);
                }
                room.setPlayerId2(EMPTY_PLAYER);
                room.setPlayerName2("");
                //被踢出的玩家不再是这一局的对手，比分清零
                resetScore(room);
                room.setState("waiting");
                room.setStart(null);
                room.setSteps(null);
                //被踢出者不再是这一局的对手：终局快照一并清掉，新房客不会看到别人的棋局
                room.setFinalSteps(null);
                room.setWinCheck(0);
                room.setColor1(null);
                room.setColor2(null);
                //踢人后回到未准备状态（状态记为「房间中」）
                room.setReadyId1(false);
                room.setReadyId2(false);
                playerRoomMap.remove(targetId);
                removed = true;
            }
        }
        if(!removed){
            this.sendMessageById(senderId,JSONUtil.toJsonStr(
                    new Message<>(400,"kick-fail","该用户不在你的房间中")));
            return;
        }
        //通知被踢用户，由前端退回大厅
        this.sendMessageById(targetId,JSONUtil.toJsonStr(
                new Message<>(400,"kicked-from-room","你已被房主移出房间，无法再次加入")));
        addRoomEvent(room,"系统消息："+displayNameOf(targetId)+" 已被房主移出房间");
        //通知房主房间状态变化
        JSONObject success = new JSONObject();
        success.put("type","kick-success");
        success.put("targetId",targetId);
        success.put("roomView",roomView(room));
this.sendMessageById(room.getPlayerId1(),JSONUtil.toJsonStr(success));
        System.out.println("房主:"+senderId+"踢出了用户:"+targetId);
        //被踢同样意味着一方离开对局，观战者应收到结束提示并退出，避免停留在已结束的房间
endSpectate(room, "房内玩家已被移出，对局结束");
        broadcastSpectatorUpdate(room);
        notifyAdminRoomView(room);
        refreshLobby();
    }

    /**
     * 公共聊天通报：把管理员对用户的禁言/封号处置在全服公共聊天中公示，起到警戒作用。
     * 只做实时公示，不写入聊天记录表，避免污染用户聊天记录。
     */
    private void broadcastPublicNotice(String content){
        if(content==null || content.trim().isEmpty()){
            return;
        }
        JSONObject notice = new JSONObject();
        notice.put("type","public-notice");
        notice.put("content",content);
        notice.put("time",MyUtil.now());
        sendAllMessage(JSONUtil.toJsonStr(notice));
    }

    private void sendAllMessage(String message){
        //只广播给已鉴权的连接：未鉴权连接不应收到公共聊天、全服通告与在线列表
        for(Map.Entry<String, Session> entry : sessionMap.entrySet()){
            if(!authedSessions.containsKey(entry.getKey())){
                continue;
            }
            //单个 session 发送失败不影响其它 session
            this.sendMessage(message,entry.getValue());
        }
    }
    /**
     * 全服广播最新在线用户列表+在线总人数
     * 推送消息类型：online-user-list
     * 消息数据格式：{userList: [在线昵称数组], onlineCount: 在线总人数(int)}
     */
    private void sendOnlineUserList() {
        // 组装返回数据（含状态与房主标记）
        Message<Map<String, Object>> onlineMsg = new Message<>(200, "online-user-list", onlineListData());
        // 全大厅所有人推送
        sendAllMessage(JSONUtil.toJsonStr(onlineMsg));
    }
}