# FiveChess

联机五子棋：多人实时对战，支持大厅匹配、房间聊天、悔棋与求和、历史对局与复盘。

前端是 `web/` 下的静态页面（`login.html` 登录注册页、`index.html` 大厅与对局页），后端是 Spring Boot 服务（`server/`，默认监听 `8081`）。

## 功能

### 账号

- 注册：账号 3–12 位，昵称 1–10 位且不可重复（服务端校验 + 数据库唯一索引 `uk_user_name` / `uk_user_login` 兜底），密码 6–50 位。账号或昵称命中违规词库时按字段分别提示「账号包含违规内容，请修改后重试！」/「昵称包含违规内容，请修改后重试！」，不显示命中的具体词；账号或昵称被占用、输入不合规时，在对应输入框下方就地提示并让边框红色抖动，页面内不使用弹窗。
- 保留名：`管理员`（管理员展示昵称）与 `admin`（含 `FIVECHESS_ADMIN_LOGIN` 配置的值）不区分大小写，普通用户注册时按字段直接拒绝（「该昵称为管理员昵称，禁止使用！」/「该账号为管理员账号，禁止使用！」），避免管理员账号尚未创建时被抢占。比较前会先去掉空白与不可见字符，因此 `管理 员`、`admin`（零宽空格）这类视觉上等价的写法同样被拒。
- 字符限制：账号与昵称不允许包含控制字符（换行、制表、`NUL` 等）与零宽 / 双向控制字符（`U+200B–200F`、`U+FEFF`、`U+202A–202E`、`U+2066–2069`）。注册时直接返回错误；登录时统一按「账号或密码错误！」处理，不暴露账号是否存在。目的是防日志伪造与同名冒充——`admin` 加一个零宽字符后，在界面上看起来与 `admin` 完全一致。
- 登录：账号 + 密码，登录状态在同一浏览器内保持，未登录访问对局页自动跳回登录页。账号不存在与密码错误统一提示「账号或密码错误！」，不暴露账号是否存在。登录成功后直接进入大厅，并只显示一次「欢迎登录」轻提示（刷新不会重复）。
- 令牌：登录成功后服务端下发 `token`，对局数据接口与 WebSocket 连接都要校验；对局数据接口还会校验请求者是否为本局参与者。令牌按闲置时长过期：从最后一次使用算起，超过 `FIVECHESS_TOKEN_TTL_HOURS`（默认 12 小时）未使用即失效，需重新登录；请求接口、建立连接、页面心跳都算一次使用，页面开着不会被判过期。失效时前端弹窗说明原因并回登录页。
- 密码存储：入库前经 PBKDF2 加盐哈希（格式 `pbkdf2$迭代次数$盐$哈希`）；历史无盐 MD5 口令在登录时自动升级为新格式。
- 密码可见性：登录 / 注册页密码框右侧的「小眼睛」可切换明文与密文。
- 账号记忆与记住密码：登录页默认记住最近使用的账号并自动填入；账号框右侧的箭头可展开「已登录过的账号」列表（显示昵称与最近使用时间，当前账号打勾），点某一项切换账号，点右侧 × 移除该账号，`Esc` 收起列表。勾选「记住密码」的账号下次打开登录页会自动填好密码，直接点「登录」即可；未勾选的账号只自动填账号、密码留空，切换到它时也会清空已填的密码。列表最多保留 8 个账号（超出时丢弃最旧的），数据存放在浏览器 `localStorage` 的 `fcLoginUsers` 键下；密码以 `v1:` 前缀保存，算法是「`encodeURIComponent` → 与固定串逐字符异或 → Base64」，**可逆、不是加密**，只是避免明文裸露，公用电脑请勿勾选。
- 头像：管理员未上传时固定使用前端静态的管理员默认头像 `static/image/avatar.webp`（站点部署在 `/box/Chess/` 时完整地址是 `/box/Chess/static/image/avatar.webp`）；普通用户没上传过头像时，每次登录从内置头像目录 `static/image/touxiang/` 随机分配一张（只在本次会话内固定，别人看到的也是同一张）；在主页面点自己的头像即可更换，上传的图片由浏览器压成 256×256 方图后**存入数据库 `user_avatar` 表**，再由 `GET /avatar/{用户id}?v=版本号` 对外输出，之后每次登录都是这张。服务端会校验类型（JPG / PNG / GIF / WEBP，按文件头判断）、大小（3MB 以内）与像素尺寸（单边 ≤ 4096、总像素 ≤ 1677 万，防止小体积图解码后撑爆客户端内存），并要求携带有效令牌。内置头像目录的增删规则见下文「内置头像目录」章节。
- 头像地址与加载失败：服务端下发的是根绝对路径（`/static/image/touxiang/xxx.webp`），而本项目部署在站点子路径 **`/box/Chess/`** 下，浏览器会把 `/static/...` 解析到域名根而 404。因此前端把这类地址统一改写成 `/box/Chess/static/image/...`（配置项 `avatarStaticBase`，在 `web/static/js/index.js` 的 `data` 里，站点路径变了只改这一处），后端也用 `fivechess.avatar.static-base=/box/Chess` 直接下发带前缀的地址。改写只针对 `/static/` 前缀；`/avatar/{id}` 属于后端接口，保持原样交给反代/跨域配置。若地址仍取不到，会依次退回「按页面实际路径推导」「域名根路径」（本地根路径开发时靠这一步自愈），全部失败才显示中性占位图 `static/image/default-avatar.svg`，**不会退回管理员那张 `avatar.webp`**（否则普通用户头像一旦加载失败就会显示成管理员，卡片上也会出现破图）。
- 退出登录：确认弹窗二次确认后清除登录状态并回到登录页。同一账号在别处登录时，先前的连接会被提示并下线（单点在线）。

### 在线大厅

- 在线房间列表：实时展示房主昵称、加入者昵称、双方各自的头像与房间状态（等待加入 / 对局中）。对局中的房间在卡片下方居中显示观战人数，其上方提供「观战」按钮；等待加入的房间显示「公开房间」（globe 图标）或「私人房间」（lock 图标）。
- 房间号：创建房间时由服务端分配 6 位房间号（从 `000001` 起顺延）；房间空了或被解散后号码释放，下一个新建房间取当前最小空闲号复用。发号在服务端同步块内完成，并发创建不会重号。大厅卡片首行与房间内标题栏都显示「房间号：xxxxxx」。
- 按房间号搜索：大厅标题下方提供搜索框，按房间号模糊匹配（`1`、`0001`、`000001` 都能命中 `000001`），无匹配时提示「没有找到该房间号」；搜索生效时只保留匹配到的卡片并隐藏「创建房间」入口。
- 在线用户列表：展示昵称、在线人数与每人当前状态，进人 / 离开 / 状态变化时自动刷新。当前用户在线时置顶并带紫色「(我)」标记；顶部搜索框支持按昵称 / 账号 / ID 模糊搜索（大小写不敏感），无匹配时显示「没有匹配的用户」。状态分四种：空闲、房间中（已坐满但未全部准备）、游戏中、观战中。列表区最多完整显示 5 人，超出时可在区域内滚动查看。
- 刷新无闪动：服务端对断线用户设有约 1.5 秒的下线宽限期，期间重连即取消下线；前端列表按用户 ID 复用行节点，只改动变化行。
- 创建房间：点击「创建房间」卡片上的加号头像即可，无需填写名称或编号，创建后进入等待状态。
- 房间可见性：房主可把房间设为公开（任何人可加入）或私人（需要密码）。公开切私人时须设置 4–20 位密码才会生效；密码只以 PBKDF2 加盐哈希保存在服务端内存，不写入房间列表、日志，也不下发给客户端；切回公开会清空旧密码。私人房间仍出现在所有人的大厅列表中，仅以状态标识区分。
- 加入房间：点击空位加入；公开房间直接进入，私人房间需输入正确密码。房间已满、已不存在、加入自己的房间、受邀免密、已被房主踢出等情形都有对应提示。被该房主踢出过的用户再次点击该房间时不再弹密码框，直接提示「你已被房主移出该房间，无法再次加入！」。
- 邀请与观战入口：在线用户列表每项在状态右侧提供行内按钮——对方为空闲 / 房间中 / 观战时显示「邀请」，对方为游戏中时显示「观战」。
- 离开房间：房主离开后房客自动成为新房主；房客离开后房间回到等待状态。

### 对局

- 开局准备：双方都点击准备后由服务端随机分配黑白，黑棋先手。已准备时按钮变为「取消准备」，点击可撤销；对局真正开始后不再允许取消准备。棋盘下方昵称旁显示准备状态标签（绿色「已准备」/ 浅色「未准备」），刷新后按服务端状态恢复。
- 落子：18×18 棋盘，轮到自己时才响应点击；同一位置不能重复落子；对局结束后不可继续落子。
- 悔棋：向对手发起请求，对方同意后按上一步归属回退一步或两步；拒绝则不回退。
- 求和：向对手发起和棋请求，对方同意后判定和棋并自动重新开局（重新分配黑白、清空步数），不足 3 回合的对局不保存。
- 认输：认输后本局立即结束，对手获胜。
- 重新开局：双方同意后清空棋盘重新开局并重新随机黑白；两边各自拿到服务端分配的真实颜色，不会出现颜色对调。
- 胜负与先后手：全部由服务端判定并广播；单房间步数上限 324 步（满盘判和）。
- 落子提示：对手最后一步高亮显示；终局时服务端把最后一颗落子连同连五坐标一并下发，获胜方、失败方与观战者拿到同一份完整棋盘，五连棋子以红色连线与圆点标出。
- 房间内实时比分：对局区上方显示当前房间的比分条，取服务端下发的 `scoreId1` / `scoreId2`。昵称超过 6 个字按码点截断为「前 6 字…」，悬停可看完整昵称；两侧昵称区等宽，比分数字始终居中；领先一方数字用暖红色并放大显示。等待对手时不显示比分条。
- 观战：对正在对局的用户发起观战，以只读方式进入房间，不影响胜负判定，可随时「退出观战」。观战过程中能看到房内悔棋 / 认输 / 求和 / 重开的发起与结果，以及最终哪一方获胜；分出胜负或房内一方退出时弹出轻提示（3 秒后自动关闭）并自动退出观战。

### 历史对局与复盘

- 历史对局列表：分页展示每局的开始时间、结束时间、对手昵称、步数与结局（获胜 / 败北 / 平局 / 认输 / 对手认输），每页 10 条，底部固定分页条「上一页　当前页 / 总页数　下一页　共 N 条」，首页 / 末页时对应按钮置灰。
- 复盘回放：支持开局、上一步、下一步、终局与自动播放；进入复盘时以轻提示说明本局我方执何色（3 秒后自动关闭）；「退出复盘」按钮会停止自动播放、清空复盘棋盘并回到历史列表。
- 终局高亮：回放到最后一手时，按与房间内终局**完全一致**的样式，把获胜的五子（长连则取整条）用红线连起来并点上红点；判定规则与后端 `fiveInRowLine` 相同（横 / 竖 / 撇 / 捺，取第一条 ≥5 的连线），认输、和棋等未连五的终局不画线。中途回放不显示，回退一步立即消失（整盘重绘，不会留下红线残段）。
- 不足 3 回合（6 步）的对局不保存。
- 结算原子性：一局结束时棋盘数据（`game`）与战绩（`record`）在**同一事务**里写入，任一失败整体回滚并保留本局可重试，不会留下「有棋盘数据但没有战绩」的孤儿行。

### 聊天

- 公共聊天大厅：消息在大厅内全服广播，显示昵称与时间，自己与他人的消息区分显示。窗口顶部始终保留系统提示「网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。」，连接建立、刷新回填历史、消息超过 300 条裁剪时都会补回。
- 房间聊天：仅房间内双方可见，包含聊天消息与准备、悔棋、求和、重开等系统提示。
- 敏感词过滤：违规内容被等长掩码替换，大小写不同也能识别。
- 聊天记录留存：房间聊天与公共聊天分别写入 `chat_room_record` / `chat_public_record`，每条记录保存用户 id、昵称、时间、房间号、原始内容与发言时的实时 IP。入库保存的是原始内容（不受过滤影响，便于追溯），聊天窗口展示的仍是过滤后的内容。保留期由 `FIVECHESS_CHAT_RETENTION_DAYS` 配置（默认 30 天），过期记录由后台任务定期清理。
- 消息长度上限 2000 字符，超出不发送。
- 禁言提示：被禁言后，公共聊天与房间聊天的输入框位置改为显示「你已被管理员禁言，暂时无法发言」，发送按钮保持禁用；文字放不下时以两段首尾相接的方式向左无缝滚动。
- 房间消息列表只保留最近 300 条；准备 / 取消准备的系统提示同步给双方，重复的准备通知不会重复记消息，也不会重复请求开局。
- 安全渲染：昵称等用户内容在提示框与邀请弹窗中先转义再展示，不会被当作 HTML 解析（防 XSS）。

### 邀请、观战与房间管理

- 邀请他人：在房间中时，对状态为空闲 / 房间中 / 观战的用户点击「邀请」即可。按钮随状态联动：已发出显示「等待接受中...」并禁用；被拒绝后进入 10 秒倒计时，归零恢复；被「拒绝并不再接受」后当天置灰（仍可点击，但提示当天不可再邀请）；对方接受并进入房间后该用户不再显示「邀请」。
- 受邀处理：被邀请者收到弹窗，显示邀请人昵称与剩余秒数，可「接受」（直接进入邀请者房间）或「拒绝」；30 秒未处理则弹窗自动关闭并自动拒绝。弹窗内还有「拒绝并不再接受该用户邀请」，选择后该邀请者当天无法再邀请该用户。
- 观战入口：对「游戏中」或「对局结束」的用户点击「观战」即可进入只读视图；大厅房间卡片上的「观战」按钮同样可一键进入。进入已结束的对局时会询问「对局已结束，是否继续观看？」，继续则留在只读终局棋盘，离开则回大厅。视图中只提供「退出观战」，棋盘下方显示双方昵称与准备状态标签并实时刷新。
- 房主标识：房间内本人是房主时，昵称右侧显示「(房主)」。
- 房内玩家信息：棋盘下方居中显示房主昵称（带「(房主)」）与另一位成员昵称，无对手时显示「等待加入···」。
- 公开 / 私人切换：房主在页面标题左侧看到一个开关（左「私人」蓝、右「公开」绿），点击即可切换；公开切私人时弹密码设置框（4–20 位），切回公开无需密码并清空旧密码。房内其他成员与大厅其他用户会同步收到变更。
- 踢出玩家：房内有对手时，对局按钮行「离开房间」左侧出现「踢出玩家」。被踢用户立即回大厅并收到提示，且不可再加入该房间。管理员账号不能被房主踢出：按钮始终显示，点击时前端提示「不能踢出管理员」，服务端同样二次拒绝。
- 失联房间回收：移动端切后台或网络半开时 TCP 连接仍处于打开状态，服务端收不到关闭事件，房间会一直留在大厅、别人进去后永远等不到房主准备。服务端每 60 秒巡检一次，超过心跳超时（30 秒）再等 5 分钟宽限仍无任何活动的玩家会被自动退房，房间随之释放，并推送 `room-closed` 让页面回到大厅。页面每 10 秒发一次心跳，正常使用不会触发。
- 离开房间的大厅同步：服务端的权威房间列表要一个网络往返才下发，因此退出（或被踢出）时前端会**先在本地把这次离开投影到列表上**——房客退出把座位清空、房主退出且还有房客则房客升为房主、房主单独退出直接把该房间摘掉。否则大厅会先闪一下「自己还在房间里」的旧卡片（单人房间则是整张卡片闪一下才消失）。权威列表到达后覆盖本地投影。
- 房间解散的大厅同步（同一思路）：收到 `room-closed`（房主另建房间、管理员解散、失联回收等，房间在服务端已移除）时，前端先把该房间卡片从本地列表摘掉再切回大厅；管理员在巡查页解散房间同样先摘卡片再退出查看。否则会先闪一下刚解散的房间旧卡片。

### 滥用防护

- 普通 HTTP 接口：按来源 IP 做总速率限制（默认 600 次 / 分钟），超限返回 `429` 并提示「请求过于频繁，请稍后再试！」。
- 会话探测接口 `/sessionCheck`：因需供未登录页面轮询，不占用 HTTP 总限额，改为**独立**限流（默认 120 次 / 分钟 / IP，`FIVECHESS_SESSION_CHECK_PER_IP_LIMIT` 可调）。该接口的令牌反查走服务端反向索引，O(1) 而非遍历全部账号，因此无法用小请求放大成 CPU 消耗。
- 请求体大小：所有 HTTP 接口的请求体上限 8MB，超出直接返回 `413` 与 JSON 提示，不进入业务逻辑、不解析 JSON（头像上传的 5MB 图片经 Base64 膨胀后在界内）。限制在过滤器里按 `Content-Length` 预判，早于 Spring 解析请求体。
- 登录：按 IP 与账号双维度限速（默认各 10 次 / 分钟）；同一账号连续失败 5 次后临时锁定 5 分钟，锁定期内密码正确也拒绝并提示剩余秒数，登录成功即清除失败计数。
- 注册：按 IP 限制单位时间内的注册次数（默认 10 次 / 分钟），防批量注册。
- WebSocket：单 IP 并发连接数上限（默认 20），超出时拒绝并断开。
- 聊天：同一用户单位时间内聊天条数上限（默认 20 条 / 10 秒），超限消息被丢弃并提示「发言过于频繁，请稍后再试！」。
- 阈值均可配置，也可用 `FIVECHESS_SECURITY_ENABLED=false` 一键关闭（压测与自动化测试使用）。
- 反向代理 / CDN 之后必须让服务端知道如何还原真实来源 IP，否则限流、封禁、注册 IP 记录全部落在代理地址上：
  - **Cloudflare（含 Render 这类跑在 Cloudflare 上的平台）**：设 `FIVECHESS_TRUST_CLOUDFLARE_HEADER=true`，直接读取边缘覆盖写入的 `CF-Connecting-IP`。该头由边缘强制覆盖，客户端伪造无效，也不受边缘层数影响——这是这类部署唯一可靠的取值方式（否则拿到的是 Cloudflare 节点地址，形如 `104.x` / `172.6x`，会随节点变化）。
  - **普通 Nginx 等代理**：设 `FIVECHESS_TRUST_FORWARDED_FOR=true`，并把 `FIVECHESS_FORWARDED_FOR_HOPS` 设为**实际代理层数**（单层 Nginx 填 `1`，CDN + Nginx 填 `2`）：服务端从 `X-Forwarded-For` **右起**取第 N 跳，取的是代理亲手追加的地址；填大了会把客户端自己写的那一段当成真实地址，等于重新开放伪造。
  - **直接对外**（`java -jar` 监听公网端口，前面没有任何网关）：**什么都不用配**，两个开关的默认值 `auto` 在非边缘平台会自动关闭，任何请求头都不采信。不要手动写 `false` —— 与不写等价，只会留下一个换环境后忘删的坑（详见「识别真实来源 IP」一节）。
  - 两个开关都支持 `auto`（默认）：检测到 Render 平台注入的 `RENDER` 环境变量时自动开启，其余环境自动关闭，因此 Render 上无需额外配置。
- 计数与锁定状态保存在单实例内存，重启即清零。

### 管理员与账号处置

管理员账号名由 `FIVECHESS_ADMIN_LOGIN` 配置（默认 `admin`）。该账号登录后前端才出现管理入口，服务端对每一次管理操作二次校验身份。

- 处置入口：管理员在房间内昵称旁与在线用户列表每个昵称右侧看到「禁言 / 解除禁言」与「封号」按钮（管理员本人与空缺位置不显示）；两处按钮共用同一套服务端校验与状态，禁言状态以在线列表数据回显。
- 禁言：点击后弹出二次确认（显示目标昵称，可取消），确认后被禁言用户立即收到提示，公共聊天与房间聊天都会被拒绝；在线列表与房内按钮切换为「解除禁言」，状态落库，重启后依然生效。
- 封号：被封用户立即收到通知并被断开连接（前端清除登录态并回登录页），其账号与**两个 IP** 同时被封禁——账号无法再登录，这两个 IP 也都无法再注册新账号，注册或登录统一提示「您已被管理员封号，如有任何疑问请移步 https://blog.donghao.ltd/ 联系管理员」。两个 IP 分别是：
  - `user.register_ip`：注册时的出口，注册时落库、此后不变；
  - `user.last_ip`：最近一次**登录成功**时的出口，每次登录覆盖（登录失败或已被封号的尝试不会改动它）。

  记录两个出口是因为用户的注册地与常用登录地往往不同，任一处命中即拒绝注册与登录。两者相同（含同一条 IPv6 `/64`）时只保留一份，封号名单里不会重复显示。两个字段都为空时退回当前在线 IP；用户不在线且无记录时，管理员回执明确提示「未取到该用户的 IP（仅封账号，IP 未限制）」，不会静默假装已限制 IP。

  **同一个账号两次看到的 IP 不一样，通常是网络侧导致的，不是解析出错。** 服务端取的是 TCP 对端的地址，直连部署时它就是用户的真实公网 IP，但这个地址本身可能变化：

  - **部署在代理 / CDN / 隧道 / 云负载均衡之后**：取到的会是最后一跳代理的地址——所有用户共享它（封一个号牵连全站），多节点代理下它还会随节点变化。若代理是 Cloudflare（Render 的入站网络就是 Cloudflare），把它认成用户 IP 的典型特征是 `104.16.0.0/12`、`172.64.0.0/13` 段（如 `104.23.160.x`、`172.71.150.x`）：此时开启 `FIVECHESS_TRUST_CLOUDFLARE_HEADER=true` 即可。若是普通 Nginx，则把 `FIVECHESS_TRUST_FORWARDED_FOR` 置为 `true` 且 `FIVECHESS_FORWARDED_FOR_HOPS` 与实际层数一致。服务端在开关为 `false` 时首次收到带转发头的请求，会在日志打印一条 `[security]` 提示，并回显 `remoteAddr` / `X-Forwarded-For` / `CF-Connecting-IP` 的实际值，据此一条日志即可判断该用哪个开关。
  - **IPv6 用户**：同一线路内会不断轮换隐私地址，所以字符串天天不同。封禁按 `/64` 前缀生效，封号回执与封号名单里会同时显示原值与「比对」范围，两条看着不同实际是同一段。
  - **移动网络（4G/5G）**：运营商在 CGNAT 地址池内分配出口，换基站或重连就可能换 IP；这是网络侧行为，服务端无法还原成唯一的「本人地址」。这类账号的 IP 封禁只能覆盖已观测到的出口，账号封禁才是可靠的处置手段。
- 封号名单与解封：管理员在大厅左侧栏「背景」按钮下方看到「封号名单」面板（仅大厅显示、默认收起），列出已封号账号的昵称、已封 IP（分别标注「注册」与「登录」）与封号时间，每项可就地解封；最多显示最近 10 个，超出时提示总数。解封时两个 IP 字段一起清空，账号与这两个 IP 恢复正常登录 / 注册。没有绑定到 IP 的会显示「未记录（仅封账号）」，鼠标悬停可看到原因。
- 管理员账号不可删除（可选加固）：在 MySQL / MariaDB 上执行「数据库」章节给出的可选触发器 SQL 后，触发器 `trg_user_protect_admin` 会拒绝删除 `user` 表中 `login = 'admin'` 的行（`DELETE` 单条或整表都报 `ERROR 1644`）。该触发器只对 `DELETE` 生效，`TRUNCATE TABLE` 与 `DROP TABLE` 不受约束，需靠数据库账号权限控制。若修改了 `FIVECHESS_ADMIN_LOGIN`，需同步修改触发器里的判定值。**TiDB（含 TiDB Cloud）不支持触发器**，跳过即可，删除保护改由数据库账号权限约束（业务账号不授予 `DROP` 权限）。
- 公共聊天通报：禁言 / 解除禁言 / 封号 / 解封时，服务端在公共聊天大厅以红色系统通报公示用户与处置结果；通报只作实时公示，不写入聊天记录表。
- 操作记录：每次禁言 / 解除禁言 / 封号 / 解封 / 解散房间都写入 `moderation_log`（操作人、目标账号、动作、说明与时间）。
- 隐身巡查：管理员对大厅任意房间（含私人房间）点击「巡查房间」即可进入房间内部视图，看到该房间的棋盘、人员与完整房间聊天（含进入前的历史记录与系统事件），房内落子实时同步；房内有加入 / 离开 / 准备 / 开局 / 换房主时视图实时刷新。房内用户无感知：不计入观战人数、不产生进入提示、不出现在成员信息中。查看期间管理员可在该房间发言，昵称取服务端权威昵称。
- 解散房间：管理员在巡查视图或大厅卡片上点击「解散房间」并二次确认，房内所有用户收到提示并回到大厅；管理员自身被解散 / 退回大厅时只用轻提示。
- 封号后的房间联动：封号会立即把该用户移出房间，并按剩余人员收敛房间——封号房客时对局终止、房主留在房间等待；封号房主且房内还有房客时，房客继承房主；房间只剩一人且被封号时房间直接解散，正在巡查的管理员自动退出查看。

### 界面与响应式

- 三栏布局：左侧用户区、中间大厅或棋盘、右侧在线列表与聊天。桌面端三栏等高对齐、栏间 16px 间距，宽屏最大 1340px 居中；左右两栏分别收窄至 186px / 268px，把宽度让给中部的历史对局列表。
- 左侧用户区：头像在上、昵称在下，按钮等宽（72px）居中对齐头像。
- 背景：内置 4 套背景可切换（淡紫远山 `bg-2.webp`、墨色黄花 `bg-1.webp`、暮色夕阳 `bg-3.webp`、仲夏梦莲 `bg-4.webp`，默认「淡紫远山」；5 张背景图均为 WebP，合计约 0.67 MB）。「背景」为下拉按钮，默认收起、点击展开，选中即生效；选择只保存在内存里，刷新后回到默认背景。背景由固定定位的独立背景层绘制（`position:fixed` + `background-size:cover` + 居中），任何分辨率与滚动位置都完整铺满。
- 大厅：房间卡片网格自适应列数；「大厅」标题为居中蓝色渐变胶囊（圆角、白字、带投影）。
- 房间聊天窗口固定高度（桌面 380px，窄屏 260–300px），底部输入行与消息区左右对齐。
- 公共聊天窗口在大厅常驻，进入房间后以悬浮窗形式显示与隐藏；桌面端滑动过渡 0.3 秒，窄屏（≤1100px）改回文档流并与房间聊天窗纵向排列；显隐状态存在 `sessionStorage` 的 `fcPublicChat`，同一标签页刷新后保持，关闭标签页后回到默认。
- 响应式断点统一以 1100px 为界：
  - `≤1100px`：三栏纵向堆叠，左栏收敛为一行工具条，棋盘按列宽等比缩放（上限 540px），右栏限宽 560px 居中；
  - `≤600px`：缩小头像（56px）与按钮宽度，压缩聊天窗口高度，登录卡片左右各留 12px；
  - 横向溢出只在内容层用 `overflow-x: clip` 兜底，不在 `html/body` 上使用 `overflow-x: hidden`（会把 `overflow-y` 计算成 `auto`，手机端滚不到页面底部）；
  - 输入框在窄屏统一 `font-size:16px`（避免 iOS / 微信内置浏览器自动放大），并加 `viewport-fit=cover` 与 `env(safe-area-inset-bottom)` 适配刘海屏。
- 页面标题：进入房间后标题右侧显示观战人数，房主在标题左侧看到公开 / 私人开关；桌面端标题整体左移 41px，使其居中在中间列正上方。
- 操作结果、等待状态等以轻提示（toast）反馈。
- 刷新保留页面：刷新或断线重连后恢复刷新前的页面与状态（房间等待、对局棋盘、观战、历史对局、复盘），恢复期间不会先闪大厅；页面用 `v-cloak` 在 Vue 挂载前整体隐藏。视图状态记在 `sessionStorage` 的 `fcViewState`，只对同一标签页有效（关掉标签页再打开即回大厅），退出登录、令牌失效、被封号等场景会主动清空；恢复失败时有超时兜底，不会停在空白页。
- 登录 / 注册页：背景同样是固定定位的独立背景层，卡片用 flex 自动外边距在视口中居中，宽度约 400px（`≤600px` 时按 `calc(100% - 24px)` 收缩），矮窗口下自动可滚动。
- 统一主题弹窗：退出登录、邀请、求和 / 重开 / 认输 / 离开房间的二次确认，以及账号在别处登录、服务重启、会话失效、封号等系统提示都使用同一套弹窗。连接意外断开（网络抖动、切标签、跳转页面再回来）不直接弹「登录失效」，而是顶部显示「正在重连…」并静默重连，只有服务端明确回答令牌无效时才提示并回登录页。

## 通讯协议

### HTTP 接口

除 `GET /health` 与 `GET /avatar/{userId}` 外，接口均为 `POST`，请求体与响应体为 JSON（响应结构 `{ code, success, message, content }`；`/health` 返回纯文本，`/avatar/{userId}` 返回图片二进制）。请求体超过 8MB 时直接返回业务码 `413`（`{"code":413,"success":false,"message":"请求体过大，请压缩图片后重试！"}`）。

| 接口 | 请求参数 | 说明 |
| --- | --- | --- |
| `POST /registerPost` | `id`、`name`、`password` | 注册；会记录来源 IP（`user.register_ip`，解析不到写 NULL）；来源 IP 命中封号名单直接返回 403；账号或昵称冲突返回 409，命中违规词或保留名返回 403，触发限流返回 429 |
| `POST /loginPost` | `login`、`password` | 登录；成功时 `content` 为用户信息与 `token`，失败统一 401。**登录成功会把本次来源 IP 写入 `user.last_ip`**（每次覆盖，用于封号时锁定「常用出口」）；登录失败或账号已被封号时不写 |
| `POST /sessionCheck` | `id`、`token` | 探测令牌是否有效，失败时返回失效原因（`restart` / `superseded` / `loggedout` / `missing` / `expired`） |
| `POST /logoutPost` | `id`、`token` | 退出登录并作废令牌 |
| `POST /getPage` | `id`、`token`、`pageNum` | 历史对局分页（每页 10 条） |
| `POST /getRecords` | `id`、`token` | 个人战绩统计（前端已改用分页接口 `/getPage`，此接口保留兼容） |
| `POST /getGameData` | `id`、`token`、对局标识 | 复盘数据；仅本局两位参与者可取 |
| `POST /avatarPost` | `id`、`token` | 查询某用户当前展示的头像地址（大厅卡片、房间与观战取图用；已上传返回 `/avatar/{id}?v=版本号`，否则为内置头像或默认头像） |
| `POST /avatarUploadPost` | `id`、`token`、`image` | 上传头像，`image` 为 `data:image/...;base64,...`；按文件头校验 JPG / PNG / GIF / WEBP、上限 3MB、像素尺寸单边 ≤ 4096，成功返回带 `?v=版本号` 的新地址 |
| `GET /avatar/{userId}` | 无 | 输出上传头像的图片二进制；带 `Content-Type`、`ETag`（版本号）、`Cache-Control: public, max-age=604800`、`X-Content-Type-Options: nosniff`，无记录返回 404。支持 `If-None-Match` 条件请求：版本号未变返回 **304**（且只查 `version` 单列，不读图片二进制） |
| `GET /health` | 无 | 健康检查，返回 `UP,服务正常运行中` |

### WebSocket

连接地址 `ws(s)://<host>:<port>/socket/{用户id}`，连上后先发 `auth` 鉴权，失败会收到 `auth-fail` 并断开。

客户端消息：

| 分类 | 类型 |
| --- | --- |
| 连接 | `auth`、`heartbeat`、`report-nick` |
| 房间 | `create-room`、`join-room`、`quit-room`、`spectate`、`toggle-room-open`、`kick-player` |
| 邀请 | `invite`、`invite-accept`、`invite-reject` |
| 对局 | `info-ready`、`start-game`、`play`、`regret`、`regret-agree`、`regret-reject`、`peace`、`peace-agree`、`peace-reject`、`restart`、`restart-agree`、`restart-reject`、`surrender` |
| 聊天 | `public-chat-message`、`chat-message` |
| 管理 | `admin-mute`、`admin-unmute`、`admin-ban`、`admin-unban`、`admin-banned-list`、`admin-enter-room`、`admin-leave-room`、`admin-dismiss-room` |

服务端消息：

| 分类 | 类型 |
| --- | --- |
| 鉴权 | `auth-success`、`auth-fail`、`self-state`、`server-restart`、`banned`、`kicked`、`muted`、`ws-limit` |
| 大厅 | `online-rooms`、`online-user-list`、`someone-join-room`、`become-owner`、`kick-success`、`kick-fail`、`kicked-from-room`、`room-closed` |
| 房间 | `create-room-success`、`join-room-success`、`join-room-fail`、`room-restore`、`room-open-changed`、`room-open-fail`、`spectate-success`、`spectate-fail`、`spectator-update`、`spectate-end`、`spectate-notice`、`spectate-reset`、`spectator-update` |
| 对局 | `start-game`、`start-game-fail`、`play`、`play-reject`、`game-over`、`round-reset`、`regret-agree`、`regret-applied`、`action-invalid` |
| 邀请 | `invite-sent`、`invite-received`、`invite-accepted`、`invite-rejected`、`invite-timeout`、`invite-fail`、`invite-blocked-list` |
| 聊天 | `public-chat-message`、`chat-message`、`chat-limit`、`public-notice`、`client-leave` |
| 管理 | `admin-room-view`、`admin-room-update`、`admin-room-event`、`admin-action-ok`、`admin-fail`、`admin-room-closed`、`admin-leave-ok`、`admin-banned-list` |

对局相关消息带 `boardVersion`（当前局序号）：开局类消息下发，客户端在悔棋 / 认输 / 求和 / 重开等请求中回传，服务端据此丢弃属于上一局的迟到请求；被拒绝的操作会收到 `action-invalid`，其中 `roundOver` 表示本局是否已经结束。

## 安全与上线注意

内置能力：PBKDF2 加盐哈希（兼容历史 MD5 并在登录时升级）；接口与 WebSocket 均校验令牌；对局数据仅本局参与者可见；聊天敏感词过滤；昵称等用户内容转义后渲染（防 XSS）；`report-nick` 上报昵称时校验归属，冒用他人或管理员昵称会被回退成本人真实昵称；管理员/保留名不可注册；注册并发冲突由账号与昵称唯一索引兜底；滥用防护（HTTP / 登录 / 注册限速、账号连续失败锁定、单 IP 连接数上限、聊天频率限制）；聊天记录留存（原文、分表、含发言 IP、保留期可配）；用户头像（管理员默认 avatar.webp，普通用户随机分配内置 webp 头像 / 上传后存数据库并由 `/avatar/{id}` 提供）；管理员能力（禁言 / 封号 / 解封 / 隐身巡查 / 解散房间 / 公共通报，每次操作二次校验并记录）；管理员账号删除由数据库触发器拦截（MySQL / MariaDB 可选）。

上线前请确认：

- `FIVECHESS_CORS_ORIGINS` 改为正式域名并去掉 `null`（默认值含 `null` 只为支持 `file://` 本地调试）；
- 数据库口令只通过 `DB_PASSWORD` 注入，不要写进配置文件；口令缺失时服务直接启动失败（避免带病上线）；
- 需要 HTTPS 时设 `SERVER_SSL_ENABLED=true` 并替换正式证书与口令；页面也必须用 https 打开，否则会因协议不匹配请求失败；
- 确认 `user` 表存在 `uk_user_name` 与 `uk_user_login` 两个唯一索引；
- 建库脚本已内置 `admin`（昵称「管理员」）一行，其口令由脚本里的 PBKDF2 哈希决定。**上线前必须改成自己的强口令**（改法见 `数据库/chess.sql` 末尾「管理员口令变更」，脚本内不含明文，无法从哈希反推）；该账号可禁言 / 封号 / 隐身查看任意房间 / 解散任意房间，请妥善保管；生产库账号不要授予 `DROP` 权限（触发器只拦 `DELETE`）；
- 清理开发与测试阶段残留的账号、对局与聊天记录，避免污染正式数据；
- 令牌闲置 12 小时（`FIVECHESS_TOKEN_TTL_HOURS` 可调，`0` 为不过期）后失效，保存在服务端内存，重启即全部失效且不跨实例共享；令牌前缀带本次启动标识，用于区分「服务重启」与「同账号在别处登录」。连接意外断开时前端静默重连，只有服务端明确回答令牌无效才弹窗说明原因并回登录页；
- 聊天记录入库的是用户原始内容，属敏感数据，请做好数据库访问控制与备份加密；系统不含人工审核与自动风控，如需合规处置请按需接入内容审核；
- 长期公网运行建议在反向代理 / 网关层补充总流量限流（抗 DDoS）与 WSS 终止。

## 运行与部署

### 环境要求

- JDK 8（`pom.xml` 中 `java.version` 为 1.8）
- MySQL 5.7 及以上
- Maven（构建）

### 数据库

- 建库建表：**只需执行 `数据库/chess.sql` 一个文件**。脚本自带 `CREATE DATABASE IF NOT EXISTS chess` + `USE chess`，随后按 `user`、`user_avatar`（用户上传的头像二进制）、`record`（对局战绩）、`game`（进行中棋局快照）、`chat_public_record`（公共聊天记录）、`chat_room_record`（房间聊天记录）、`moderation_log`（管理员操作记录）的顺序创建七张表并写入内置管理员。**不用事先建库，也不需要第二个脚本。**
- **首次建库（一条命令，末尾不写库名）**：

  ```bash
  mysql -u<账号> -p --default-character-set=utf8mb4 < 数据库/chess.sql
  ```

  - 想换库名：改 `chess.sql` 里 `CREATE DATABASE` 与 `USE` 两行的 `chess`（共 2 处）。
  - TiDB Cloud：控制台「导入」直接指向 `chess.sql`，目标库选 `chess` 即可。
- **补建 `user_avatar` 表（老库按需执行，可重复运行）**：

  ```sql
  CREATE TABLE IF NOT EXISTS `user_avatar` (
    `user_id` int NOT NULL COMMENT '用户id（user.id）：一个用户只保留最新一张',
    `content` mediumblob NOT NULL COMMENT '图片二进制（前端已压到 256x256，单张上限 3MB）',
    `content_type` varchar(32) NOT NULL DEFAULT 'image/jpeg' COMMENT 'image/jpeg、image/png、image/gif、image/webp',
    `version` bigint NOT NULL DEFAULT '0' COMMENT '版本号（上传时间毫秒），拼在访问地址后用于刷新浏览器缓存',
    `updated_at` varchar(30) DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
  ```
- 兼容性：`chess.sql` 可直接导入 MySQL 8 与 TiDB（含 TiDB Cloud），不含触发器、存储过程等 MySQL 专有语法。**仅 MySQL / MariaDB** 想要数据库层面拦住「删除 admin」时，额外执行下面这段（可重复执行；**TiDB 不支持触发器，跳过**，删除保护改由账号权限约束）：

  ```sql
  DROP TRIGGER IF EXISTS `trg_user_protect_admin`;
  DELIMITER $$
  CREATE TRIGGER `trg_user_protect_admin`
  BEFORE DELETE ON `user`
  FOR EACH ROW
  BEGIN
      IF OLD.`login` = 'admin' THEN
          SIGNAL SQLSTATE '45000'
              SET MYSQL_ERRNO = 1644,
                  MESSAGE_TEXT = '禁止删除管理员账号 admin（如确需删除，请先 DROP TRIGGER trg_user_protect_admin）';
      END IF;
  END$$
  DELIMITER ;
  ```

  `DELIMITER` 是 mysql 命令行客户端的指令，图形客户端请按各自的「执行整段脚本」方式运行。触发器只拦 `DELETE`；`TRUNCATE` / `DROP` 不受约束，权限仍需自行收紧。
- ⚠️ 脚本里是 `DROP TABLE IF EXISTS` 重建模式，**只用于首次建库**；已有数据的库执行会清空七表。另外删除 `user` 表的动作会连带删掉挂在上面的触发器，**所以每次重跑 `chess.sql` 之后都要重新执行一次上面那段触发器**（如果之前建过）。
- 导入后自查（MySQL）：

  ```sql
  SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE();  -- 应为 7 行
  SHOW CREATE TABLE user_avatar;                                                      -- 5 列，user_id 为主键
  SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = DATABASE();  -- 执行过上面的触发器才 1 行，TiDB 恒为 0
  SELECT id, login, name, avatar FROM user;                                           -- 内置 admin 一行，avatar 为 NULL
  ```
- `user` 表带昵称唯一索引 `uk_user_name`、账号唯一索引 `uk_user_login`，以及管理字段 `muted`（禁言）、`banned`（封号）、`ban_time`（封号时间）、`register_ip`（注册时的客户端 IP，注册写入后不变）、`last_ip`（最近一次登录成功的客户端 IP，每次登录覆盖）与头像字段 `avatar`（普通用户上传后的头像路径 `/avatar/{id}`，为空表示每次登录随机分配内置头像）。
- 封号拦截的 IP 判定读的就是 `register_ip` 与 `last_ip` 两列，**没有** `ban_ip` / `ban_ip_last` 快照列：那两列的值必然等于封号当时的这两个字段，多存一份只会带来「解封必须两边都清」的隐患。两个字段覆盖「在家注册、在公司登录」这类多出口用户，任一命中即拒绝注册。
- 表不使用 `AUTO_INCREMENT=n` 起始值：显式插入 `id=1` 的内置管理员后，第一个注册用户自动是 2。自增计数器只增不减，删行不会让它回退，若已有库出现跳号，可执行 `ALTER TABLE user AUTO_INCREMENT = 2;`（前提是表内没有更大的 id）。
- 查询相关索引：`user.idx_user_ban(banned)`（封号名单）、`record.idx_record_p1(player1, start, id)` 与 `record.idx_record_p2(player2, start, id)`（「我的对局」按时间倒序分页）、`record.idx_record_data(data)`（按棋谱标识取对局）、`chat_room_record.idx_room_room(room_id, id)`（房间历史取最近 N 条）。`record.win` 为 `int`，存胜方用户 id，`0` 表示和棋，空值表示对局未结算。
- `user_avatar` 表存头像图片本身（`user_id` 主键、`content` mediumblob、`content_type`、`version`、`updated_at`），一个用户只保留最新一张。表不存在时后端不会崩，只在日志提示一次并降级为「只用内置头像」，登录 / 对局 / 聊天不受影响。数据库短时故障导致的降级是**限时**的（60 秒后自动重试），不会因为一次抖动就永久停用头像库。
- **补全 `user` 表字段与索引（老库按需执行）**：

  ```sql
  ALTER TABLE `user` ADD UNIQUE KEY `uk_user_name` (`name`);
  ALTER TABLE `user` ADD UNIQUE KEY `uk_user_login` (`login`);
  ALTER TABLE `user`
    ADD COLUMN `muted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否被管理员禁言',
    ADD COLUMN `banned` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否被封号',
    ADD COLUMN `ban_time` varchar(30) DEFAULT NULL COMMENT '封号时间',
    ADD COLUMN `register_ip` varchar(64) DEFAULT NULL COMMENT '注册时的客户端IP',
    ADD COLUMN `last_ip` varchar(64) DEFAULT NULL COMMENT '最近一次登录成功的客户端IP',
    ADD COLUMN `avatar` varchar(255) DEFAULT NULL COMMENT '头像地址';
  ```

  老库如果还留着 `ban_ip` / `ban_ip_last` 两列，新版不再读写它们，可以直接删除：

  ```sql
  ALTER TABLE `user` DROP COLUMN `ban_ip`, DROP COLUMN `ban_ip_last`;
  ```

  已经建过表、只缺某些列时，按需执行对应的单条语句（示例：只缺 `avatar` 列）：

  ```sql
  ALTER TABLE `user` ADD COLUMN `avatar` varchar(255) DEFAULT NULL COMMENT '头像地址';
  ```

  缺少 `avatar` 列时登录会直接报错。

  项目只维护 `数据库/chess.sql` **一个**脚本（建库 + 7 张表 + 内置管理员），上述字段与索引全部写在建表语句里，不提供增量迁移脚本。**标准升级方式是删库重建**：

  ```bash
  # 1. 删掉旧库（确认没有需要保留的数据）
  mysql -u<账号> -p -e "DROP DATABASE IF EXISTS chess;"
  # 2. 用唯一脚本重建
  mysql -u<账号> -p --default-character-set=utf8mb4 < 数据库/chess.sql
  ```

  确实不能删库时，才用上一段的手工 `ALTER TABLE` 补列兜底。

  > ⚠️ **顺序不能反**：必须先重建库，再上新版 jar。新包注册时写 `register_ip`、登录成功时写 `last_ip`，缺任一列都会让对应操作报错（注册/登录直接失败）。
  >
  > ⚠️ 删库会丢失所有账号与战绩，重建后 `user` 表只剩内置 `admin`，历史对局记录不可恢复。

### 配置项与环境变量

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_PASSWORD` | 无（**必填**） | 数据库口令；未设置时启动直接失败，启动时会做一次连通性校验 |
| `DB_USERNAME` | `root` | 数据库账号 |
| `DB_URL` | 云端 TiDB 连接串 | 连接串（含 `allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`）；**上线务必显式设置**为本机或自建库，默认值只是开发用云端库 |
| `FIVECHESS_CORS_ORIGINS` | `https://blog.donghao.ltd:*` | 跨域来源白名单（逗号分隔，支持 `http://host:*` 通配）；本地调试可加 `http://localhost:*,null` |
| `SERVER_SSL_ENABLED` | `false` | 是否以 HTTPS 启动 |
| `SERVER_SSL_KEY_STORE_PASSWORD` | `fivechess` | 证书库口令（生产请用环境变量覆盖） |
| `FIVECHESS_ADMIN_LOGIN` | `admin` | 管理员账号名 |
| `FIVECHESS_SECURITY_ENABLED` | `true` | 滥用防护总开关；`false` 时全部限流 / 锁定 / 连接数 / 聊天频率限制放行（压测与自动化测试使用） |
| `FIVECHESS_TRUST_FORWARDED_FOR` | `auto` | 是否按转发头识别真实来源 IP：`auto`（检出 Render 平台时开启，其余环境自动关闭）/ `true` / `false`。**直接对外不必设置**——`auto` 本身就是关闭；Nginx 等代理后设 `true`。不要为了「保险」手动写 `false`，换到边缘平台后它会覆盖自动判定 |
| `FIVECHESS_TRUST_CLOUDFLARE_HEADER` | `auto` | 是否直接采信边缘覆盖写入的 `CF-Connecting-IP`：`auto` / `true` / `false`。请求链路必定经过 Cloudflare（含 Render）时开启，优先级高于 `X-Forwarded-For`，不受多层边缘影响；**后端可被直连时不必设置**（`auto` 即为关闭） |
| `FIVECHESS_FORWARDED_FOR_HOPS` | `1` | 可信代理层数：从 `X-Forwarded-For` 右起第几跳是真实客户端。单层 Nginx 填 `1`，CDN + Nginx 填 `2`；只在没有可用 `CF-Connecting-IP` 时生效。**必须与实际层数一致**，填大了等于允许伪造来源 IP |
| `FIVECHESS_BAN_IPV6_PREFIX_BITS` | `64` | IPv6 封禁比对掩码位数。`/64` 能拦住「同一线路换隐私地址」的绕过（运营商常规做法）；设为 `128` 退化为精确匹配，换地址即可绕过 |
| `FIVECHESS_HTTP_PER_IP_LIMIT` / `FIVECHESS_HTTP_WINDOW_MS` | `600` / `60000` | 单 IP 在窗口内的 HTTP 请求上限与窗口长度（毫秒） |
| `FIVECHESS_SESSION_CHECK_PER_IP_LIMIT` | `120` | `/sessionCheck` 会话探测接口的独立限流：单 IP 在 HTTP 窗口内可调用次数。该接口不占 HTTP 总额度（需供未登录页面轮询），因此单独计量 |
| `FIVECHESS_LOGIN_IP_LIMIT` / `FIVECHESS_LOGIN_ACCOUNT_LIMIT` | `10` / `10` | 单 IP、单账号在窗口内的登录尝试上限 |
| `FIVECHESS_LOGIN_LOCK_THRESHOLD` / `FIVECHESS_LOGIN_LOCK_MS` | `5` / `300000` | 账号连续失败多少次后锁定、锁定时长（毫秒） |
| `FIVECHESS_REGISTER_IP_LIMIT` | `10` | 单 IP 在窗口内可注册的账号数上限 |
| `FIVECHESS_WS_MAX_PER_IP` | `20` | 单 IP 允许的并发 WebSocket 连接数上限 |
| `FIVECHESS_CHAT_PER_USER_LIMIT` / `FIVECHESS_CHAT_WINDOW_MS` | `20` / `10000` | 同一用户单位时间内聊天条数上限与窗口长度（毫秒） |
| `FIVECHESS_CHAT_RETENTION_DAYS` | `30` | 聊天记录保留天数，超期记录由后台任务定期清理 |
| `FIVECHESS_ROOM_PASSWORD_TRY_LIMIT` / `FIVECHESS_ROOM_PASSWORD_WINDOW_MS` | `10` / `60000` | 私人房间密码：同一账号在窗口内的尝试次数上限。超限后即使密码正确也先挡一段时间，防止爆破 |
| `FIVECHESS_TOKEN_TTL_HOURS` | `12` | 登录令牌闲置有效期（小时）：超过该时长未使用即失效需重新登录；`0` 表示永不过期。请求接口、建立连接、页面心跳都会续期 |
| `FIVECHESS_AVATAR_API_BASE` | Render 上自动取 `RENDER_EXTERNAL_URL`，否则空 | 后端对外地址。**前后端不同域名时必须配置**（如 `https://chess-server-p7h1.onrender.com`），上传头像地址才会拼成绝对地址；留空输出相对路径 `/avatar/{id}` |
| `FIVECHESS_AVATAR_STATIC_BASE` | `/box/Chess` | 前端静态基路径（站点子路径）。后端据此在下发的内置头像与默认头像前加前缀，得到 `/box/Chess/static/image/...`。部署到域名根时设为空字符串即可；地址若再变，改这里或前端 `index.js` 的 `avatarStaticBase` |
| `FIVECHESS_AVATAR_BUILTIN_DIR` | 空 | 可选：内置头像目录，仅在 classpath 清单 `avatar-builtin.txt` 缺失时用于本地扫描兜底 |

### 识别真实来源 IP（按部署形态选配置）

服务端把来源 IP 用于限流、封禁与注册记录，它只能来自两处：TCP 对端地址，或可信网关写入的请求头。**取哪个头的逻辑是通用的（任何环境都能取对），但「是否读请求头」必须按实际链路指定一次**——程序若一律信任，任何客户端自带一个 `X-Forwarded-For: 1.2.3.4` 就能冒充任意地址，限流与封号会全部失效。

| 部署形态 | 需要配置 | 取到的地址 |
| --- | --- | --- |
| **Render（当前线上形态）** | **不用配**：检出 `RENDER` 环境变量后 `auto` 自动开启 | `CF-Connecting-IP`（Render 入站前置 Cloudflare，`getRemoteAddr()` 只会拿到 CF 节点） |
| 服务器 + 直接 `java -jar` 监听公网端口，前面没有任何网关 | 不用配（`auto` 自动关闭） | `getRemoteAddr()`，本身就是真实地址 |
| 服务器 + Nginx / 宝塔 / 1Panel 反向代理（无 CDN） | `FIVECHESS_TRUST_FORWARDED_FOR=true`，`FIVECHESS_FORWARDED_FOR_HOPS` 保持 `1` | `X-Forwarded-For` 右起第 1 跳 |
| Nginx 前面还有一层 CDN（非 Cloudflare） | 同上，`HOPS=2` | 右起第 2 跳 |
| Cloudflare 直接回源（含 Cloudflare Tunnel） | `FIVECHESS_TRUST_CLOUDFLARE_HEADER=true` | `CF-Connecting-IP`，边缘覆盖写，客户端伪造无效 |
| 云负载均衡（ALB / SLB / CLB 等） | 先看日志提示；多数只追加一跳，`HOPS=1` 即可 | 同上 |
| 其他 PaaS（Heroku / Railway / Fly / Koyeb 等） | 先看日志提示，按其结论配置 | 视平台写入的头而定 |

**任何环境都不要为了「保险」手动把它设成 `false`。** 直接对外时 `auto` 本来就是关闭的，写 `false` 与不写完全等价，唯一的作用是留下一个将来会忘的坑：一旦后端又搬到 CDN / PaaS 后面，或在前面加了网关，这条 `false` 会覆盖自动判定，来源 IP 静默退回成节点地址（线上曾因此把 Cloudflare 节点记成用户 IP）。需要保守时应当**不配**，而不是配 `false`。

反向代理要确保透传这两个头（宝塔、1Panel 的默认站点配置已包含，手工写时照抄）：

```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Real-IP $remote_addr;
```

换环境后按三步确认（一分钟内可完成）：

1. 启动日志里搜 `[security]`，应有两行 `trust-forwarded-for=` 与 `trust-cloudflare-header=`。日志会同时打印**读到的原始值**与解析结果，例如 `trust-cloudflare-header="auto" -> true (auto: edge-platform=true)`。若显示 `"false" -> false (configured: ...)` 而你并没有配过这个变量，说明它被外部显式指定了——按可能性排序：**部署仓库里被 `Dockerfile` 拷进容器的 `application.yml`（最常见，见下节）**、平台侧共享变量组（Render 的 Environment Group、Blueprint 的 `render.yaml`）、镜像内置 `ENV`。后两种不会出现在服务的 Environment 列表里，第一种甚至不在环境变量体系内；在该服务里新增一条同名变量设为 `auto` 可以覆盖前两者；
2. 用页面注册一个测试号，然后 `SELECT register_ip, last_ip FROM user WHERE login='<测试号>'`；
3. 与 `https://ipinfo.io` 或 `https://ip.sb` 上看到的公网地址比对。若记下来的是 `104.16.0.0/12` 或 `172.64.0.0/13` 段（形如 `104.23.160.x`、`172.71.150.x`），说明链路经过 Cloudflare 而开关没开；服务端也会在首次遇到这种请求时打印一条带实际头值与结论的 `[security]` 提示。

### 外部配置文件会覆盖 jar 内配置

Spring Boot 启动时会读取**当前工作目录**下的 `application.yml`，其优先级高于 jar 内的 `BOOT-INF/classes/application.yml`。容器部署时这一点很容易被忽略：若 `Dockerfile` 里有

```dockerfile
COPY application.yml application.yml
```

构建上下文根目录的这份文件就会落到 `/app/application.yml`，并**覆盖 jar 内所有同名配置项**。后果是：改 jar 里的默认值不生效，排查时还容易误判成「环境变量被平台注入」，而实际来源是仓库里这个文件。

判断依据就是启动日志里打印的原始值：

| 日志形态 | 含义 |
| --- | --- |
| `"auto" -> true (auto: ...)` | 取自 jar 内默认值 `${...:auto}`，未被覆盖 |
| `"false" -> false (configured: ...)` | 被外部显式指定：环境变量、命令行参数，或这份 `application.yml` |

同一份日志里若出现 `forwarded-for="false"` 而 `cloudflare-header="auto"`，说明外部只覆盖了前者，按项比对即可定位文件里哪几行在生效。

配置来源应当只保留一处：要么删掉 `Dockerfile` 的 `COPY` 行、让 jar 内配置唯一起作用（删前先确认外部文件里没有必需项，如数据库连接串、CORS 域名、头像地址前缀，否则会回落到默认值），要么明确把这份外部文件当作唯一配置入口，并保证它与 jar 内默认值同步。

### 如何打包（构建 jar）

后端是标准 Maven 项目，一条命令即可打出可运行的 fat jar：

```bash
# 在项目根目录执行（推荐，含全部白盒测试，约需数分钟）
mvn -f server/pom.xml clean package

# 只想快速出包、跳过测试时（发布前仍建议至少跑一次完整测试）
mvn -f server/pom.xml clean package -DskipTests
```

- **产物**：`server/target/FiveChess-0.0.1-SNAPSHOT.jar`（约 26 MB，Spring Boot repackage 后的可执行 fat jar，内嵌 Tomcat，`java -jar` 直接运行）。
- **环境要求**：`JAVA_HOME` 必须指向 **JDK**（含 `javac`）。如果报 `No compiler is provided in this environment`，说明 `JAVA_HOME` 指向了 JRE，需要改为 JDK，例如：
  - Git Bash / Linux：`export JAVA_HOME="/c/Program Files/Java/jdk1.8.0_491"`
  - Windows CMD：`set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_491`
  - PowerShell：`$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_491"`
- **发布归档**：把产物复制到 `release/` 并按惯例改名（时间戳到分钟）：

  ```bash
  cp server/target/FiveChess-0.0.1-SNAPSHOT.jar release/FiveChess-server-$(date +%Y%m%d-%H%M).jar
  sha256sum release/FiveChess-server-*.jar   # 记录校验值
  ```

- **打包后自检**（建议每次都做，30 秒）：

  ```bash
  DB_URL='jdbc:mysql://127.0.0.1:3306/chess?...' DB_USERNAME=root DB_PASSWORD=*** \
  java -jar server/target/FiveChess-0.0.1-SNAPSHOT.jar --server.port=8090 &
  # 等 Tomcat started 后：
  curl http://127.0.0.1:8090/health     # 应返回 UP,服务正常运行中
  ```

- **注意**：jar 只包含后端（含内嵌静态资源），**不包含 `web/` 目录**。发布时后端 jar 与 `web/` 前端目录必须一起部署、一起更新，版本要配套。

### 启动后端

```bash
# 开发热跑（不产出 jar）
DB_PASSWORD=你的口令 mvn -f server/pom.xml spring-boot:run

# 运行打包好的 jar（打包方法见上一节「如何打包」）
DB_URL='jdbc:mysql://127.0.0.1:3306/chess?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false' \
DB_USERNAME=root DB_PASSWORD=你的口令 \
java -jar release/FiveChess-server-<版本号>.jar
```

默认以 HTTP 方式监听 `8081` 端口（`http://127.0.0.1:8081`）。

### 启动前端

前端为静态页面，用任意静态服务器托管 `web/` 目录后访问 `login.html` 即可（例如 `python -m http.server 8080 --directory web`）。接口地址默认取 `127.0.0.1:8081`，也可在页面中通过 `window.FIVECHESS_SERVER`（主机:端口）与 `window.FIVECHESS_PROTOCOL`（`http` / `https`）覆盖。

> 不要用 `file://` 直接打开页面：浏览器会限制部分能力，且跨域白名单需额外放行 `null`。

> **部署在子路径 `/box/Chess/` 下**：把 `web/` 整个目录放到站点的 `/box/Chess/` 位置（访问地址形如 `https://域名/box/Chess/login.html`），页面内的 `./static/...` 相对引用会自动跟随。头像这类由**服务端下发根绝对路径**的资源，前端会把 `/static/image/...` 改写成 `/box/Chess/static/image/...`；后端默认 `fivechess.avatar.static-base=/box/Chess` 也直接下发带前缀的地址。部署路径若再变：前端改 `web/static/js/index.js` 里 `data.avatarStaticBase`，后端改 `FIVECHESS_AVATAR_STATIC_BASE`（或 `application.yml`），两处保持一致。

> **站点级公共脚本 `/js/loper_too.js`**：`index.html` 与 `login.html` 都用根绝对路径 `/js/loper_too.js` 引用它。该文件位于**网站根目录**（与 `/box/Chess/` 同级），不在本项目 `web/` 目录内，因此本地开发或项目内搜索找不到它是正常的，**不要当成死引用删掉**——删了线上会 404。它由站点统一维护，本项目不负责分发。

> **头像的存储与前后端分离部署**：用户上传的头像图片存在数据库 `user_avatar` 表里，由后端接口 `GET /avatar/{用户id}` 输出，**后端不读写任何本地图片文件**，所以前后端不在同一台机器也能正常用（不会出现「上传成功但页面裂图」「容器重启头像全丢」）。内置头像（`/box/Chess/static/image/touxiang/`，全部为 `.webp`）与管理员默认头像（`/box/Chess/static/image/avatar.webp`）仍由前端静态服务托管。
>
> 前后端不同域名时，必须配置 `FIVECHESS_AVATAR_API_BASE` 为后端对外地址，否则浏览器会按页面域名去请求 `/avatar/{id}` 而 404。部署在 Render 时该值自动取自平台注入的 `RENDER_EXTERNAL_URL`，无需手工设置；自建服务器示例：`FIVECHESS_AVATAR_API_BASE=https://chess.example.com`。
>
> 数据库需有 `user_avatar` 表（`数据库/chess.sql` 已包含；已有库按「数据库」章节给出的 `CREATE TABLE IF NOT EXISTS` 语句补一次即可）。表缺失时头像上传会自动降级为「只用内置头像」，登录与对局不受影响。
>
> **部署必须同步更新的前端文件**：`web/static/js/index.js`（头像地址解析与降级）、`web/static/js/login.js`（登录/注册的提交等待态）、`web/index.html`（头像绑定与脚本版本号）、`web/static/image/default-avatar.svg`（中性占位图）、`web/static/css/semantic.min.css`（见下节「首屏渲染」）、`web/login.html`（注册面板默认隐藏、首帧兜底与首页资源预取）、`web/static/image/` 下的 5 张 WebP 背景图与 `avatar.webp`、整个 `web/static/image/touxiang/` 目录（1375 张内置头像，全部为 `.webp`；与 jar 内 `avatar-builtin.txt` 清单必须同批部署）。只更新其中一部分会出现「头像空白」「背景图 404」等问题。

> **首屏渲染约束**：改动 `semantic.min.css`、`login.html`、`index.html` 时必须遵守下面三条，否则会出现白屏、黑屏或文字延迟出现。
> 1. `web/static/css/semantic.min.css` 顶部**不得**引入第三方字体外链（如 Google Fonts）。外链会阻塞渲染，国内网络常要等到超时，期间整页空白；且 `Lato` 无中文字形，中文要等系统回退字体异步解析后才出字。中文统一走系统字体。
> 2. `web/login.html` 的注册面板在样式里默认 `#register{display:none}`，不要改成由 `login.js` 事后隐藏，否则首帧会把登录与注册两张卡片一起画出来再收掉。
> 3. `web/index.html` 保留启动期背景层 `#bootBg`（固定定位、`pointer-events:none`，Vue 挂载后被 app 内的 `.bg-layer` 覆盖）、`bg-2.webp` 预加载，以及写在样式表之前的首帧兜底色；`#app` 上的 `v-cloak` 保证挂载前不显示未编译模板。

> **登录到上线的链路**：登录后从 `login.html` 跳转 `index.html`，WebSocket 在 `index.js` 执行后才建立，因此上线速度取决于首页资源是否已在缓存中。
> 1. `web/login.html` 用 `<link rel="prefetch">` 预取首页资源（`semantic.min.js`、`vue@2.js`、`index.js`、`index.css` 等），用户输入账号密码的时间即完成预热。**这些 `?v=` 必须与 `index.html` 一致**，否则会各拉一份。
> 2. 服务端在鉴权通过时即写入在线列表并广播，不等客户端再发 `report-nick`，下发给本人的第一份列表已包含自己。客户端的 `report-nick` 保留，用于改名。
> 3. 背景图与内置头像使用 WebP，控制首屏下载字节数。

> **登录/注册提交的等待反馈**：弱网或后端冷启动时一次请求可能要十几秒，点击后必须立刻有反馈，否则用户会反复点击——重复登录会让两次签发的令牌互相顶掉。行为分三段，由 `web/static/js/login.js` 的 `fcSetSubmitting()` 控制：
> 1. **点击瞬间**：按钮禁用＋转圈，文案变「登录中…／注册中…」。按钮保持主色，不靠「变灰」表示禁用：`.ui.button.fc-submit.is-loading` 用 `opacity:1 !important` 压过 Semantic 的 `.ui.button:disabled{opacity:.45}`，变灰反而像是点坏了。
> 2. **5 秒仍无响应**：按钮下方出现「网络较慢，正在等待服务器响应」＋呼吸点。阈值刻意留长：正常网速几百毫秒内就返回，阈值过短会让每次登录都闪一行多余的字。
> 3. **10 秒仍无响应**：文案改为「服务器还在努力赶路中，请再给它一点点时间，不要离开我，马上就好……」，按 `\n` 断成两行（由 `.fc-wait-text{white-space:pre-line}` 渲染，`text-wrap:pretty` 对中文不生效），同时隐藏末尾呼吸点（文案自带省略号，否则重复）。
>
> 请求成功时**不恢复按钮**，一直保持「登录中…」直到页面跳转；只有失败与网络异常才恢复，避免按钮闪回「登录」被误认为没点上。这段样式内联在 `web/login.html`（`login.css` 不含相关规则，其 `?v=` 不必动），但改动 `login.js` 后必须同步升 `login.html` 里它的 `?v=`，两个文件同批部署。

> **静态资源缓存（前端与内置头像托管在 Vercel，无需额外配置）**：线上响应头为
> `Cache-Control: public, s-maxage=86400, max-age=86400` + `ETag`（`Server: Vercel`、`X-Vercel-Cache: HIT`），
> 即浏览器与边缘节点各缓存 1 天，条件请求返回 304（响应体为空）。
>
> 由此有三条约束：
> 1. **不需要配 `immutable`**，也不需要在 Vercel 上写缓存配置。每天一次复验的成本是 HTTP/2 下一轮并行请求 + 304，可忽略。
> 2. **`?v=` 在这个部署形态下是刚需，不是可选**。浏览器会缓存 1 天，改了 CSS/JS 却忘记改 `?v=`，老访客最长要等 1 天才能看到新文件。改完用这条自查，输出为空才算干净：
>    `grep -nE '(href|src)="\./static/[^"]*\.(css|js)"' web/*.html | grep -v '?v='`
> 3. 图片（背景图、内置头像）同样是 1 天缓存。换图后最长 1 天收敛；要立即生效就让文件名变化（如 `bg-2-20261123.webp`），比等缓存快。
>
> 如果确实想把最后这点复验也省掉（一年零请求），可以在**站点根仓库**（不是本项目的 `web/`，本项目位于该仓库的 `box/Chess/` 子目录）加一个 `vercel.json`：
>
> ```json
> {
>   "headers": [
>     {
>       "source": "/box/Chess/static/(.*)\\.(css|js)",
>       "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }]
>     },
>     {
>       "source": "/box/Chess/static/(.*)\\.(webp|png|jpe?g|gif|svg)",
>       "headers": [{ "key": "Cache-Control", "value": "public, max-age=2592000" }]
>     }
>   ]
> }
> ```
>
> 部署后确认生效：`curl -sI https://<域名>/box/Chess/static/css/index.css | grep -i cache-control`。
> **开 immutable 之前必须先确认上面那条自查命令输出为空**，否则漏了 `?v=` 的文件会变成一年刷不掉。
>
> 自建服务器（Nginx）的等价配置——`location` 里写 `add_header` 会切断 `server` 块的同名指令继承（`X-Frame-Options` 等需要重列），也不要和 `expires` 混用，两者会各输出一个 `Cache-Control`：
>
> ```nginx
> location ~* ^/box/Chess/static/.*\.(css|js)$ {
>     add_header Cache-Control "public, max-age=31536000, immutable" always;
>     access_log off;
> }
> location ~* ^/box/Chess/static/.*\.(webp|png|jpe?g|gif|svg|woff2?|ttf|eot|ico)$ {
>     add_header Cache-Control "public, max-age=2592000" always;
>     access_log off;
> }
> ```
>
> WebP 需要现代浏览器（IE 不支持，本项目其它能力也已不依赖 IE）。

### 内置头像目录（`web/static/image/touxiang/`）

没上传过头像的普通用户，每次登录从这个目录随机取一张（仅本次会话内固定）。图片本身由前端静态服务托管，**后端不读取图片文件**，只读一份打包在 jar 里的文件名清单 `server/src/main/resources/avatar-builtin.txt`。

- 规格：1375 张、约 33 MB（平均 25 KB，最大约 200 KB），全部为 `.webp`；清单与目录内容完全一致。
- **增删图片必须三处同步**：目录文件 → 清单 → 重新打包。少做一步，要么「图放了永远分配不到」，要么「清单里有、图没了」导致裂图。

  ```bash
  # ① 放入 / 删除图片（文件名建议纯英文数字，不要子目录、不要空格；新图请存成 .webp）
  cp 新头像.webp web/static/image/touxiang/

  # ② 重新生成清单（在项目根目录执行）
  { echo "# 内置头像文件名清单（由 web/static/image/touxiang 生成，供后端随机分配时使用）";
    echo "# 图片本身由前端静态服务托管，后端不读取文件；增删 touxiang 里的图片后请同步更新本文件";
    ls web/static/image/touxiang | sort; } > server/src/main/resources/avatar-builtin.txt

  # ③ 重新打包并部署（清单在 jar 的 classpath 里，不重打包后端看不到）
  mvn -f server/pom.xml clean package
  ```

- 只加文件不改清单：图片能通过 URL 直接打开，但后端永远分配不到它（等于白放）。
- 只删文件不改清单：随机分配可能命中已删除的文件名，页面裂图（前端没有 `onerror` 兜底），要等该用户下次登录重新随机才恢复。本地 Windows 不区分大小写、线上 Linux 静态服务区分，文件名必须逐字符一致。
- 清单加载规则：只接受 `jpg` / `jpeg` / `png` / `gif` / `webp`（不区分大小写），其它扩展名的行会被静默忽略；行顺序无关，启动时会排序。内置头像目录目前只放 `.webp`，新增图也请沿用，避免同一目录混格式。
- 后端**只在启动时**读一次清单，所以改完必须重启进程；又因为清单在 jar 内，实际动作就是重新打包。
- 不受影响的部分：用户上传过的头像存在 `user_avatar` 表、走 `/avatar/{id}`；管理员默认头像 `static/image/avatar.webp` 不在该目录内，不要删也不要挪。
- 建议新图压到 256×256、单张 30 KB 以内（与上传头像的处理一致），页面里只按小圆头像展示。

### 自动化测试脚本（`测试/`）

脚本只依赖 Python 3 标准库与 Node 18+ 内置 `fetch` / `ws`（无需 `pip install` / `npm install`），默认连本地实例，第一个参数可覆盖地址。不同用途对实例参数的要求不同，混用会互相干扰：

| 脚本 | 用例数 | 默认地址 | 实例启动要求 |
| --- | ---: | --- | --- |
| `http_feature.py` | 80 | `127.0.0.1:8098` | 额度放宽（默认配置即可，但建议 `FIVECHESS_REGISTER_IP_LIMIT` 调大） |
| `ws_feature.mjs` | 75 | `127.0.0.1:8098` | 同上；实时对局链路全在这 |
| `http_security.py` | 71 | `127.0.0.1:8098` | 额度放宽，否则正常用例会被 429 打断 |
| `ws_security.mjs` | 32 | `127.0.0.1:8098` | 同上 |
| `ws_reconnect_pending.mjs` | 14 | `127.0.0.1:8099` | 安全开关关闭即可；最后一节要等请求 TTL 走完，整体约 80 秒 |
| `ip_spoof.py` | 17 | `127.0.0.1:8096` | **必须** `FIVECHESS_TRUST_FORWARDED_FOR=true`、`FIVECHESS_FORWARDED_FOR_HOPS=1`、`FIVECHESS_BAN_IPV6_PREFIX_BITS=64`，并放宽注册/登录额度 |
| `http_ratelimit.py` | 12 | `127.0.0.1:8097` | 额度**压紧**：`HTTP_PER_IP_LIMIT=15`、`REGISTER_IP_LIMIT=3`、`LOGIN_*_LIMIT=5`、`LOGIN_LOCK_THRESHOLD=3`、`SESSION_CHECK_PER_IP_LIMIT=5` |
| `ws_ratelimit.mjs` | 5 | `127.0.0.1:8097` | `CHAT_PER_USER_LIMIT=5`、`WS_MAX_PER_IP=3`；**要在 `http_ratelimit.py` 之前跑**（后者会把总限流打满） |
| `perf.mjs` | — | `127.0.0.1:8098` | 并发压测，输出 avg/p50/p95/p99/max/qps；建议 `-Xmx512m` 起服务以暴露内存问题 |

- 每个脚本最后打印 `PASS n / FAIL m` 与失败项清单，退出码非 0 即有失败。
- 安全 / 限流脚本会直接连 MySQL 造前置数据（封号、改 IP 等），通过第 3 个参数指定**测试库**，不要把生产库名传进去。
- 脚本自身会注册大量临时账号（昵称带时间戳+序号），跑完建议直接用 `数据库/chess.sql` 重建测试库，而不是逐条清理。

### 发布产物

构建产物放在 `release/`，历史版本与测试记录归档在同一目录，按文件名中的日期区分。

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `FiveChess-server-20261126-1332.jar` | 27,489,339 字节 | `4DC41071AF5D26865591797E7F106A190E48B5A069E0BE5C250884D72F3DE256` |

打包方法与自检步骤见「如何打包（构建 jar）」。

> 部署时前端与后端必须同批更新：jar 不包含 `web/`，只换后端会导致页面缺少对应入口与样式。

### HTTPS 支持

默认关闭，需要显式开启。

- 配置项（`server/src/main/resources/application.yml`）：

  ```yaml
  server:
    port: 8081
    ssl:
      enabled: ${SERVER_SSL_ENABLED:false}
      key-store: classpath:keystore.p12
      key-store-type: PKCS12
      key-store-password: ${SERVER_SSL_KEY_STORE_PASSWORD:fivechess}
      key-alias: fivechess
  ```

- 证书：`server/src/main/resources/keystore.p12`（自签证书，别名 `fivechess`，口令 `fivechess`，会打进可执行 jar）。
- 开启方式（任选其一）：

  ```bash
  # 环境变量
  SERVER_SSL_ENABLED=true DB_PASSWORD=你的口令 java -jar target/FiveChess-0.0.1-SNAPSHOT.jar
  # 启动参数
  DB_PASSWORD=你的口令 java -jar target/FiveChess-0.0.1-SNAPSHOT.jar --server.ssl.enabled=true
  ```

- 前端适配：`web/static/js/MyUtil.js` 跟随页面协议自动选择 `https` / `http` 与 `wss` / `ws`（也可用 `window.FIVECHESS_PROTOCOL` 强制指定），避免 https 页面请求 http 接口被按混合内容拦截。
- 使用注意：页面本身也要用 https 访问；自签证书会被浏览器提示「不安全」，生产请换成正式证书，并用环境变量提供证书口令。

## 已知边界

- 房间、在线列表、邀请冷却、断线宽限期、令牌、限流计数等状态保存在**单实例内存**中，多实例部署需引入共享存储或粘性会话。
- **断连超过 1.5 秒即退出房间**：`offlineGraceMs`（1.5 秒）宽限期结束后，用户会被同时移出在线列表并退出所在房间。宽限期内重连（页面正常刷新）可完整恢复房间与棋盘；强刷、冷缓存或弱网导致断连超时则按离线处理——房主离线时房客继承房主且当前比分与棋局清空，房内无人则房间解散。心跳超时（30 秒无活动）走另一条路径，另有 5 分钟房间保留期。
- **邀请与「悔棋 / 求和 / 重开」只在重连时补推一次**：这几类消息点对点发送一次，服务端不留离线副本。收信方在投递瞬间断线时消息会落空，但服务端保留着带过期时间的待办状态（邀请 30 秒、对局请求 60 秒），鉴权成功后会按待办补推一次，因此闪断后重连仍能看到弹窗。超出有效期的不再补推，对局请求还会主动告知发起方「对手未响应」。
- 登录令牌按闲置时长过期（默认 12 小时，可配置为不过期），服务重启后全部失效。
- 账号连续失败锁定可被他人恶意触发（5 次错误锁 5 分钟），已由单 IP 登录限流缓解。
- 数据库口令缺失时服务直接启动失败，这是有意设计。
- 内置头像清单（`avatar-builtin.txt`）打包在 jar 内，增删图片必须重新打包后端才能生效；未上传头像的普通用户每次登录都会重新随机一张（不会固定），这是有意设计（见「内置头像目录」章节）。
- 首屏要等 `semantic.min.css`（约 630 KB）下载完才会绘制，慢速网络下这段时间为空白（本地/局域网约 0.1 秒，限速 1.6 Mbps 约 5 秒）。可进一步压缩：在 `application.yml` 打开 `server.compression.enabled: true`（默认已包含 `text/css`、`application/javascript`、`application/json`、`image/svg+xml`），630 KB 可压到约 90 KB；该配置改的是后端，需重新打包 jar 才生效。
- 静态文件由 Vercel 托管，缓存头是平台默认的 `max-age=86400`（1 天，不可在项目内改，除非加 `vercel.json`）；jar 与 `web/` 里都不含缓存配置。1 天到期后浏览器发条件请求，Vercel 回 304（无 body、边缘命中），成本可忽略。
- 改 CSS/JS 必须同步改 HTML 里的 `?v=`：浏览器缓存 1 天，忘了改就意味着老访客最长 1 天看不到新文件。这靠人工纪律维持，项目没有构建期自动改写版本号的流程。
