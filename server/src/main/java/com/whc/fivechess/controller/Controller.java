package com.whc.fivechess.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.whc.fivechess.component.AvatarService;
import com.whc.fivechess.component.ModerationService;
import com.whc.fivechess.component.TokenStore;
import com.whc.fivechess.domain.*;
import com.whc.fivechess.mapper.GameMapper;
import com.whc.fivechess.mapper.RecordMapper;
import com.whc.fivechess.mapper.UserMapper;
import com.whc.fivechess.security.AbuseGuard;
import com.whc.fivechess.security.ClientIpResolver;
import com.whc.fivechess.util.MyUtil;
import com.whc.fivechess.util.PasswordUtil;
import com.whc.fivechess.util.SensitiveWordUtil;
import com.whc.fivechess.websocket.component.SocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.List;

@RestController
public class Controller {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RecordMapper recordMapper;
    @Autowired
    private GameMapper gameMapper;
@Autowired
    private TokenStore tokenStore;
    @Autowired
    private AbuseGuard abuseGuard;
    @Autowired
    private ModerationService moderationService;
    @Autowired
    private AvatarService avatarService;

//账号、昵称、密码的业务长度限制（均不超过数据库字段长度）
    private static final int MIN_LOGIN_LENGTH = 3;
    private static final int MAX_LOGIN_LENGTH = 12;
    private static final int MAX_NAME_LENGTH = 10;
    //管理员专用名称：账号与昵称都不允许普通用户占用（不区分大小写）
    private static final String[] RESERVED_ADMIN_NAMES = {"管理员", "admin"};
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 50;
//历史对局每页条数（前端分页计算依赖该值）
    private static final int PAGE_SIZE = 10;
    //页码上限：避免恶意传入极大页码造成数据库超大 offset 查询
    private static final int MAX_PAGE_NUM = 100000;

    /**
     * 上传头像的缓存策略。
     * 头像地址形如 /avatar/{id}?v=版本号，换头像后地址变化，所以内容可视为不可变；
     * 这里的 max-age 是给「没带版本号直接访问」和 CDN 用的兜底，到期后靠 ETag 复验（命中即 304）。
     */
    private static final String AVATAR_CACHE_CONTROL = "public, max-age=604800";

    /**
     * 解析请求体，格式非法时返回 null，避免异常直接抛给容器导致 500
     */
    private JSONObject parseRequest(String request) {
        if (request == null || request.trim().isEmpty()) {
            return null;
        }
        try {
            return JSONUtil.parseObj(request);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 是否包含不可见字符：控制字符（换行、回车、制表、NUL 等）与零宽/双向控制符。
     *
     * 为什么必须拦：
     * 1) 账号与昵称会被写进日志、大厅列表、房间与聊天前缀，回车换行可以伪造日志行（日志注入）；
     * 2) 零宽字符能让「admin」与「admin+零宽」在视觉上完全一样，配合昵称唯一索引可以冒充他人。
     * 这些都只应出现在自由文本（聊天内容）里，标识符字段一律要求可见字符。
     */
    private boolean containsInvisibleChar(String value) {
        return MyUtil.containsInvisibleChar(value);
    }

    private String badRequest(String message) {
        return JSONUtil.toJsonStr(new ResponseDto<>(false, 400, message));
    }

    /** 是否纯十进制数字（长度上限 10 位，足以容纳 int 主键，同时挡掉超长垃圾路径） */
    private static boolean isDigitsOnly(String value){
        if(value == null || value.isEmpty() || value.length() > 10){
            return false;
        }
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c < '0' || c > '9'){
                return false;
            }
        }
        return true;
    }

    /**
     * 对局数据属于用户私有信息，需校验登录身份。
     * 响应里带上失效原因（restart=服务重启 / superseded=账号在别处登录 /
     * loggedout=已退出登录 / missing=未携带令牌），前端据此给出准确提示。
     */
    private String unauthorized(String reason) {
        ResponseDto<Object> dto = new ResponseDto<>(false, 401, "登录状态已失效，请重新登录！");
        JSONObject content = new JSONObject();
        content.put("reason", reason == null ? TokenStore.REASON_LOGGED_OUT : reason);
        dto.setContent(content);
        return JSONUtil.toJsonStr(dto);
    }

    private String unauthorized() {
        return unauthorized(null);
    }

    /**
     * 是否为管理员专用名称（账号或昵称都不能用）：
     * 「管理员」是系统管理员的展示昵称，「admin」是管理员账号名的默认值，均不区分大小写。
     * 与实际的 admin 配置项配合判断，避免管理员账号尚未创建时被普通用户抢占。
     */
    private boolean isReservedAdminName(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        //按「去掉空白与不可见字符后」比较：否则「管理 员」「管理\u200B员」这类变体
        //在界面上与被保护的「管理员」无法区分，却因为字符串不相等而被放行
        String normalized = MyUtil.stripInvisible(trimmed);
        for (String reserved : RESERVED_ADMIN_NAMES) {
            if (reserved.equalsIgnoreCase(trimmed) || reserved.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return moderationService != null && moderationService.isAdminLogin(trimmed);
    }

    /**
     * 校验请求中的登录令牌与用户id是否匹配
     */
    private boolean tokenValid(String id, String token) {
        return tokenStore.validate(id, token);
    }

@PostMapping(value = "/loginPost", produces = MediaType.APPLICATION_JSON_VALUE)
    public String login(@RequestBody(required = false) String request, HttpServletRequest httpRequest){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        String login = data.getStr("login");
        String password = data.getStr("password");
        if(login==null||login.trim().isEmpty()||password==null||password.isEmpty()){
            return badRequest("账户或密码为空！");
        }
        login = login.trim();

        //含不可见字符的账号不可能注册成功（注册侧已拒绝），这里直接按「账号或密码错误」处理，
        //避免这类输入进入查询与日志路径（响应与密码错误完全一致，不额外泄漏信息）
        if (containsInvisibleChar(login)) {
            return JSONUtil.toJsonStr(new ResponseDto<>(false,401,"账号或密码错误！"));
        }

        //登录限流：按 IP 与账号双维度限制尝试频率，连续失败达阈值后临时锁定账号
        //注意：封号只针对账号本身，不能因为同一出口 IP 上有被封账号就拒绝其他正常账号登录
        //（IP 封禁只用于阻止该 IP 继续注册新账号，见 register 接口）
        String loginIp = ClientIpResolver.resolve(httpRequest);
        String blockReason = abuseGuard.loginBlockReason(loginIp, login);
        if(blockReason != null){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,429,blockReason));
        }

QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("login",login);
        //用列表查询而不是 selectOne：历史脏数据（并发注册产生的同名账号）会让 selectOne 抛异常变成 500
        List<User> candidates = userMapper.selectList(query);
        if(candidates==null || candidates.isEmpty()){
            abuseGuard.recordLoginFailure(login);
            //账号不存在与密码错误使用完全相同的业务码和提示，避免通过响应差异枚举账号
            return JSONUtil.toJsonStr(new ResponseDto<>(false,401,"账号或密码错误！"));
        }
        //逐个校验密码，只有密码正确的那条记录才能登录
        User user = null;
        for(User candidate : candidates){
            //密码校验：新账号为加盐哈希，历史账号为无盐 MD5
            if(PasswordUtil.matches(password,candidate.getPassword())){
                user = candidate;
                break;
            }
        }
        if(user==null){
            abuseGuard.recordLoginFailure(login);
            return JSONUtil.toJsonStr(new ResponseDto<>(false,401,"账号或密码错误！"));
        }
        //封号拦截：该账号已被封号，即使密码正确也不允许登录
        if(Boolean.TRUE.equals(user.getBanned())){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,403,ModerationService.BAN_MESSAGE));
        }
        //登录成功：清除该账号的连续失败计数
        abuseGuard.recordLoginSuccess(login);
        //记录本次登录的来源 IP：封号时与注册 IP 一并封锁，覆盖「注册在 A 地、平时在 B 地登录」。
        //只在真正登录成功后写入（密码错误、已封号都走不到这里），解析失败或地址未变时不写库。
        recordLoginIp(user, loginIp);
        if(candidates.size() > 1){
            System.out.println("检测到重复账号：" + login + "（共 " + candidates.size() + " 条），已按密码匹配登录");
        }
        //历史 MD5 摘要在不改变用户密码的前提下，登录时升级为加盐哈希
        if(PasswordUtil.isLegacyHash(user.getPassword())){
            String upgraded = PasswordUtil.hash(password);
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setPassword(upgraded);
            userMapper.updateById(upgrade);
            user.setPassword(upgraded);
        }
//下发登录令牌：WebSocket 鉴权与对局数据接口都需要携带
        String token = tokenStore.issue(String.valueOf(user.getId()));
        //密码摘要不下发给客户端
        user.setPassword(null);
        user.setToken(token);
        //告知前端当前账号是否为管理员（决定是否显示禁言/封号等管理入口）
        user.setAdmin(moderationService.isAdminLogin(user.getLogin()));
        //头像：上传过的用数据库里的图（由 /avatar/{id} 提供）；管理员默认 avatar.webp；其余本次登录随机分配内置头像
        user.setAvatar(avatarService.avatarForLogin(user));
        return JSONUtil.toJsonStr(new ResponseDto<>(user));
    }

    /**
     * 记录「最近一次登录成功的来源 IP」。
     *
     * <p>该值只在登录成功路径写入，封号时与 {@code register_ip} 一起绑定，使管理员面对
     * 「注册在 A 地、平时在 B 地登录」的账号时，能同时封住两个已知出口。</p>
     *
     * <p>写入策略：解析失败（{@code unknown}）一律不写，避免污染封禁比对；
     * 与上次相同则不写，避免同一台机器反复登录产生无谓的 UPDATE。</p>
     */
    private void recordLoginIp(User user, String ip){
        String literal = ClientIpResolver.normalizeLiteral(ip);
        if(literal == null || literal.equals(user.getLastIp())){
            return;
        }
        try{
            User update = new User();
            update.setId(user.getId());
            update.setLastIp(literal);
            userMapper.updateById(update);
            user.setLastIp(literal);
        }catch(Exception e){
            //记录登录 IP 属于辅助信息，失败不能影响登录本身
            System.out.println("更新最近登录 IP 失败：" + e.getMessage());
        }
    }

@PostMapping(value = "/registerPost", produces = MediaType.APPLICATION_JSON_VALUE)
    public String register(@RequestBody(required = false) String request, HttpServletRequest httpRequest){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        //封号拦截：被封号账号绑定的 IP 不允许再注册新账号
        String registerIp = ClientIpResolver.resolve(httpRequest);
        if(moderationService.isBannedIp(registerIp)){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,403,ModerationService.BAN_MESSAGE));
        }
        //注册限流：限制同一 IP 在单位时间内的注册次数，防批量注册
        if(!abuseGuard.allowRegister(registerIp)){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,429,"操作过于频繁，请稍后再试！"));
        }
        String id = data.getStr("id");
        String name = data.getStr("name");
        String password = data.getStr("password");

        // 1. 单独判断每一项是否为空，单独提示
        if (id == null || id.trim().isEmpty()) {
            return badRequest("账号不能为空！");
        }
        if (password == null || password.trim().isEmpty()) {
            return badRequest("密码不能为空！");
        }
        if (name == null || name.trim().isEmpty()) {
            return badRequest("昵称不能为空！");
        }

id = id.trim();
        name = name.trim();

        //「管理员 / admin」是系统管理员专用的账号名与昵称：即使管理员账号尚未创建、
        //或昵称唯一索引暂时失效，普通用户也不能占用，否则会在大厅、聊天与房间内冒充管理员。
        //账号与昵称分别给出各自的提示，避免用户不知道该改哪一项。
        if (isReservedAdminName(id)) {
            return JSONUtil.toJsonStr(new ResponseDto<>(false, 403,
                    "该账号为管理员账号，禁止使用！"));
        }
        if (isReservedAdminName(name)) {
            return JSONUtil.toJsonStr(new ResponseDto<>(false, 403,
                    "该昵称为管理员昵称，禁止使用！"));
        }

        //不可见字符：账号/昵称会被写进日志与界面，换行可伪造日志行，零宽字符可伪装同名冒充他人
        if (containsInvisibleChar(id)) {
            return badRequest("账号包含不可见字符，请使用可见字符！");
        }
        if (containsInvisibleChar(name)) {
            return badRequest("昵称包含不可见字符，请使用可见字符！");
        }

        // 2. 长度校验
        if(id.length() < MIN_LOGIN_LENGTH){
            return badRequest("账号长度不能小于3位！");
        }
        if(password.length() < MIN_PASSWORD_LENGTH){
            return badRequest("密码长度不能小于6位！");
        }
if(id.length() > MAX_LOGIN_LENGTH){
            return badRequest("账号长度不能超过12位！");
        }
        if(name.length() > MAX_NAME_LENGTH){
            return badRequest("昵称长度不能超过10位！");
        }
        if(password.length() > MAX_PASSWORD_LENGTH){
            return badRequest("密码长度不能超过50位！");
        }

        //注册账号/昵称命中违规词时直接拒绝，并按字段分别提示；只提示包含违规内容，不返回具体命中的词
        if(SensitiveWordUtil.containsSensitiveWord(id)){
            return badRequest("账号包含违规内容，请修改后重试！");
        }
        if(SensitiveWordUtil.containsSensitiveWord(name)){
            return badRequest("昵称包含违规内容，请修改后重试！");
        }

// 校验账号是否已注册。
        // 使用列表查询而不是 selectOne：历史脏数据（并发注册残留的同名账号）会让 selectOne 抛
        // TooManyResultsException，最终变成 500，注册接口不应因这类数据直接报错。
        // 冲突统一用 409：401 是「登录状态失效」的专用码，前端对 401 会走清登录态回登录页，
        // 注册冲突若复用 401，任何按码分支的调用方都会把「昵称重复」误判成掉线。
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("login",id);
        List<User> sameLoginUsers = userMapper.selectList(query);
        if(sameLoginUsers!=null && !sameLoginUsers.isEmpty()){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,409,"账号已被注册！"));
        }
        // 昵称不允许重复
        QueryWrapper<User> nameQuery = new QueryWrapper<>();
        nameQuery.eq("name",name);
        if(userMapper.selectCount(nameQuery) > 0){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,409,"昵称已被使用！"));
        }
        User user1 = new User();
        user1.setLogin(id);
        user1.setName(name);
        user1.setPassword(PasswordUtil.hash(password));
        user1.setTime(MyUtil.now());
        //记录注册来源 IP：封号时优先绑定它。只在注册这一刻取一次，
        //这样即使用户随后离线、换网络，管理员封号时仍能封住「他注册时用的那个 IP」。
        user1.setRegisterIp("unknown".equals(registerIp) ? null : registerIp);
        try{
            userMapper.insert(user1);
        }catch (DuplicateKeyException e){
            //并发注册下由数据库唯一索引兜底：按真正冲突的字段给出准确提示
            QueryWrapper<User> loginQuery = new QueryWrapper<>();
            loginQuery.eq("login",id);
            if(userMapper.selectCount(loginQuery) > 0){
                return JSONUtil.toJsonStr(new ResponseDto<>(false,409,"账号已被注册！"));
            }
            return JSONUtil.toJsonStr(new ResponseDto<>(false,409,"昵称已被使用！"));
        }
        return JSONUtil.toJsonStr(new ResponseDto<>("注册成功！"));
    }

    /**
     * 登录态探测（页面打开、回到前台、断线重连前调用）。
     * 只做内存校验、不查数据库，用于让前端在「页面刚打开」或「连接已断开」时
     * 立刻拿到明确结论：登录还有效，还是已经被顶号/服务重启清空了。
     *
     * 返回 content：{valid:true/false, reason:ok|restart|superseded|loggedout|missing|expired, bootId}
     */
    @PostMapping(value = "/sessionCheck", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sessionCheck(@RequestBody(required = false) String request, HttpServletRequest httpRequest){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        //本接口不需要登录、且被排除在 HTTP 总限流之外（页面打开/切回前台/重连都要立刻拿到结论），
        //因此单独按 IP 限流：否则任何人都能无限调用它做探测或消耗服务端资源。
        String checkIp = ClientIpResolver.resolve(httpRequest);
        if(!abuseGuard.allowSessionCheck(checkIp)){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,429,"操作过于频繁，请稍后再试！"));
        }
        String userId = data.getStr("id");
        if(userId==null || userId.trim().isEmpty()){
            userId = data.getStr("userId");
        }
        String token = data.getStr("token");
        String reason = tokenStore.invalidReason(userId, token);
        JSONObject content = new JSONObject();
        content.put("valid", reason == null);
        content.put("reason", reason == null ? TokenStore.REASON_OK : reason);
        // 当前服务实例的启动标识：前端可据此判断「服务换了一个实例」
        content.put("bootId", tokenStore.getBootId());
        return JSONUtil.toJsonStr(new ResponseDto<>(content));
    }

    /**
     * 查询当前登录用户的头像地址。
     * 页面刷新、断线重连后前端用它恢复头像（登录时分配的随机头像在本次会话内保持不变）。
     *
     * 返回 content：{avatar}
     */
    @PostMapping(value = "/avatarPost", produces = MediaType.APPLICATION_JSON_VALUE)
    public String avatar(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data == null){
            return badRequest("请求参数格式错误！");
        }
        String id = data.getStr("id");
        String token = data.getStr("token");
        if(id == null || id.trim().isEmpty() || !tokenValid(id, token)){
            return unauthorized(tokenStore.invalidReason(id, token));
        }
        JSONObject content = new JSONObject();
        content.put("avatar", avatarService.avatarOf(id));
        return JSONUtil.toJsonStr(new ResponseDto<>(content));
    }

    /**
     * 读取用户上传的头像图片（公开接口，不需要令牌：图片本身会出现在 img 标签里）。
     * 前后端分离部署时前端无法访问后端磁盘，所以图片存在数据库、由本接口输出。
     *
     * 缓存策略：地址带 ?v=版本号，内容不可变，因此允许浏览器与 CDN 长时间缓存。
     * 支持条件请求（If-None-Match）：浏览器缓存过期后带 ETag 复验，命中直接回 304。
     * 304 分支只查版本号这一列，不读 content 大字段——否则「判定未修改」本身就要把
     * 整张图（上限 3MB）从库里拉回来再丢掉，头像访问量一大就是纯粹的浪费。
     *
     * @param userId      用户id（数据库主键）
     * @param ifNoneMatch 浏览器缓存的 ETag；首次访问时为空
     * @return 图片字节；版本号未变时返回 304；没有上传记录时返回 404，前端据此回退默认头像
     */
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatarImage(@PathVariable("userId") String userId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch){
        //只接受纯数字的用户 id。参数化查询本身不会被注入，但把 "1;DROP TABLE user"、"1abc"
        //这类值传下去，数据库会按隐式类型转换当成 1 处理，于是任何以数字开头的怪路径
        //都能读到该用户的头像——行为上不合预期，也让缓存键变得不可控。
        if(!isDigitsOnly(userId)){
            return ResponseEntity.notFound().build();
        }
        //条件请求短路：只有带了 ETag 才多查这一次版本号，首次访问仍是一条查询
        Long version = (ifNoneMatch == null || ifNoneMatch.isEmpty())
                ? null : avatarService.versionOf(userId);
        if(version != null){
            String etag = "\"" + version + "\"";
            if(etagMatches(ifNoneMatch, etag)){
                HttpHeaders notModified = new HttpHeaders();
                notModified.setCacheControl(AVATAR_CACHE_CONTROL);
                notModified.setETag(etag);
                notModified.add("X-Content-Type-Options", "nosniff");
                //304 必须显式回带 ETag 与 Cache-Control：浏览器据此刷新缓存有效期，否则会立刻再问一次
                return new ResponseEntity<>(notModified, HttpStatus.NOT_MODIFIED);
            }
        }
        UserAvatar row = avatarService.imageOf(userId);
        if(row == null || row.getContent() == null || row.getContent().length == 0){
            return ResponseEntity.notFound().build();
        }
        String contentType = row.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            contentType = "image/jpeg";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setCacheControl(AVATAR_CACHE_CONTROL);
        //类型已按文件头校验过，禁止浏览器再猜类型
        headers.add("X-Content-Type-Options", "nosniff");
        if(row.getVersion() != null){
            headers.setETag("\"" + row.getVersion() + "\"");
        }
        return new ResponseEntity<>(row.getContent(), headers, HttpStatus.OK);
    }

    /**
     * 判断 If-None-Match 是否命中当前 ETag。
     *
     * 按 RFC 9110 用弱比较：忽略 W/ 前缀，支持逗号分隔的多个标签与 *。
     * 不能直接 equals，因为浏览器/代理可能发 W/"v"、也可能把多个缓存副本的 ETag 一起发过来。
     */
    private static boolean etagMatches(String ifNoneMatch, String etag){
        if(ifNoneMatch == null){
            return false;
        }
        String value = ifNoneMatch.trim();
        if("*".equals(value)){
            return true;
        }
        for(String part : value.split(",")){
            String tag = part.trim();
            if(tag.startsWith("W/") || tag.startsWith("w/")){
                tag = tag.substring(2).trim();
            }
            if(tag.equals(etag)){
                return true;
            }
        }
        return false;
    }

    /**
     * 更换头像：请求体 {id, token, image}，image 为 data:image/...;base64,... 的图片数据。
     * 图片存入数据库 user_avatar 表，由 /avatar/{id} 对外提供，不再写服务器文件。
     *
     * 返回 content：{avatar}（带版本号的新头像地址）
     */
    @PostMapping(value = "/avatarUploadPost", produces = MediaType.APPLICATION_JSON_VALUE)
    public String uploadAvatar(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data == null){
            return badRequest("请求参数格式错误！");
        }
        String id = data.getStr("id");
        String token = data.getStr("token");
        if(id == null || id.trim().isEmpty() || !tokenValid(id, token)){
            return unauthorized(tokenStore.invalidReason(id, token));
        }
        try{
            String avatar = avatarService.saveAvatar(id, data.getStr("image"));
            JSONObject content = new JSONObject();
            content.put("avatar", avatar);
            return JSONUtil.toJsonStr(new ResponseDto<>(content));
        }catch(IllegalArgumentException e){
            return badRequest(e.getMessage());
        }catch(Exception e){
            System.out.println("头像保存失败：" + e.getMessage());
            return JSONUtil.toJsonStr(new ResponseDto<>(false,500,"头像保存失败，请稍后重试！"));
        }
    }

/**
     * 退出登录：注销服务端令牌。
     * 仅清除前端 localStorage 并不会让旧令牌失效，令牌一旦被截获仍可继续调用接口；
     * 因此退出时同步在服务端注销。接口按幂等处理：令牌无效也返回成功。
     */
    @PostMapping(value = "/logoutPost", produces = MediaType.APPLICATION_JSON_VALUE)
    public String logout(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data == null){
            return badRequest("请求参数格式错误！");
        }
        String token = data.getStr("token");
        String userId = tokenStore.userIdOf(token);
        if(userId != null){
            tokenStore.revoke(userId);
            //退出后忘掉本次会话分配的头像：下次登录重新随机（上传过本人的则仍用自己的文件）
            avatarService.forget(userId);
            //已建立的 WebSocket 不会因 HTTP 令牌撤销而自动失效，这里主动断开，
            //避免"点了退出登录、长连接仍在收消息和发言"
            SocketServer.closeUserSessions(userId, TokenStore.REASON_LOGGED_OUT);
        }
        return JSONUtil.toJsonStr(new ResponseDto<>("已退出登录！"));
    }

    //以分页的方式获取对局列表
    @PostMapping(value = "/getPage", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getPage(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        String id = data.getStr("id");
        String pageNum = data.getStr("pageNum"); //页码

        if(id==null||id.trim().isEmpty()){
            return badRequest("用户id不能为空！");
        }
        id = id.trim();
        if(!tokenValid(id,data.getStr("token"))){
            return unauthorized(tokenStore.invalidReason(id, data.getStr("token")));
        }
        int current;
        try{
            current = Integer.parseInt(pageNum);
        }catch (Exception e){
            return badRequest("页码格式错误！");
        }
if(current < 1){
            return badRequest("页码不能小于1！");
        }
        if(current > MAX_PAGE_NUM){
            return badRequest("页码过大！");
        }

        Page<Record> page = new Page<>(current,PAGE_SIZE);
        QueryWrapper<Record> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("player1",id).or().eq("player2",id).orderByDesc("start","id");
        return JSONUtil.toJsonStr(new ResponseDto<>(recordMapper.selectPage(page,queryWrapper)));
    }
    //获取对局列表
    @PostMapping(value = "/getRecords", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getRecords(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        String id = data.getStr("id");
        if(id==null||id.trim().isEmpty()){
            return badRequest("用户id不能为空！");
        }
        id = id.trim();
        if(!tokenValid(id,data.getStr("token"))){
            return unauthorized(tokenStore.invalidReason(id, data.getStr("token")));
        }
        //查找该用户的全部对局
        QueryWrapper<Record> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("player1",id).or().eq("player2",id).orderByDesc("start","id");
        List<Record> records = recordMapper.selectList(queryWrapper);
        return JSONUtil.toJsonStr(new ResponseDto<>(records));
    }

    //获取某一对局详情
    @PostMapping(value = "/getGameData", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getGameData(@RequestBody(required = false) String request){
        JSONObject data = parseRequest(request);
        if(data==null){
            return badRequest("请求参数格式错误！");
        }
        String id = data.getStr("id");
        if(id==null||id.trim().isEmpty()){
            return badRequest("对局id不能为空！");
        }
        id = id.trim();
        //令牌必须有效，且只有该局的两位对局者可以查看对局详情
        String requesterId = tokenStore.userIdOf(data.getStr("token"));
        if(requesterId==null){
            return unauthorized(tokenStore.invalidReason(null, data.getStr("token")));
        }

        Game game = gameMapper.selectById(id);
        if(game==null){
            return badRequest("未找到对局！");
        }
        List<Record> owners = recordMapper.selectList(new QueryWrapper<Record>().eq("data",id));
        boolean owner = false;
        for(Record record : owners){
            if(requesterId.equals(String.valueOf(record.getPlayer1()))
                    || requesterId.equals(String.valueOf(record.getPlayer2()))){
                owner = true;
                break;
            }
        }
        if(!owner){
            return JSONUtil.toJsonStr(new ResponseDto<>(false,403,"无权查看该对局！"));
        }

        ByteArrayInputStream bais;
        WhitelistObjectInputStream in = null;
        Steps steps = null;
        try{
            bais = new ByteArrayInputStream(game.getData());
            in = new WhitelistObjectInputStream(bais);
            steps  = (Steps)in.readObject();
        }catch (Exception e){
            e.printStackTrace();
        }
        finally{
            if(in != null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if(steps==null){
            return badRequest("对局查找出错！");
        }
        return JSONUtil.toJsonStr(new ResponseDto<>(steps));
    }
	
	/**
     * 存活探针：供 UptimeRobot 等外部监控用 GET 定期访问。
     * 固定返回 HTTP 200 + "ok"，不查数据库、不校验令牌、不计入限流，
     * 避免数据库变慢或业务限流导致 429 被误判为服务宕机。
	 * 不需要存活检测时注释
     */
    @GetMapping("/health")
    public String health(){
        return "UP,服务正常运行中";
    }

    /**
     * 带类白名单的反序列化流。
     *
     * 对局棋盘以 Java 序列化二进制存库，读回来时必须限制可反序列化的类：
     * 原生 ObjectInputStream 会按流里声明的类名加载并执行其 readObject，
     * 一旦库里出现被污染的数据（历史脚本、越权写库、备份被替换），
     * 就可能触发任意类的构造/回调。这里只放行对局结构真正用到的几个类。
     */
    private static final class WhitelistObjectInputStream extends ObjectInputStream {
        private static final java.util.Set<String> ALLOWED = new java.util.HashSet<>(java.util.Arrays.asList(
                "com.whc.fivechess.domain.Steps",
                "com.whc.fivechess.domain.Step",
                "java.util.ArrayList",
                "java.lang.Integer",
                "java.lang.Number",
                "java.lang.String"
        ));

        private WhitelistObjectInputStream(ByteArrayInputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(java.io.ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            if (!ALLOWED.contains(desc.getName())) {
                throw new java.io.InvalidClassException("不允许反序列化的类", desc.getName());
            }
            return super.resolveClass(desc);
        }
    }
}