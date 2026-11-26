package com.whc.fivechess.component;

import com.whc.fivechess.domain.User;
import com.whc.fivechess.domain.UserAvatar;
import com.whc.fivechess.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 头像服务。
 *
 * 头像来源分三种：
 * 1. 管理员：未上传时固定使用前端静态文件 /static/image/avatar.webp；
 * 2. 普通用户上传过：图片二进制存在数据库 user_avatar 表，由后端 /avatar/{id} 提供；
 * 3. 普通用户未上传过：每次登录从内置头像清单里随机分配一张，仅在本次会话内固定。
 *
 * 关键设计：后端不读写任何本地图片文件。
 * 前后端分离部署时（例如前端在 nginx、后端在容器平台），后端进程的磁盘与前端静态目录
 * 不是同一块盘，写文件既到不了前端、容器重启还会丢，所以上传结果统一入库。
 * 内置头像的图片本身仍由前端静态服务托管，后端只需要文件名清单即可完成随机分配。
 */
@Component
public class AvatarService {

    /** 管理员默认头像，也是普通用户头像缺失时的兜底（前端静态文件，WebP 格式） */
    public static final String DEFAULT_AVATAR = "/static/image/avatar.webp";
    /** 内置头像目录对应的访问前缀（前端静态目录） */
    public static final String BUILTIN_PREFIX = "/static/image/touxiang/";
    /** 上传头像的后端访问前缀，完整地址为 /avatar/{用户id}?v=版本号 */
    public static final String AVATAR_API_PREFIX = "/avatar/";

    /** 内置头像清单（classpath 资源） */
    private static final String BUILTIN_MANIFEST = "avatar-builtin.txt";

    /** 单张头像最大字节数 */
    private static final long MAX_IMAGE_BYTES = 3L * 1024 * 1024L;
    /** 单边最大像素：超过直接拒绝 */
    private static final int MAX_IMAGE_SIDE = 4096;
    /** 总像素上限：4096 × 4096，约 1600 万像素 */
    private static final long MAX_IMAGE_PIXELS = (long) MAX_IMAGE_SIDE * MAX_IMAGE_SIDE;
    /** 允许的图片类型（按文件头判定，不信任客户端声明） */
    private static final Set<String> ALLOWED_EXT = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp"));
    /** 单账号上传频率：窗口内最多几次 */
    private static final int UPLOAD_LIMIT = 12;
    private static final long UPLOAD_WINDOW_MS = 5L * 60 * 1000L;

    /** 前端静态站点地址，留空表示与页面同域（默认，前后端同域或前端反代时适用） */
    @Value("${fivechess.avatar.static-base:}")
    private String staticBaseConfig;
    /** 后端对外地址，用于生成上传头像的绝对地址；留空则输出相对路径 */
    @Value("${fivechess.avatar.api-base:}")
    private String apiBaseConfig;
    /** 可选：内置头像目录，仅本地开发时用于兜底扫描（清单缺失时） */
    @Value("${fivechess.avatar.builtin-dir:}")
    private String builtinDirConfig;

    private String staticBase = "";
    private String apiBase = "";
    private Path builtinDir;

    /** 内置头像文件名列表（启动时读取清单） */
    private final List<String> builtinFiles = new CopyOnWriteArrayList<>();
    /** 本次登录分配的随机头像：用户id -> 头像地址 */
    private final Map<String, String> sessionAvatars = new ConcurrentHashMap<>();
    /** 上传频率记录：用户id -> 最近上传时间戳 */
    private final Map<String, long[]> uploadRecords = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private AvatarStore avatarStore;

    @PostConstruct
    public void init(){
        staticBase = normalizeBase(staticBaseConfig);
        apiBase = normalizeBase(apiBaseConfig);
        loadBuiltinFiles();
        String builtinLocation = staticBase.isEmpty()
                ? BUILTIN_PREFIX + "（页面同域）" : staticBase + BUILTIN_PREFIX;
        System.out.println("[头像] 存储方式：数据库（user_avatar 表），不读写本地图片文件");
        System.out.println("[头像] 内置头像清单：" + builtinFiles.size() + " 张（图片由前端托管：" + builtinLocation + "）");
        System.out.println("[头像] 上传头像地址前缀：" + (apiBase.isEmpty()
                ? "（未配置，输出相对路径 " + AVATAR_API_PREFIX + "{id}）" : apiBase));
        if(apiBase.isEmpty()){
            System.out.println("[头像] 提示：前后端不同域名时请配置 FIVECHESS_AVATAR_API_BASE，"
                    + "否则浏览器会按页面域名去取 /avatar/{id} 而 404");
        }
    }

    /** 去掉末尾斜杠，避免拼出 /// 这种地址 */
    private String normalizeBase(String base){
        if(base == null){
            return "";
        }
        String text = base.trim();
        while(text.endsWith("/")){
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 读取内置头像清单：优先 classpath 资源，缺失时回退到本地目录扫描（仅开发环境）。
     */
    private void loadBuiltinFiles(){
        List<String> names = new ArrayList<>();
        try(InputStream in = getClass().getClassLoader().getResourceAsStream(BUILTIN_MANIFEST)){
            if(in != null){
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while((line = reader.readLine()) != null){
                    String name = line.trim();
                    if(name.isEmpty() || name.startsWith("#")){
                        continue;
                    }
                    if(hasImageExt(name)){
                        names.add(name);
                    }
                }
            }
        }catch(IOException e){
            System.out.println("[头像] 内置头像清单读取失败：" + e.getMessage());
        }
        if(names.isEmpty() && builtinDirConfig != null && !builtinDirConfig.trim().isEmpty()){
            builtinDir = Paths.get(builtinDirConfig.trim()).toAbsolutePath().normalize();
            if(Files.isDirectory(builtinDir)){
                try(DirectoryStream<Path> stream = Files.newDirectoryStream(builtinDir)){
                    for(Path file : stream){
                        String name = file.getFileName().toString();
                        if(hasImageExt(name)){
                            names.add(name);
                        }
                    }
                }catch(IOException e){
                    System.out.println("[头像] 内置头像目录读取失败：" + e.getMessage());
                }
            }
        }
        Collections.sort(names);
        builtinFiles.clear();
        builtinFiles.addAll(names);
        if(builtinFiles.isEmpty()){
            System.out.println("[头像] 警告：内置头像清单为空，未上传头像的用户将统一使用默认头像");
        }
    }

    private boolean hasImageExt(String name){
        int dot = name.lastIndexOf('.');
        return dot > 0 && ALLOWED_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    /**
     * 登录时确定本次会话的头像：上传过的用数据库里的图；管理员用默认图；其余随机分配一张内置头像。
     */
    public String avatarForLogin(User user){
        if(user == null){
            return defaultAvatarUrl();
        }
        String userId = String.valueOf(user.getId());
        String avatar = resolveByRole(user);
        remember(userId, avatar);
        return avatar;
    }

    /**
     * 按角色与已有记录解析头像（不涉及随机分配）
     */
    private String resolveByRole(User user){
        if(user == null){
            return defaultAvatarUrl();
        }
        Long version = avatarStore == null ? null : avatarStore.versionOf(user.getId());
        if(version != null){
            return uploadedUrl(String.valueOf(user.getId()), version);
        }
        if(isAdmin(user)){
            return defaultAvatarUrl();
        }
        return randomBuiltin();
    }

    private boolean isAdmin(User user){
        if(moderationService == null || user == null){
            return false;
        }
        return moderationService.isAdminLogin(user.getLogin());
    }

    /**
     * 获取指定用户当前展示的头像（房间卡片、大厅等「别人看到的头像」走这里）。
     * 已在线用户取登录时分配的结果；未命中则查库，查不到用默认头像兜底。
     */
    public String avatarOf(String userId){
        if(userId == null || userId.trim().isEmpty() || "-1".equals(userId.trim())){
            return defaultAvatarUrl();
        }
        String key = userId.trim();
        String cached = sessionAvatars.get(key);
        if(cached != null){
            return cached;
        }
        String resolved = defaultAvatarUrl();
        try{
            User user = userMapper == null ? null : userMapper.selectById(Integer.valueOf(key));
            if(user != null){
                resolved = resolveByRole(user);
            }
        }catch(Exception e){
            //id 非数字或查询失败：保持默认头像，不影响大厅展示
        }
        remember(key, resolved);
        return resolved;
    }

    /**
     * 记录本次会话的头像。会话缓存只为让「别人看到的头像」与本人一致，
     * 条目数与登录过的账号数同量级，这里加一个上限兜底，避免长期运行无限增长。
     */
    private void remember(String userId, String avatar){
        if(sessionAvatars.size() > 20000){
            sessionAvatars.clear();
        }
        sessionAvatars.put(userId, avatar);
    }

    /** 登录分配结果随账号下线清理，避免内存长期堆积 */
    public void forget(String userId){
        if(userId != null){
            sessionAvatars.remove(userId);
        }
    }

    /** 读取用户上传的头像二进制，供 GET /avatar/{id} 使用；没有记录返回 null */
    public UserAvatar imageOf(String userId){
        Integer id = parseUserId(userId);
        return id == null || avatarStore == null ? null : avatarStore.find(id);
    }

    /**
     * 只查头像版本号，供 GET /avatar/{id} 处理条件请求（If-None-Match）时短路使用。
     *
     * 与 {@link #imageOf(String)} 的区别在于不读取 content 大字段：
     * 浏览器带着 ETag 来复验时，只要版本号没变就能直接回 304，
     * 不必把整张图片（上限 3MB）从库里捞出来再丢掉。
     *
     * @return 版本号；null 表示该用户没有上传过头像，或头像表暂不可用（调用方应退回完整查询）
     */
    public Long versionOf(String userId){
        Integer id = parseUserId(userId);
        return id == null || avatarStore == null ? null : avatarStore.versionOf(id);
    }

    private Integer parseUserId(String userId){
        if(userId == null || userId.trim().isEmpty()){
            return null;
        }
        try{
            int id = Integer.parseInt(userId.trim());
            return id > 0 ? id : null;
        }catch(NumberFormatException e){
            return null;
        }
    }

    private String randomBuiltin(){
        if(builtinFiles.isEmpty()){
            loadBuiltinFiles();
        }
        if(builtinFiles.isEmpty()){
            return defaultAvatarUrl();
        }
        return urlWith(staticBase, BUILTIN_PREFIX + builtinFiles.get(random.nextInt(builtinFiles.size())));
    }

    private String defaultAvatarUrl(){
        return urlWith(staticBase, DEFAULT_AVATAR);
    }

    /** 上传头像地址：后端接口 + 版本号（换头像后浏览器立即刷新） */
    private String uploadedUrl(String userId, long version){
        return urlWith(apiBase, AVATAR_API_PREFIX + userId) + "?v=" + version;
    }

    private String urlWith(String base, String path){
        return base == null || base.isEmpty() ? path : base + path;
    }

    /**
     * 保存用户上传的头像：图片写入数据库，库里的 user.avatar 记录访问路径。
     *
     * @param userId  用户id（数据库主键，天然是数字）
     * @param dataUrl 前端传来的 data:image/...;base64,... 字符串
     * @return 新头像地址（带版本号）
     */
    public String saveAvatar(String userId, String dataUrl) throws IOException{
        if(userId == null || userId.trim().isEmpty()){
            throw new IllegalArgumentException("登录状态无效，请重新登录！");
        }
        String key = userId.trim();
        if(!allowUpload(key)){
            throw new IllegalArgumentException("头像更换太频繁，请稍后再试！");
        }
        byte[] bytes = decodeDataUrl(dataUrl);
        String ext = detectExt(bytes);
        if(ext == null){
            throw new IllegalArgumentException("只能上传 JPG / PNG / GIF / WEBP 格式的图片！");
        }
        //只限字节数不够：几 MB 的压缩图可以解压成上亿像素（decompression bomb），
        //所有展示该头像的客户端都会因为解码这张图而占满内存，所以还要限制像素尺寸
        validateDimensions(bytes, ext);
        Integer id = parseUserId(key);
        User user = (id == null || userMapper == null) ? null : userMapper.selectById(id);
        if(user == null){
            throw new IllegalArgumentException("登录状态无效，请重新登录！");
        }
        if(avatarStore == null){
            throw new IllegalStateException("头像存储未就绪");
        }
        long version = avatarStore.save(user.getId(), bytes, contentTypeOf(ext));

        String path = AVATAR_API_PREFIX + user.getId();
        User update = new User();
        update.setId(user.getId());
        update.setAvatar(path);
        userMapper.updateById(update);

        String url = uploadedUrl(String.valueOf(user.getId()), version);
        sessionAvatars.put(key, url);
        return url;
    }

    /** 按扩展名给出响应 Content-Type（类型已由文件头校验过） */
    private String contentTypeOf(String ext){
        switch(ext){
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }

    private boolean allowUpload(String userId){
        long now = System.currentTimeMillis();
        long[] record = uploadRecords.get(userId);
        if(record == null){
            uploadRecords.put(userId, new long[]{now, 1});
            return true;
        }
        synchronized(record){
            if(now - record[0] > UPLOAD_WINDOW_MS){
                record[0] = now;
                record[1] = 1;
                return true;
            }
            if(record[1] >= UPLOAD_LIMIT){
                return false;
            }
            record[1]++;
            return true;
        }
    }

    /**
     * 解析 data:image/...;base64,xxx，并做大小校验
     */
    private byte[] decodeDataUrl(String dataUrl){
        if(dataUrl == null){
            throw new IllegalArgumentException("请先选择图片！");
        }
        String text = dataUrl.trim();
        int comma = text.indexOf(',');
        if(comma < 0 || !text.startsWith("data:image/")){
            throw new IllegalArgumentException("图片格式无法识别，请重新选择！");
        }
        String base64 = text.substring(comma + 1);
        byte[] bytes;
        try{
            bytes = Base64.getDecoder().decode(base64);
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException("图片数据损坏，请重新选择！");
        }
        if(bytes.length == 0){
            throw new IllegalArgumentException("请先选择图片！");
        }
        if(bytes.length > MAX_IMAGE_BYTES){
            throw new IllegalArgumentException("图片过大，请选择 3MB 以内的图片！");
        }
        return bytes;
    }

    /**
     * 按文件头判断真实图片类型，避免把任意文件改名成 .jpg 上传
     */
    private String detectExt(byte[] bytes){
        if(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF){
            return "jpg";
        }
        if(bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'){
            return "png";
        }
        if(bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'){
            return "gif";
        }
        if(bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P'){
            return "webp";
        }
        return null;
    }

    /**
     * 校验图片像素尺寸：只做文件头解析，不解码像素，避免校验本身消耗大内存。
     *
     * 为什么不用 ImageIO：JDK 的 JPEG 解码器比浏览器严格得多，一些浏览器能正常显示、
     * 结构却不够规范的 JPEG（例如扫描段顺序异常的图）会让 ImageIO 直接抛
     * "Invalid JPEG file structure"，若据此判为损坏就会误杀用户正常图片。
     * 这里改为按各格式的文件头规范自己解析，规则确定、只读固定字节、不依赖解码器。
     *
     * 文件头解析不出尺寸时按损坏处理：magic 已通过（内容确实是该格式），
     * 却连头部都读不出来，说明文件被截断或伪造，宁可拒绝也不要把畸形图存进库。
     */
    private void validateDimensions(byte[] bytes, String ext){
        int[] size = readSizeByHeader(bytes, ext);
        if(size == null){
            throw new IllegalArgumentException("图片数据损坏，请重新选择！");
        }
        int width = size[0];
        int height = size[1];
        if(width <= 0 || height <= 0
                || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                || (long) width * height > MAX_IMAGE_PIXELS){
            throw new IllegalArgumentException("图片尺寸过大，请上传边长不超过 4096 像素的图片！");
        }
    }

    /** 按扩展名分派到各格式的头部解析；解析不出来返回 null */
    private int[] readSizeByHeader(byte[] bytes, String ext){
        if(bytes == null || ext == null){
            return null;
        }
        switch(ext){
            case "png":
                return readPngSize(bytes);
            case "gif":
                return readGifSize(bytes);
            case "webp":
                return readWebpSize(bytes);
            default:
                return readJpegSize(bytes);
        }
    }

    /** PNG：8 字节签名 + 长度(4) + "IHDR" + 宽(4，大端) + 高(4，大端) */
    private int[] readPngSize(byte[] b){
        if(b.length < 24){
            return null;
        }
        if(b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R'){
            return null;
        }
        return new int[]{beInt(b, 16), beInt(b, 20)};
    }

    /** GIF：6 字节签名 + 逻辑屏宽(2，小端) + 高(2，小端) */
    private int[] readGifSize(byte[] b){
        if(b.length < 10){
            return null;
        }
        return new int[]{leShort(b, 6), leShort(b, 8)};
    }

    /**
     * JPEG：从 SOI 之后逐个标记段前进，遇到 SOFn（C0-CF，排除 C4 DHT / C8 JPG / CC DAC）
     * 时，段内布局为 长度(2) 精度(1) 高(2) 宽(2)，取高宽即可。
     */
    private int[] readJpegSize(byte[] b){
        int len = b.length;
        int pos = 2;                       //跳过 SOI(FF D8)
        while(pos + 3 < len){
            if((b[pos] & 0xFF) != 0xFF){
                //段长与实际内容差一两个字节的图片在真实图库里很常见，浏览器会重新同步后照常显示。
                //这里同样向前找下一个标记，避免把这类图片误判为损坏。
                //多数字节错位是前一段把长度多算了 1 个字节，所以允许回退 1 字节重新同步。
                //每次重新同步都会把 pos 推进到下一个标记位置，整体扫描量仍是 O(n)。
                pos = nextMarker(b, pos > 2 ? pos - 1 : pos);
                if(pos < 0){
                    return null;
                }
                continue;
            }
            int marker = b[pos + 1] & 0xFF;
            if(marker == 0xFF || marker == 0x00){   //填充字节 / 熵编码里的转义字节
                pos++;
                continue;
            }
            if(marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)){
                pos += 2;                  //无长度字段的独立标记
                continue;
            }
            if(marker == 0xD9 || marker == 0xDA){
                return null;               //到 EOI / SOS 仍未见 SOF
            }
            int segLen = beShort(b, pos + 2);
            if(segLen < 2){
                return null;
            }
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if(isSof){
                if(pos + 9 > len){
                    return null;
                }
                return new int[]{beShort(b, pos + 7), beShort(b, pos + 5)};
            }
            //按段长跳过 APPn/EXIF 缩略图、DQT、DHT 等，避免把缩略图里的 SOF 当成主图尺寸
            pos += 2 + segLen;
        }
        return null;
    }

    /** 从 from 开始找下一个形如 FF xx 的标记，找不到返回 -1 */
    private static int nextMarker(byte[] b, int from){
        for(int i = from; i + 1 < b.length; i++){
            if((b[i] & 0xFF) != 0xFF){
                continue;
            }
            int m = b[i + 1] & 0xFF;
            if(m != 0x00 && m != 0xFF){
                return i;
            }
        }
        return -1;
    }

    private static int beShort(byte[] b, int i){
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int leShort(byte[] b, int i){
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int beInt(byte[] b, int i){
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    /**
     * WebP 宽高：JDK 8 没有内置解码器，这里按 RIFF 容器解析。
     * 支持 VP8X（扩展格式）、VP8L（无损）、VP8（有损）三种。
     */
    private int[] readWebpSize(byte[] b){
        if(b == null || b.length < 30){
            return null;
        }
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        if("VP8X".equals(chunk)){
            int width = 1 + ((b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16));
            int height = 1 + ((b[27] & 0xFF) | ((b[28] & 0xFF) << 8) | ((b[29] & 0xFF) << 16));
            return new int[]{width, height};
        }
        if("VP8L".equals(chunk)){
            int b0 = b[21] & 0xFF, b1 = b[22] & 0xFF, b2 = b[23] & 0xFF, b3 = b[24] & 0xFF;
            int width = 1 + (((b1 & 0x3F) << 8) | b0);
            int height = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
            return new int[]{width, height};
        }
        if("VP8 ".equals(chunk)){
            //chunk data 偏移 20，帧头 3 字节 + 起始码 3 字节后是 14bit 宽高
            int p = 26;
            if(b.length < p + 4){
                return null;
            }
            int width = ((b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF)) & 0x3FFF;
            int height = ((b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF)) & 0x3FFF;
            return new int[]{width, height};
        }
        return null;
    }

    /** 内置头像目录：仅在配置了 fivechess.avatar.builtin-dir 时存在，供本地开发排查 */
    public Path getBuiltinDir(){
        return builtinDir;
    }

    /** 内置头像数量，供健康检查与测试使用 */
    public int builtinCount(){
        return builtinFiles.size();
    }

    /** 判断地址是否为内置头像（/static/image/touxiang/<清单中的文件名>），供测试与排查使用 */
    public boolean isBuiltinAvatar(String avatar){
        if(avatar == null){
            return false;
        }
        String text = avatar;
        if(!staticBase.isEmpty() && text.startsWith(staticBase)){
            text = text.substring(staticBase.length());
        }
        if(!text.startsWith(BUILTIN_PREFIX)){
            return false;
        }
        String name = text.substring(BUILTIN_PREFIX.length());
        int query = name.indexOf('?');
        if(query >= 0){
            name = name.substring(0, query);
        }
        return builtinFiles.contains(name);
    }
}
