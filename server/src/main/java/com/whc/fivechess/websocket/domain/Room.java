package com.whc.fivechess.websocket.domain;

import cn.hutool.json.JSONObject;
import com.whc.fivechess.domain.Step;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对战房间
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Room {
    private String id = UUID.randomUUID().toString();//初始化房间号
    private String playerId1; //棋手1id  房主
    private String playerId2 = "-1"; //棋手2id   创建时没人
    private String playerName1; //棋手1 昵称
    private String playerName2 = ""; //棋手2昵称
    private String roomNo; //房间号：创建房间时由服务端自动分配，6 位数字（如 000001），释放后号码可复用
    private String state = "waiting"; //默认状态名字
    ///开局之后才会有的数据
    private String start = null; //开局时间
    private List<Step> steps = null;    //记录
    //终局步数快照：saveRecord 会清空 steps 用于下一局，这里保留最后一局棋盘，
    //供对局结束后才进入的观战者查看完整终局局面。
    private List<Step> finalSteps = null;
    private Integer winCheck = 0;    //初始为0 一方确认赢了之后+1  到2的时候保存对局记录
    //开局后由服务端记录的颜色：先后手与胜负都由服务端据此判定
    private String color1 = null; //棋手1的颜色
    private String color2 = null; //棋手2的颜色

///房间设置与成员管理
    private boolean open = true;    //房间是否公开：公开时任何人可加入，私人时需邀请或房间密码
    //私人房间密码的 PBKDF2 加盐哈希；仅保存在内存中，不写入日志、不下发给客户端
    @ToString.Exclude
    private String privatePasswordHash = null;
    // 房间内的系统事件（加入/离开/准备/开局/终局等），供管理员巡查时完整展示房间消息
    private List<JSONObject> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    // 房间会被多个 WebSocket 线程并发读写；使用并发集合避免遍历时被修改导致
    // ConcurrentModificationException（观战人数刷新、加入/退出房间等场景）。
    private Set<String> invitedIds = ConcurrentHashMap.newKeySet();             //已受邀（可进入私人房间）的用户id
    private Set<String> kickedIds = ConcurrentHashMap.newKeySet();              //被房主踢出且禁止再次加入该房间的用户id（永久，不随时间失效）
    private Map<String,String> spectators = new ConcurrentHashMap<>();//观战者：用户id -> 昵称
    ///准备状态：双方都准备后才算「游戏中」，任一方未准备记为「房间中」
    private boolean readyId1 = false;   //房主是否已准备
    private boolean readyId2 = false;   //房客是否已准备
    ///实时比分：当前两名玩家在本房间内各自赢下的局数
    //只在「同一对玩家」之间累计——有人离开、换人或被踢时清零，避免把上一对手的战绩带下去
    private int scoreId1 = 0;
    private int scoreId2 = 0;
}
