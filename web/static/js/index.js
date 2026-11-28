/* index.html 页面主脚本：Vue 应用实例、房间与棋局逻辑、WebSocket 通信、棋盘绘制。 */
	//顶部连接提示条的最短停留时间：任何一次连接/重连都立刻出现，但快于这个时长就恢复时
	//至少留满再淡出，否则一闪而过会像界面故障（500ms 内恢复本身用户几乎无感）。
	const FC_BANNER_MIN_MS = 500;
	let socket;//好友之间发送消息的服务器
	//「请求类」操作的等待计时器：对手长时间不响应时自动解除等待，避免界面被永久锁死
	let fcWaitingTimer = null;

	let app = new Vue({
		el:"#app",
		data:{
			lastOppoX: -1,    // 对手最后一步X坐标
			lastOppoY: -1,    // 对手最后一步Y坐标
			// 复盘棋盘标记变量
			lastReviewX: -1,
			lastReviewY: -1,
			url:getHttpUrl(),//接口地址
			avatar:'./static/image/default-avatar.svg', //本人头像：登录后由服务端下发（上传过的用本人文件，否则随机内置头像）
			avatarBusy:false, //头像上传中：避免重复点击发起多次上传
			//头像加载失败时使用的中性占位图。历史上这里回退的是 ./static/image/avatar.jpg，
			//那张图是管理员专属默认头像，普通用户一旦加载失败就会「变成管理员」，所以单独放一张
			placeholderAvatar:'./static/image/default-avatar.svg',
			//头像静态资源基路径：项目部署在站点子路径 /box/Chess/ 下。
			//服务端下发的是根绝对路径 /static/image/...，浏览器会跑到域名根取图而 404，
			//所以统一改写成 /box/Chess/static/image/...。站点路径若有变化，只改这一处。
			avatarStaticBase:'/box/Chess/',
			avatarFallbackUsed:false, //本人头像是否已经降到占位图（便于排查，也避免同一张图反复触发 error）
			avatarStage:0,           //本人头像当前尝试到第几个候选（0=配置的基路径，1=页面路径推导，2=域名根，3=占位图）
			roomAvatarStages:{},     //房间卡片头像的候选进度：key -> 序号
			addImage:'./static/image/add.png',//加房间的指示图片url
			user:{},  //用户信息
			chessBoard:null,
			context:null,//棋盘
			background_image:'./static/image/bg-2.webp', //初始背景
			tableReady:false,
			roomReady:false,//房间是否创建好了
			//刷新恢复时（存在待恢复视图）首个渲染就不显示大厅，避免刷新过程中闪一下大厅
lobbyReady:(function(){ try{ return !sessionStorage.getItem('fcViewState'); }catch(e){ return true; } })(),//显示大厅
			//刷新前若处于房间/观战/管理员查看视图，首个渲染就让公共聊天窗口保持浮动宽度，
			//避免恢复过程中它先按大厅布局被拉长再回弹到 320px
			publicChatFloating:(function(){
				try{
					var sv = sessionStorage.getItem('fcViewState');
					if(!sv){ return false; }
					var view = (JSON.parse(sv) || {}).view;
					return view === 'room' || view === 'spectate' || view === 'adminView';
				}catch(e){ return false; }
			})(),
gameReady:false,//玩家是否已准备
			gameStarted:false,//本局是否已真正开始（用于区分“已准备待开局”和“对局进行中”）
			oppoReady:false,//对手是否准备
			wins:[],//赢法数组
			chess_Board:[],//棋盘的二维数组
			isMyTurn:true,//是否该我下棋
			isOver:false,//是否结束
			myWin:[],//记录我在赢法上的分值
			OppoWin:[],//记录计算机在赢法上的分值
			count:0,//赢法编号
			onlineRooms:[],//在线的房间
			roomSearch:'',            //大厅搜索房间的关键词（按房间号模糊匹配）
			myRoom:{},//我的房间
			myChessColor:'',//我的棋子颜色
			historySteps:[],//本局的历史步数
			messages:[],//消息
			text:'',//待发送的消息
			lastPlay:false,//上一手是不是我下的  如果是 悔棋只需返回一步  如果不是 则需要返回两步
			oppoId:'-1',//对手id
			requestWaiting:false,//是否处于等待响应状态
			incomingRequest:false,//桌面正在处理对手的请求（悔棋/和棋/重开弹窗未关闭）
			pendingActionVersion:null,//待处理请求携带的局序号：同意时原样回传，服务端据此拒绝跨局生效的过期请求
			curBoardVersion:null,//当前这一局的局序号：认输时回传，服务端据此丢弃上一局延迟到达的认输

			//////因为一个页面不能存放太多的数据  所以需要进行分页查询
			historyGames:[],  //历史对局
			pageNum:1,        //当前处于第几页
			hasLastPage:false,//是否有上一页
			hasNextPage:false,//是否有下一页
			totalNum:0,       //总共有多少条数据
			totalPage:0,     //总共有多少页

			//////以下是复盘的棋盘信息
			reviewSteps:[],  //复盘总步数
			reviewStepsHistory:[], //复盘历史
			reviewIndex:0, //复盘索引
			reviewReady:false,
			reviewBoard:null,
			reviewContext:null,//棋盘
			blackName:'',//黑棋
			whiteName:'',//白棋是谁
			autoReview:false,//是否自动播放

			//背景
			bgs:[{
				name:'淡紫远山',
				url:'./static/image/bg-2.webp',
			},{
				name:'墨色黄花',
				url:'./static/image/bg-1.webp',
			},{
				name:'暮色夕阳',
				url:'./static/image/bg-3.webp'
			},{
				name:'仲夏梦莲',
				url: './static/image/bg-4.webp'
			}],
onlineUserList: [],  // 在线用户昵称数组
			onlineSearch: '',  // 在线用户列表搜索关键词（按昵称/账号模糊匹配）
			onlineUsers: [],   // 在线用户明细：{id, nick, login, status, owner}
			pendingReview: null,  //刷新前处于复盘：历史列表加载完成后重新进入该局复盘
			restorePending: null, //刷新前处于房间/观战：等待服务端 room-restore
			restoring: false,     //刷新恢复中：此期间不显示大厅，避免闪一下大厅
			//刷新恢复期的居中提示（胶囊+转圈）：只在恢复真的超过 400ms 时才出现，
			//撤掉时机完全跟随 restoring，不另设定时器，避免提示与实际页面不同步
			restoreMask: false,
			restoreMaskText: '正在恢复上次的页面…',
			restoreMaskTimer: null,
			inviteCooldowns: {}, // 邀请被拒后的冷却倒计时：被邀请者id -> 剩余秒数
			invitePending: {}, // 已发出邀请、等待对方处理：被邀请者id -> true
			inviteBlocked: {}, // 当天屏蔽：该邀请者当天不可再邀请的被邀请者id -> true
			inviteCooldownTimer: null, // 冷却倒计时定时器
			onlineCount: 0,     // 实时在线人数
			mutedSelf: false,         // 本人是否被管理员禁言：为真时冻结公共与房间聊天窗口
			// ===== 管理员相关状态（仅管理员账号使用）=====
			adminViewOpen: false,     // 是否正在隐身查看某个房间
			adminRoomId: '',          // 正在查看的房间号
			adminRoomView: {},        // 被查看房间的人员信息
			adminChatRecords: [],     // 被查看房间的聊天记录（历史+实时）
			adminSteps: [],           // 被查看房间的对局步数（用于绘制棋盘）
			adminBannedOpen: false,   // 封号名单面板：仅大厅显示且默认隐藏（点击标题展开），最多显示 10 个，可就地解封
			adminBannedList: [],      // 已封号账号列表
			// 公共聊天
			publicMessages: [], // 公共聊天消息列表
			publicText: '',       // 公共聊天输入内容
			publicChatVisible: true, // 默认显示聊天窗口
spectateMode: false, // 是否处于观战（只读）状态
			spectateRoom: {},    // 正在观战的房间视图
			// 以下计时器/弹窗句柄只在脚本中使用，声明在 data 中便于统一重置与排查
			heartbeatTimer: null,
			// ===== 连接状态（断线重连 / 页面恢复）=====
			connState: 'idle',      // idle 未连接 / connecting 连接中 / open 已连接 / down 已断开
			connBanner: '',         // 顶部连接提示条的主体文案（连接中/断开重连时显示）
			connBannerHint: '',     // 提示条右侧的动作胶囊（如「立即重试」），由文案里的全角空格拆出来
			connBannerBusy: false,  // 提示条是否显示转圈：连接/重连/探活时为真，「网络已断开」这类静态提示为假
			connBannerAt: 0,        // 提示条出现时刻：用于保证至少停留 FC_BANNER_MIN_MS，避免快速重连只闪一下
			connBannerHideTimer: null, // 提示条延迟淡出的定时器（最短停留时间未到就等满再收）
			connProbeBanner: false, // 顶部条当前显示的是「正在确认连接状态…」，探活成功后要撤掉
			reconnectTimer: null,   // 下一次重连的定时器
			reconnectAttempt: 0,    // 已重连次数（用于退避）
			pendingAction: null,    // 断线期间用户发起的操作，重连成功后自动补发一次
			connWatchdogTimer: null,// 连接看门狗：页面可见却长时间没连上时主动重试
			livenessTimer: null,    // 心跳回执超时巡检
			probeTimer: null,       // 回到页面时对“看着还在”的连接做一次探活的定时器
			lastServerAt: 0,        // 最近一次收到服务端消息的时间
			gotHeartbeatAck: false, // 服务端是否支持心跳回执（不支持时不做静默超时判定）
			roomRestoredFlag: false,// 重连后是否收到房间快照
			restoreSeq: 0,          // 房间恢复校验序号（避免多次重连的定时器互相干扰）
			inviteCountdown: 30,
			inviteDialog: null,
			inviteCountdownTimer: null,
		},
		mounted(){
			//初始化用户
			this.user = JSON.parse(user);
			//头像：先用本地记录立即显示，再向服务端确认（登录时分配的随机头像在本次会话内不变）
			if(this.user && this.user.avatar){ this.avatar = this.user.avatar; this.avatarStage = 0; this.avatarFallbackUsed = false; }
			try{ this.refreshAvatar(); }catch(e){}
			//初始化赢法
			this.initWins();
			//初始化下棋数组
			this.initChessArray();
			//下棋
			this.drawChessBoard();
			//初始化复盘的棋盘
			this.initReviewBoard();
//初始化websocket
			this.initSocket();
			this.startConnWatchdog();
			//刷新页面恢复时不再提示「欢迎登录」，只有刚从登录页进入时才提示
			try{
				if(window.sessionStorage.getItem('fcJustLoggedIn')){
					window.sessionStorage.removeItem('fcJustLoggedIn');
					this.toastMsg('欢迎登录');
				}
				//恢复公共聊天窗口上次的显示/隐藏状态，刷新后不再一律展开
				var pc = window.sessionStorage.getItem('fcPublicChat');
				if(pc !== null){
					this.publicChatVisible = (pc === '1');
				}
				//刷新恢复期间先不显示大厅，避免恢复过程中先闪一下大厅再切回原页面
				var sv = this.readViewState();
				if(sv && sv.view){
					this.lobbyReady = false;
					this.restoring = true;
					//兜底：长时间未完成恢复（例如连不上服务器）也要显示大厅，避免空白页
					var self = this;
					setTimeout(function(){
						if(self.restoring){
							self.restoring = false;
							self.lobbyReady = true;
							self.publicChatFloating = false;
						}
					}, 4000);
				}
			}catch(e){}
//初始化下拉菜单（默认收起，点击展开）
			$('.ui.dropdown').dropdown();
			//首次渲染后检查禁言提示是否需要滚动（刷新恢复场景 self-state 会稍后到达，也会再次触发）
			this.updateMutedMarquee();
			window.addEventListener('resize', this.updateMutedMarquee);
			// ===== 页面可见性与浏览器缓存恢复 =====
			//跳转到别的页面 / 切到其他标签页 / 锁屏后再回来，连接往往已经断了但令牌仍然有效，
			//这里只做「静默重连」，不提示登录失效（这是此前“去别的页面再回来就提示失效”的根因）。
			var that = this;
			window.addEventListener('pagehide', function(){
				window.__fcPageHidden = true;
				window.__fcHiddenAt = Date.now();
			});
			window.addEventListener('pageshow', function(e){
				var wasHidden = window.__fcPageHidden;
				window.__fcPageHidden = false;
				//从浏览器前进/后退缓存恢复：连接一定已失效，必须重新建立
				if(e && e.persisted){
					//缓存恢复时旧连接已经死了，但 readyState 仍可能显示 OPEN：
					//先彻底丢弃旧连接再重建，避免界面“看着在线、其实发不出消息”
					try{
						if(socket){ socket.onclose = null; socket.onerror = null; socket.close(); socket = null; }
					}catch(err){}
					that.connState = 'down';
					that.reconnectAttempt = 0;
					that.resumeConnection(true);
					return;
				}
				if(wasHidden){ that.resumeConnection(false); }
			});
			document.addEventListener('visibilitychange', function(){
				if(document.visibilityState === 'hidden'){
					//记下离开时刻：回来时据此判断「是秒回还是要先探活」，决定要不要立刻给提示
					window.__fcHiddenAt = Date.now();
					return;
				}
				//重新可见即认为“不再处于离开页面的过程”，避免 pagehide 标记残留导致不再重连
				window.__fcPageHidden = false;
				that.resumeConnection(false);
			});
			window.addEventListener('online', function(){ that.resumeConnection(false); });
			window.addEventListener('offline', function(){
				if(that.connState === 'open'){
					//网络断了不用转圈：此刻没有任何正在进行的连接动作，转圈会像在空转
					that.setConnBanner('网络已断开，恢复后会自动重连…', false);
				}
			});
		},
		watch:{
			//被管理员禁言 / 解除禁言后立即重算滚动，不必刷新页面
			mutedSelf(){
				this.updateMutedMarquee();
			},
			//刷新恢复提示的开关：restoring 变 true 时延迟出现，变 false 时淡出。
			//收到 room-restore、2 秒判定「房间已结束」、4 秒连不上兜底，都会把 restoring 置回 false，
			//所以提示不会比页面多留、也不会提前消失。
			restoring(val){
				if(val){ this.armRestoreMask(); }
				else{ this.disarmRestoreMask(); }
			}
		},
computed:{
			// 渲染用头像：按候选顺序（配置的基路径 → 页面路径推导 → 域名根 → 占位图）取当前这一级
			avatarSrc(){
				var list = this.avatarCandidates(this.avatar);
				return list[Math.min(this.avatarStage || 0, list.length - 1)];
			},
			// 当前登录账号是否为管理员（登录响应中的 admin 标记，据此显示管理入口）
			isAdmin(){
				return !!(this.user && this.user.admin === true);
			},
			// 房间人员来源：自己所在的房间用 myRoom，管理员隐身查看时用被查看房间的数据
			roomViewSrc(){
				if(this.adminViewOpen){ return this.adminRoomView || {}; }
				if(this.spectateMode){ return this.spectateRoom || {}; }
				return this.myRoom || {};
			},
			// 房间聊天窗口的消息来源：自己所在的房间用 messages，管理员查看时用被查看房间的聊天记录
			roomChatMessages(){
				if(this.adminViewOpen){
					return (this.adminChatRecords || []).map(function(c){
						if(c && c.system){
							return {type:'system', time:c.time || '', content:c.content || '', position:'middle'};
						}
						return {type:'chat', time:c.time, senderNick:c.nick, content:c.content, position:'message-left'};
					});
				}
				return this.messages;
			},

			// 封号名单：最多展示 10 个（服务端按 id 倒序返回，取最近被封的 10 个）
			adminBannedView(){
				return (this.adminBannedList || []).slice(0, 10);
			},
			// 大厅房间列表：按搜索关键词对房间号做模糊匹配（忽略大小写与首尾空格，包含即命中）
			roomsFiltered(){
				let kw = (this.roomSearch || '').trim().toLowerCase();
				if(!kw){ return this.onlineRooms || []; }
				return (this.onlineRooms || []).filter(function(room){
					let no = (room && room.roomNo) ? String(room.roomNo) : '';
					return no.toLowerCase().indexOf(kw) >= 0;
				});
			},
			// 页面标题：大厅显示站点名；进入房间（含观战、隐身巡查）后显示「房间号：xxxxxx」
			titleText(){
				let inRoom = this.roomReady || this.spectateMode || this.adminViewOpen;
				let no = this.roomViewSrc ? this.roomViewSrc.roomNo : '';
				if(inRoom && no){ return '房间号：' + no; }
				return '---联机五子棋---';
			},
			// 当前所在房间的观战人数（标题栏展示）
			mySpectatorCount(){
				if(!this.roomReady || !this.myRoom){ return 0; }
				return this.myRoom.spectatorCount || 0;
			},
			// 房间内实时比分的显示条件：只有房间里真的坐着两个人时才显示，
			// 大厅、空房间（等对手）与观战/巡查之外的场景都不显示，避免出现 0 : 0 的空壳
			scoreBarVisible(){
				let v = this.roomViewSrc || {};
				let inRoom = this.roomReady || this.spectateMode || this.adminViewOpen;
				let hasOpponent = v.playerId2 !== undefined && v.playerId2 !== null
					&& String(v.playerId2) !== '' && String(v.playerId2) !== '-1';
				return !!inRoom && hasOpponent;
			},
			// 比分：房主（左）与房客（右）在当前房间内各自赢下的局数
			scoreLeft(){
				let v = this.roomViewSrc || {};
				return parseInt(v.scoreId1, 10) || 0;
			},
			scoreRight(){
				let v = this.roomViewSrc || {};
				return parseInt(v.scoreId2, 10) || 0;
			},
			// 比分条昵称的完整文案：只用于鼠标悬停提示，不受 6 字截断影响
			scoreFullNameLeft(){
				let v = this.roomViewSrc || {};
				return v.playerName1 || '房主';
			},
			scoreFullNameRight(){
				let v = this.roomViewSrc || {};
				return v.playerName2 || '房客';
			},
			// 比分条上的昵称：最多显示 6 个字，超出部分用省略号代替。
			// 按字符截断而不是靠 CSS 宽度裁切，保证「6 字上限」在任何字体、缩放比例下都成立
			scoreNameLeft(){
				return this.cutScoreName(this.scoreFullNameLeft);
			},
			scoreNameRight(){
				return this.cutScoreName(this.scoreFullNameRight);
			},
			// 自己坐哪一侧（用于昵称旁的「我」标记，方便一眼看出哪边是自己）
			scoreMeOnLeft(){
				let v = this.roomViewSrc || {};
				return !!v.playerId1 && String(v.playerId1) === String(this.user.id);
			},
			scoreMeOnRight(){
				let v = this.roomViewSrc || {};
				return !!v.playerId2 && String(v.playerId2) === String(this.user.id);
			},
			// 我是否为当前房间的房主（房间内房主标识）
			isMyRoomOwner(){
				if(!this.roomReady || !this.myRoom || this.myRoom.playerId1===undefined){
					return false;
				}
				return String(this.myRoom.playerId1) === String(this.user.id);
			},
// 房间可见性按钮文案（点击切换）
			roomOpenText(){
				return (this.myRoom && this.myRoom.open === false) ? '私人房间' : '公开房间';
			},
			// 房间当前是否公开（驱动标题左侧的公开/私人开关）
			roomIsOpen(){
				return !(this.myRoom && this.myRoom.open === false);
			},
			// 在线列表：自己永远置顶（在线时），便于一眼看到自己的状态
			onlineUsersSorted(){
				var list = this.onlineUsers || [];
				var me = String(this.user.id);
				var mine = list.filter(function(u){ return String(u.id) === me; });
				var others = list.filter(function(u){ return String(u.id) !== me; });
				return mine.concat(others);
			},
			// 在线列表搜索后的结果：按昵称/账号（大小写不敏感）模糊匹配，再按自身置顶排序
			onlineUsersFiltered(){
				var keyword = (this.onlineSearch || '').trim().toLowerCase();
				if(keyword === ''){
					return this.onlineUsersSorted;
				}
				var matched = (this.onlineUsersSorted || []).filter(function(u){
					var nick = (u.nick || '').toLowerCase();
					var login = (u.login || '').toLowerCase();
					var uid = String(u.id || '').toLowerCase();
					return nick.indexOf(keyword) !== -1
						|| login.indexOf(keyword) !== -1
						|| uid.indexOf(keyword) !== -1;
				});
				return matched;
			}
		},
		methods:{
			// 比分条昵称截断：最多 6 个字，第 7 个字符起用省略号代替。
			// 用 Array.from 按码点切分，避免把 emoji / 代理对切成半个字符
			cutScoreName(name){
				let text = String(name == null ? '' : name);
				let chars = Array.from(text);
				return chars.length > 6 ? chars.slice(0, 6).join('') + '…' : text;
			},
			// 房主切换房间可见性（公开/私人）
toggleRoomOpen(){
				if(!socket || socket.readyState !== 1){ return; }
				var that = this;
				//当前是公开房间 → 切私人：必须先设置密码；当前是私人 → 切公开：无需密码
				var goingPrivate = !(this.myRoom && this.myRoom.open === false);
				if(goingPrivate){
					askRoomPassword('设置房间密码',
						'切换为私人房间后，其他玩家需要输入该密码才能加入（4-20 位）。受邀玩家仍可直接加入。',
						function(password){
							socket.send(JSON.stringify({type:'toggle-room-open', open:'false', password:password}));
						});
					return;
				}
				socket.send(JSON.stringify({type:'toggle-room-open', open:'true'}));
			},
			// 房主踢出房内玩家
			kickGuest(){
				if(!socket || socket.readyState !== 1){ return; }
				//管理员不能被踢出：房主点击时立即给出提示；服务端仍会二次拒绝
				if(this.myRoom && this.myRoom.playerAdmin2){
					this.toastMsg('不能踢出管理员');
					return;
				}
				var target = this.myRoom ? this.myRoom.playerId2 : null;
				if(!target || target === '-1'){
					this.toastMsg('房间里没有其他玩家');
					return;
				}
				socket.send(JSON.stringify({type:'kick-player', targetId:String(target)}));
			},
			// 观战结束统一询问：胜负结束、玩家离开/被踢/被封号导致的强制结束都走这里。
			// 只允许同时存在一个结束提示；继续则留观，离开则退出观战回大厅。
			promptSpectateEnded(detail){
				if(!this.spectateMode || this.spectatePromptOpen){ return; }
				var that = this;
				this.spectatePromptOpen = true;
				var content = '该对局已经结束，是否继续观看？';
				if(detail){ content += '<br><span style="color:#999;font-size:13px">' + this.escapeHtml(detail) + '</span>'; }
				try{
					this.spectatePromptDialog = $.confirm({
						title:'对局已结束',
						content:content + '<br><span style="color:#999;font-size:13px">选择「离开」将返回大厅。</span>',
						confirmButton:'继续观看',
						cancelButton:'离开',
						confirm:function(){
							that.spectatePromptOpen = false;
							that.spectatePromptDialog = null;
							that.addSystemMessage('系统消息：你选择继续观看已结束的对局');
						},
						cancel:function(){
							that.spectatePromptOpen = false;
							that.spectatePromptDialog = null;
							that.quitSpectate(true);
						}
					});
				}catch(e){
					// 极端情况下弹窗库不可用：默认留在观战视图，不强制踢出
					this.spectatePromptOpen = false;
					this.spectatePromptDialog = null;
				}
			},
			// 进入观战：只读展示目标房间的棋盘与局面
			enterSpectate(data, fromRestore){
				let content = data || {};
				var that = this;
				this.roomReady = false;
				this.tableReady = false;
				this.reviewReady = false;
				this.lobbyReady = false;
				this.gameReady = false;
				this.isOver = false;
				this.isMyTurn = false;
				this.spectateMode = true;
				this.spectatePromptOpen = false;
				this.spectatePromptDialog = null;
				this.spectateRoom = content.roomView || {};
				//进入的就是已结束对局：棋盘按终局只读展示
				this.isOver = (this.spectateRoom.state === 'over');
				this.messages = [];
				this.historySteps = [];
				this.drawSpectateBoard(content.steps);
this.addSystemMessage('你正在观战 ' + (this.spectateRoom.playerName1 || '') + ' 与 ' + (this.spectateRoom.playerName2 || '') + ' 的对局（只读）');
				this.saveViewState({view:'spectate'});
				this.toastMsg('进入观战');
				//仅在“主动进入”已结束对局时询问；刷新恢复观战不重复弹窗
				if(!fromRestore && this.spectateRoom.state === 'over'){
					this.$nextTick(function(){ that.promptSpectateEnded(); });
				}
			},
// ===== 刷新页面后恢复原页面：视图状态存取与还原 =====
			//把当前视图记到 sessionStorage（同一标签页刷新有效，关掉标签页自然失效）
			saveViewState(state){
				try{
					if(state){
						window.sessionStorage.setItem('fcViewState', JSON.stringify(state));
					}else{
						window.sessionStorage.removeItem('fcViewState');
					}
				}catch(e){}
			},
			//统一发送入口：连接未就绪时提示并返回 false。
			//直接在 CONNECTING 状态 send 会抛 InvalidStateError，在 CLOSED 状态则静默丢消息，
			//两种情况用户都以为发出去了（输入框已清空、对方却没收到）。
			sendWs(obj){
				if(typeof socket === 'undefined' || !socket || socket.readyState !== 1){
					this.toastMsg('连接已断开，正在重连，请稍后再试');
					return false;
				}
				try{
					socket.send(JSON.stringify(obj));
					return true;
				}catch(e){
					this.toastMsg('发送失败，请重试');
					return false;
				}
			},
			//请求类操作（悔棋/和棋/重开）开始等待：30 秒内无响应自动解除，
			//否则对手一直不点，requestWaiting 会永久为真，连「离开房间」都被挡住
			startWaitingTimeout(){
				this.clearWaitingTimeout();
				let that = this;
				fcWaitingTimer = setTimeout(function(){
					fcWaitingTimer = null;
					if(that.requestWaiting){
						that.requestWaiting = false;
						that.addSystemMessage('对方长时间未响应，请求已自动取消');
					}
				}, 30000);
			},
			clearWaitingTimeout(){
				if(fcWaitingTimer){
					clearTimeout(fcWaitingTimer);
					fcWaitingTimer = null;
				}
			},
			readViewState(){
				try{
					var raw = window.sessionStorage.getItem('fcViewState');
					return raw ? JSON.parse(raw) : null;
				}catch(e){
					return null;
				}
			},
			//鉴权成功后调用：历史/复盘在此恢复；房间/观战等待服务端 room-restore 下发
			restoreView(){
				var state = this.readViewState();
				if(!state || !state.view){
					this.restoring = false;
					return;
				}
				this.restoring = true;
				if(state.view === 'history'){
					this.getHistoryGame(state.pageNum || 1);
					return;
				}
				if(state.view === 'review'){
					this.pendingReview = state;
					this.getHistoryGame(state.pageNum || 1);
					return;
				}
				if(state.view === 'adminView' && state.roomId){
					//管理员刷新后仍回到原来的房间查看页（房间聊天内容随之下发）
					var adminSelf = this;
					this.adminRoomId = state.roomId;
					if(socket && socket.readyState === 1){
						socket.send(JSON.stringify({type:'admin-enter-room', roomId: state.roomId}));
					}
					setTimeout(function(){
						if(adminSelf.restoring && !adminSelf.adminViewOpen){
							adminSelf.restoring = false;
							adminSelf.lobbyReady = true;
							adminSelf.publicChatFloating = false;
							adminSelf.saveViewState(null);
						}
					}, 2000);
					return;
				}
				//room / spectate：等待 room-restore；若房间已结束则回到大厅并清掉记录
				var that = this;
				this.restorePending = state.view;
				setTimeout(function(){
					if(that.restorePending === state.view){
						that.restorePending = null;
						that.restoring = false;
						that.saveViewState(null);
						that.lobbyReady = true;
						that.publicChatFloating = false;
						that.toastMsg('原房间已结束，已回到大厅');
					}
				}, 2000);
			},
			//服务端下发 room-restore：恢复玩家房间或观战页面
			restoreRoom(data){
				this.restoring = false;
				//重连后收到房间快照：说明房间还在，取消“掉线过久回大厅”的兜底
				this.roomRestoredFlag = true;
				this.publicChatFloating = true;
				var view = data.roomView || {};
				this.syncBoardVersion(view);
				if(data.role === 'spectator'){
					this.saveViewState({view:'spectate'});
					this.enterSpectate({roomView:view, steps:data.steps, color1:data.color1, color2:data.color2}, true);
					return;
				}
				//玩家：恢复房间视图与棋盘局面
				this.myRoom = view;
				this.roomReady = true;
				this.lobbyReady = false;
				this.tableReady = false;
				this.reviewReady = false;
				this.spectateMode = false;
				this.requestWaiting = false;
				this.messages = [];
				this.historySteps = [];
				this.initChessArray();
				this.clearBoard();
				this.addSystemMessage("网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。");
				var meIsPlayer1 = String(view.playerId1) === String(this.user.id);
				this.myChessColor = meIsPlayer1 ? data.color1 : data.color2;
				this.gameReady = meIsPlayer1 ? !!data.readyId1 : !!data.readyId2;
				this.oppoReady = meIsPlayer1 ? !!data.readyId2 : !!data.readyId1;
				this.gameStarted = !!view.started && view.state !== 'over';
				this.oppoId = meIsPlayer1 ? view.playerId2 : view.playerId1;
				this.drawSpectateBoard(data.steps);
				var steps = data.steps || [];
				//把已落子位置写回本地棋盘标记，避免恢复后重复落子
				steps.forEach(function(s){ this.chess_Board[s.x][s.y] = 1; }, this);
				//按最后一手的实际棋手推算下一手：不能简单按步数奇偶假设 playerId1 永远先手，
				//因为每局黑白由服务端随机分配，playerId1 也可能是白棋。
				var expected;
				if(steps.length > 0){
					var last = steps[steps.length - 1];
					expected = String(last.player) === String(view.playerId1) ? view.playerId2 : view.playerId1;
				}else{
					expected = (String(data.color1) === 'black') ? view.playerId1 : view.playerId2;
				}
				this.isMyTurn = String(expected) === String(this.user.id);
				this.isOver = (view.state === 'over');
				this.saveViewState({view:'room'});
			},
			// 观战棋盘：清空后按服务端下发的步数重绘
			drawSpectateBoard(steps){
				this.initChessArray();
				this.clearBoard();
				if(steps && steps.length){
					for(let k = 0; k < steps.length; k ++){
						let s = steps[k];
						this.spectateDrawStone(s.x, s.y, s.color);
					}
				}
			},
			// 观战棋盘落子（只画棋子，不参与胜负判定）
			spectateDrawStone(x, y, color){
				this.context.beginPath();
				this.context.arc(x*30+15, y*30+15, 13, 0, 2*Math.PI);
				this.context.closePath();
				this.context.fillStyle = color;
				this.context.fill();
			},
			// 观战：新一局开始，重置棋盘
			spectateReset(data){
				if(!this.spectateMode){ return; }
				if(data && data.roomView){ this.spectateRoom = data.roomView; }
				this.drawSpectateBoard((data || {}).steps);
				this.isOver = false;
				this.addSystemMessage('新的一局开始');
			},
			// 观战：同步落子
			spectatePlay(data){
				if(!this.spectateMode){ return; }
				this.spectateDrawStone(data.x, data.y, data.color);
			},
// 观战：对局结束
			spectateOver(data){
				if(!this.spectateMode){ return; }
				this.isOver = true;
				//按服务端下发的连五坐标高亮（与对局双方一致），并告知观战者获胜方
				this.drawWinLine(data.line);
				if(data.winId === 0){
					this.toastMsg('本局和棋', 3000, true);
					this.addSystemMessage('本局结束：和棋');
				}else{
					var winner = this.spectateNickById(data.winId);
					this.toastMsg('本局结束：' + winner + ' 获胜', 3000, true);
					this.addSystemMessage('本局结束：' + winner + ' 获胜');
				}
				//整局结束后询问是否继续观看：不再自动退出，由观战者选择继续或离开
				this.promptSpectateEnded();
			},
			//观战中按用户id取昵称（用于展示获胜方）
			spectateNickById(id){
				var room = this.adminViewOpen ? (this.adminRoomView || {}) : (this.spectateRoom || {});
				if(String(room.playerId1) === String(id)){ return room.playerName1 || '玩家1'; }
				if(String(room.playerId2) === String(id)){ return room.playerName2 || '玩家2'; }
				return '一方';
			},
// 退出观战，回到大厅（silent 为 true 时不覆盖已有的结束提示）
			quitSpectate(silent){
				if(this.spectatePromptDialog){
					try{ this.spectatePromptDialog.close(); }catch(e){}
					this.spectatePromptDialog = null;
				}
				this.spectatePromptOpen = false;
				if(socket && socket.readyState === 1){
					socket.send(JSON.stringify({type:'quit-room'}));
				}
				this.spectateMode = false;
				this.spectateRoom = {};
				this.lobbyReady = true;
				this.isOver = true;
				this.initChessArray();
				this.clearBoard();
				this.saveViewState(null);
				if(!silent){
					this.toastMsg('已退出观战');
				}
			},
			// 收到邀请：由玩家选择接受或拒绝
			processInvite(data){
				let that = this;
				//重连补推：本地弹窗还在时不再重建，否则会关闭再打开、把倒计时重置一遍
				if(data && data.resend && that.inviteDialog){ return; }
				//优先用服务端下发的邀请人昵称，缺失时从本页在线列表按 fromId 兜底解析，确保弹窗能显示邀请人
				let inviter = that.onlineUsers.filter(function(u){ return String(u.id) === String(data.fromId); })[0];
let inviterName = data.fromName || (inviter && inviter.nick) || '对方';
				//同一时间只保留一个邀请弹窗，避免连续邀请导致弹窗叠加、倒计时互相干扰
				try{ if(that.inviteDialog){ that.inviteDialog.close(); } }catch(e){}
				that.inviteDialog = null;
				that.stopInviteCountdown();
				that.inviteCountdown = 30;
				that.inviteDialog = $.confirm({
					title:'收到邀请',
					content:'「' + that.escapeHtml(inviterName) + '」邀请你加入房间，是否接受？<br><span style="color:#999;font-size:13px">未处理将在 <b class="invite-countdown">30</b> 秒后自动拒绝</span><br><a href="javascript:;" class="invite-block-link">拒绝并不再接受该用户邀请</a>',
					confirmButton:'接受',
					cancelButton:'拒绝',
					confirm:function(){
						that.stopInviteCountdown();
						that.inviteDialog = null;
						socket.send(JSON.stringify({type:'invite-accept', roomId:data.roomId, fromId:data.fromId}));
					},
					cancel:function(){
						that.stopInviteCountdown();
						that.inviteDialog = null;
						socket.send(JSON.stringify({type:'invite-reject', roomId:data.roomId, fromId:data.fromId}));
					}
				});
				// 第三个选项：拒绝并不再接受该用户邀请（以次要链接放在提示下方，避免与两个主按钮挤在一行）
				$(document).off('click.inviteBlockLink').on('click.inviteBlockLink', '.invite-block-link', function(){
					that.stopInviteCountdown();
					try{ if(that.inviteDialog){ that.inviteDialog.close(); } }catch(e){}
					that.inviteDialog = null;
					socket.send(JSON.stringify({type:'invite-reject', roomId:data.roomId, fromId:data.fromId, block:true}));
					that.toastMsg('已拒绝并当天不再接受该用户的邀请');
				});
				that.inviteCountdownTimer = setInterval(function(){
					that.inviteCountdown = that.inviteCountdown - 1;
					let el = document.querySelector('.invite-countdown');
					if(el){ el.textContent = that.inviteCountdown > 0 ? that.inviteCountdown : 0; }
					if(that.inviteCountdown <= 0){
						that.stopInviteCountdown();
						try{ if(that.inviteDialog){ that.inviteDialog.close(); } }catch(e){}
						that.inviteDialog = null;
						socket.send(JSON.stringify({type:'invite-reject', roomId:data.roomId, fromId:data.fromId, timeout:true}));
						that.toastMsg('邀请已超时自动拒绝');
					}
				}, 1000);
			},
			stopInviteCountdown(){
				if(this.inviteCountdownTimer){
					clearInterval(this.inviteCountdownTimer);
					this.inviteCountdownTimer = null;
				}
			},
			// 被房主踢出：回到大厅并提示
			processKicked(data){
				//同 quitRoom：被踢后回大厅时先用本地投影把自己从该房间的座位摘掉，
				//否则大厅会闪一下「自己还在房里」的卡片
				this.projectSelfLeftRoom(this.myRoom && this.myRoom.roomNo);
				this.spectateMode = false;
				this.spectateRoom = {};
				this.myRoom = {};
				this.curBoardVersion = null;
				this.oppoId = '-1';
				this.oppoReady = false;
				this.gameReady = false;
this.gameStarted = false;
				this.roomReady = false;
				this.tableReady = false;
				this.lobbyReady = true;
				this.publicChatFloating = false;
				this.isMyTurn = false;
				this.isOver = true;
this.historySteps = [];
				this.messages = [];
				this.initChessArray();
				this.clearBoard();
				//被踢后原房间已不可恢复，清除刷新恢复记录，避免下次刷新尝试回到该房间
				this.saveViewState(null);
				this.toastMsg((data && data.content) || '你已被房主移出房间');
			},
			// 房间昵称旁的准备状态：自己在房间用本地状态，管理员巡查用被查看房间的服务端快照
			playerReady(slot){
				var v = this.roomViewSrc || {};
				//观战者只读：直接使用服务端下发的房间快照，不与本地准备状态混用
				if(this.adminViewOpen || this.spectateMode){
					return slot === 1 ? !!v.readyId1 : !!v.readyId2;
				}
				var meIsPlayer1 = String(v.playerId1) === String(this.user.id);
				if(slot === 1){
					return meIsPlayer1 ? !!this.gameReady : !!this.oppoReady;
				}
				return meIsPlayer1 ? !!this.oppoReady : !!this.gameReady;
			},
			// 在线列表：是否可邀请（需在房间中，且对方空闲或房间中，游戏中的用户不可邀请）
// 管理员在房间内可管理的目标（管理员本人与空缺位置不显示按钮）
			canManageId(id){
				return this.isAdmin && !!id && String(id) !== String(this.user.id) && String(id) !== '-1';
			},
			// 指定用户当前是否被禁言（用于切换按钮文案）
			mutedStateOf(id){
				if(!id){ return false; }
				for(let i = 0; i < this.onlineUsers.length; i++){
					if(String(this.onlineUsers[i].id) === String(id)){
						return !!this.onlineUsers[i].muted;
					}
				}
				return false;
			},
			// 管理员：禁言 / 解除禁言（房间内昵称旁按钮与在线列表按钮共用）
			adminToggleMuteById(id){
				let that = this;
				if(!socket || socket.readyState !== 1 || !id){ return; }
				//解除禁言直接执行；禁言与封号一致，先二次确认
				if(this.mutedStateOf(id)){
					socket.send(JSON.stringify({type:'admin-unmute', targetId:String(id)}));
					return;
				}
				var nick = '';
				for(let i = 0; i < this.onlineUsers.length; i++){
					if(String(this.onlineUsers[i].id) === String(id)){ nick = this.onlineUsers[i].nick; break; }
				}
				confirmDialog('禁言确认',
					'确认禁言「' + that.escapeHtml(nick || '') + '」？<br><span style="color:#999;font-size:13px">该用户将暂时无法在公共聊天与房间内发言，可随时解除。</span>',
					'确认禁言', '取消',
					function(){
						if(socket && socket.readyState === 1){
							socket.send(JSON.stringify({type:'admin-mute', targetId:String(id)}));
						}
					});
			},
			// 管理员：封号（二次确认后执行，被封用户会立即退出登录）
			adminBanById(id, nick){
				let that = this;
				if(!socket || socket.readyState !== 1 || !id){ return; }
				confirmDialog('封号确认',
					'确认封号「' + that.escapeHtml(nick || '') + '」？<br><span style="color:#999;font-size:13px">该用户会立即退出登录，且其 IP 无法再注册新账号。</span>',
					'确认封号', '取消',
					function(){
						if(socket && socket.readyState === 1){
							socket.send(JSON.stringify({type:'admin-ban', targetId: String(id)}));
						}
					});
			},
			// 管理员：隐身进入某个房间（含私人房间）内部查看对局与房间消息，房内用户无感知
			adminViewRoom(room){
				if(!socket || socket.readyState !== 1 || !room || !room.id){ return; }
				this.adminRoomId = room.id;
				this.adminRoomView = {};
				this.adminChatRecords = [];
				this.adminSteps = [];
				//记录查看状态：刷新后仍停留在该房间查看页
				this.saveViewState({view:'adminView', roomId: room.id});
				socket.send(JSON.stringify({type:'admin-enter-room', roomId: room.id}));
			},
			// 管理员：退出房间查看，回到大厅
			adminCloseView(){
				if(socket && socket.readyState === 1 && this.adminRoomId){
					socket.send(JSON.stringify({type:'admin-leave-room', roomId: this.adminRoomId}));
				}
				this.adminViewOpen = false;
				this.adminRoomId = '';
				this.adminRoomView = {};
				this.adminChatRecords = [];
				this.adminSteps = [];
				this.initChessArray();
				this.clearBoard();
//退出查看后不再记录该查看状态，刷新即回到大厅
				this.saveViewState(null);
				this.lobbyReady = true;
			},
			// 管理员巡查期间：房间成员/状态变化时同步刷新查看视图，避免停留在旧的“等待加入···”
			applyAdminRoomUpdate(data){
				if(!this.adminViewOpen || !data){ return; }
				if(data.roomId && String(data.roomId) !== String(this.adminRoomId)){ return; }
if(data.roomView){
					this.adminRoomView = data.roomView;
					//重开后状态回到 playing：同步解除终局标记，避免仍按上一局的结束态渲染
					this.isOver = data.roomView.state === 'over';
				}
				if(data.steps){
					this.adminSteps = (data.steps || []).slice();
					this.drawSpectateBoard(this.adminSteps);
				}
				this.$nextTick(this.updateMutedMarquee);
			},
			// 管理员巡查的房间被解散：本地退出查看并回到大厅
			adminRoomClosed(data){
				if(!this.adminViewOpen){ return; }
				//被巡查的房间已在服务端解散：回大厅前先把卡片从本地列表摘掉
				this.projectRoomGone(this.adminRoomView && this.adminRoomView.roomNo);
				this.adminViewOpen = false;
				this.adminRoomId = '';
				this.adminRoomView = {};
				this.adminChatRecords = [];
				this.adminSteps = [];
				this.publicChatFloating = false;
				this.initChessArray();
				this.clearBoard();
				this.saveViewState(null);
				this.lobbyReady = true;
				this.toastMsg((data && data.content) || '房间已被解散');
			},
			// 管理员：解散正在查看的房间
			adminDismissCurrentRoom(){
				let that = this;
				if(!this.adminRoomId){ return; }
				confirmDialog('解散房间确认',
					'确认解散该房间？<br><span style="color:#999;font-size:13px">房间内所有用户都会收到提示并回到大厅。</span>',
					'确认解散', '取消',
					function(){
						if(socket && socket.readyState === 1){
							socket.send(JSON.stringify({type:'admin-dismiss-room', roomId: that.adminRoomId}));
						}
						//解散后退出查看状态并回到大厅，避免停留在已解散的房间视图
						//房间已在服务端移除：先在本地列表摘掉这张卡片，等权威列表覆盖，
						//否则回大厅会先闪一下刚解散的房间
						that.projectRoomGone(that.adminRoomView && that.adminRoomView.roomNo);
						that.adminViewOpen = false;
						that.adminRoomId = '';
						that.adminRoomView = {};
						that.adminChatRecords = [];
						that.adminSteps = [];
						that.initChessArray();
						that.clearBoard();
						that.saveViewState(null);
						that.lobbyReady = true;
					});
			},
			// 管理员：从大厅房间卡片直接解散房间
			adminDismissRoom(room){
				let that = this;
				if(!room || !room.id){ return; }
				confirmDialog('解散房间确认',
					'确认解散该房间？<br><span style="color:#999;font-size:13px">房间内所有用户都会收到提示并回到大厅。</span>',
					'确认解散', '取消',
					function(){
						if(socket && socket.readyState === 1){
							socket.send(JSON.stringify({type:'admin-dismiss-room', roomId: room.id}));
						}
					});
			},
			// 收到管理员隐身查看的房间视图：切换到“房间内部”视图，展示对局棋盘、房间人员与房间聊天记录
			enterAdminRoomView(data){
				this.lobbyReady = false;
				this.tableReady = false;
				this.reviewReady = false;
				this.roomReady = false;
				this.spectateMode = false;
				this.isOver = false;
				this.adminRoomId = data.roomId || this.adminRoomId;
				this.adminRoomView = data.roomView || {};
				//房间消息窗口展示全部信息：守法提示 + 历史聊天 + 房间系统事件（加入/离开/准备/开局/终局）
				var chats = (data.chatRecords || []).map(function(c){
					return {time:c.time || '', nick:c.nick || '未知', content:c.content || ''};
				});
				var events = (data.events || []).map(function(e){
					return {time:e.time || '', content:e.content || '', system:true};
				});
				var merged = chats.concat(events).sort(function(a,b){
					return String(a.time || '').localeCompare(String(b.time || ''));
				});
				this.adminChatRecords = [{
					system:true,
					time:'',
					content:'系统消息：网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。'
				}].concat(merged);
				this.adminSteps = (data.steps || []).slice();
				this.adminViewOpen = true;
				this.restoring = false;
				//记录查看状态：刷新后仍停留在该房间查看页
				this.saveViewState({view:'adminView', roomId:this.adminRoomId});
				//按服务端下发的步数绘制当前对局
				this.drawSpectateBoard(this.adminSteps);
				this.scrollToBottom();
			},
			// 房间内实时新消息：查看房间时追加到管理员的聊天记录里
			adminAppendChat(data){
				this.adminChatRecords.push({
					time: data.time || '',
					nick: data.fromName || '未知',
					content: data.content || ''
				});
				this.scrollToBottom();
			},
			// 房间内实时系统事件：加入/离开/准备/开局/终局等，与房内成员看到的消息保持一致
			adminAppendEvent(data){
				if(!this.adminViewOpen || !data || !data.event){ return; }
				if(data.roomId && String(data.roomId) !== String(this.adminRoomId)){ return; }
				this.adminChatRecords.push({
					system: true,
					time: data.event.time || '',
					content: data.event.content || ''
				});
				this.scrollToBottom();
			},
			// 管理员巡查时把操作说明（悔棋/认输/求和/重开及其结果）也写入房间消息窗口
			adminAppendSystem(content){
				if(!content){ return; }
				this.adminChatRecords.push({
					system: true,
					time: this.getTimeNow(),
					content: '系统消息：' + content
				});
				this.scrollToBottom();
			},
			// 管理员：拉取已封号账号列表（可解封）
			loadBannedList(){
				if(!socket || socket.readyState !== 1){ return; }
				socket.send(JSON.stringify({type:'admin-banned-list'}));
			},
			// 管理员：解除封号
			adminUnban(b){
				let that = this;
				if(!b || !b.id){ return; }
				confirmDialog('解除封号确认',
					'确认解除「' + that.escapeHtml(b.nick || b.login || '') + '」的封号？<br><span style="color:#999;font-size:13px">解封后该账号与其 IP 可重新登录/注册。</span>',
					'确认解封', '取消',
					function(){
						if(socket && socket.readyState === 1){
							socket.send(JSON.stringify({type:'admin-unban', targetId: String(b.id)}));
						}
					});
			},
			// 管理员：封号名单里的「已封 IP」文案。
			// 封禁按账号的注册 IP 与最近登录 IP 判定，两者经常不同（在家注册、在公司登录），都要列出来。
			bannedIpText(b){
				if(!b){ return '未记录（仅封账号）'; }
				let parts = [];
				if(b.registerIp){ parts.push('注册 ' + b.registerIp); }
				if(b.lastIp){ parts.push('登录 ' + b.lastIp); }
				return parts.length ? parts.join(' / ') : '未记录（仅封账号）';
			},
			bannedIpTitle(b){
				if(!b || (!b.registerIp && !b.lastIp)){
					return '该账号没有注册/登录 IP 记录，本次仅封账号';
				}
				return '这些 IP 已被禁止注册新账号：注册 IP 为注册那一刻的出口，登录 IP 为最近一次登录的出口';
			},
			canInviteUser(u){
				if(!u || !this.roomReady){ return false; }
				if(String(u.id) === String(this.user.id)){ return false; }
				//已在本人房间内的成员不再显示邀请按钮（离开房间后恢复正常规则）
				if(this.myRoom){
					if(String(this.myRoom.playerId1) === String(u.id) || String(this.myRoom.playerId2) === String(u.id)){
						return false;
					}
				}
				return u.status==='idle' || u.status==='inRoom' || u.status==='spectating';
			},
			// 在线列表：是否可观战（对方正在游戏中）
canSpectateUser(u){
				if(!u){ return false; }
				if(String(u.id) === String(this.user.id)){ return false; }
				//同一房间内的成员无需“观战”入口：本身就在同一局里
				if(this.myRoom){
					if(String(this.myRoom.playerId1) === String(u.id) || String(this.myRoom.playerId2) === String(u.id)){
						return false;
					}
				}
				return u.status==='inGame';
			},
			// 邀请对方加入我的房间
			inviteUser(u){
				if(!u || !socket || socket.readyState !== 1){ return; }
				if(this.isInviteBlocked(u.id)){
					this.toastMsg('当天不可再邀请该用户');
					return;
				}
				if(this.invitePendingOf(u.id)){
					this.toastMsg('已邀请该用户，正在等待对方处理');
					return;
				}
				if(this.inviteCooldownLeft(u.id) > 0){
					this.toastMsg('该用户刚拒绝了邀请，请 ' + this.inviteCooldownLeft(u.id) + ' 秒后再试');
					return;
				}
				socket.send(JSON.stringify({type:'invite', targetId:String(u.id)}));
			},
			// 记录「被拒后的冷却倒计时」：按被邀请者 id 保存剩余秒数并逐秒递减
			startInviteCooldown(uid, seconds){
				if(!uid || !seconds || seconds <= 0){ return; }
				uid = String(uid);
				let m = Object.assign({}, this.inviteCooldowns);
				m[uid] = Math.ceil(seconds);
				this.inviteCooldowns = m;
				if(this.inviteCooldownTimer){ return; }
				let that = this;
				this.inviteCooldownTimer = setInterval(function(){
					let next = {};
					let any = false;
					for(let k in that.inviteCooldowns){
						let left = that.inviteCooldowns[k] - 1;
						if(left > 0){ next[k] = left; any = true; }
					}
					that.inviteCooldowns = next;
					if(!any){
						clearInterval(that.inviteCooldownTimer);
						that.inviteCooldownTimer = null;
					}
				}, 1000);
			},
			// 该用户当前剩余冷却秒数（0 表示可邀请）
			inviteCooldownLeft(uid){
				return this.inviteCooldowns[String(uid)] || 0;
			},
			// 标记「已发出邀请、等待对方接受/拒绝」
			setInvitePending(uid){
				if(!uid){ return; }
				uid = String(uid);
				let m = Object.assign({}, this.invitePending);
				m[uid] = true;
				this.invitePending = m;
			},
			// 解除「等待接受中」状态
			clearInvitePending(uid){
				if(!uid){ return; }
				uid = String(uid);
				if(!this.invitePending[uid]){ return; }
				let m = Object.assign({}, this.invitePending);
				delete m[uid];
				this.invitePending = m;
			},
			// 该用户是否处于「等待接受中」
			invitePendingOf(uid){
				return !!this.invitePending[String(uid)];
			},
			// 标记「当天不可再邀请该用户」
			setInviteBlocked(uid){
				if(!uid){ return; }
				uid = String(uid);
				let m = Object.assign({}, this.inviteBlocked);
				m[uid] = true;
				this.inviteBlocked = m;
			},
			// 该用户是否已被当天屏蔽
			isInviteBlocked(uid){
				return !!this.inviteBlocked[String(uid)];
			},
			// 同步当天屏蔽名单（登录后由服务端下发）
			applyInviteBlockedList(ids){
				let m = {};
				if(ids && ids.length){
					for(let i = 0; i < ids.length; i++){ m[String(ids[i])] = true; }
				}
				this.inviteBlocked = m;
			},
// 观战对方的对局
			spectateUser(u){
				if(!u || !socket || socket.readyState !== 1){ return; }
				socket.send(JSON.stringify({type:'spectate', targetId:String(u.id)}));
			},
			// 大厅房间卡片：直接观战「对局中」的房间（观战房间内的房主所在对局）
			spectateFromCard(room){
				if(!room || !socket || socket.readyState !== 1){ return; }
				let hostId = room.playerId1;
				if(!hostId || hostId === '-1'){ return; }
				socket.send(JSON.stringify({type:'spectate', targetId:String(hostId)}));
			},
			// 用服务端下发的房间视图同步当前房间/观战房间（成员、准备状态、观战人数等）
			//同步当前局序号：服务端在开局消息与房间视图里都会带上，认输时原样回传
			syncBoardVersion(data){
				if(!data || data.boardVersion === undefined || data.boardVersion === null){
					return;
				}
				let v = Number(data.boardVersion);
				if(!isNaN(v)){
					this.curBoardVersion = v;
				}
			},
			applyRoomView(view){
				if(!view){ return; }
				this.syncBoardVersion(view);
				if(this.spectateMode){
					if(this.spectateRoom && this.spectateRoom.id && view.id !== this.spectateRoom.id){ return; }
					this.spectateRoom = view;
					return;
				}
				if(!this.roomReady){ return; }
				if(this.myRoom && this.myRoom.id && view.id !== this.myRoom.id){ return; }
				this.myRoom = view;
			},
			// 在线列表状态文案
			statusText(status){
				if(status==='inGame')	return '游戏中';
				if(status==='inRoom')	return '房间中';
				if(status==='spectating')	return '观战中';
				return '空闲';
			},
			// 在线列表状态样式类
			statusClass(status){
				if(status==='inGame')	return 'status-ingame';
				if(status==='inRoom')	return 'status-inroom';
				if(status==='spectating')	return 'status-spectating';
				return 'status-idle';
			},
			// 清除对手最后一步的标注
			clearLastOppoMark() {
				if (this.lastOppoX !== -1 && this.lastOppoY !== -1) {
					// 清除标注区域，不破坏棋子
					this.context.clearRect(this.lastOppoX*30 + 10, this.lastOppoY*30 + 10, 10, 10);
					// 重新绘制棋子（防止擦除标注时损坏棋子）
					this.context.beginPath();
					this.context.arc(this.lastOppoX*30+15, this.lastOppoY*30+15, 13, 0, 2*Math.PI);
					this.context.closePath();
					const oppoColor = this.myChessColor === 'black' ? 'white' : 'black';
					this.context.fillStyle = oppoColor;
					this.context.fill();
				}
			},
			// 绘制对手最后一步的标注（青色小圆圈）
			drawLastOppoMark(x, y) {
				this.context.beginPath();
				this.context.arc(x*30+15, y*30+15, 5, 0, 2*Math.PI);
				this.context.closePath();
				this.context.fillStyle = '#00FFFF'; // 青色高亮标注，显眼不遮挡棋子
				this.context.fill();
			},
			// 辅助方法：获取复盘棋子颜色
			getReviewColor(x, y){
				let step = this.reviewSteps.find(s => s.x == x && s.y == y);
				return step ? step.color : 'black';
			},
			// 清除复盘棋盘的最后一步标注
			clearReviewMark() {
				if (this.lastReviewX === -1 || this.lastReviewY === -1) return;
				this.reviewContext.clearRect(this.lastReviewX*30 + 10, this.lastReviewY*30 + 10, 10, 10);
				// 重绘棋子，不破坏棋盘
				this.reviewContext.beginPath();
				this.reviewContext.arc(this.lastReviewX*30+15, this.lastReviewY*30+15, 13, 0, 2*Math.PI);
				this.reviewContext.closePath();
				this.reviewContext.fillStyle = this.getReviewColor(this.lastReviewX, this.lastReviewY);
				this.reviewContext.fill();
			},
			// 绘制复盘最后一步标注
			drawReviewMark(x, y) {
				this.reviewContext.beginPath();
				this.reviewContext.arc(x*30+15, y*30+15, 5, 0, 2*Math.PI);
				this.reviewContext.closePath();
				this.reviewContext.fillStyle = '#00FFFF';
				this.reviewContext.fill();
			},
			//按下自动或者暂停按键时
			pressAutoPause(){
				//如果时暂停状态则改为自动 反正亦然
				if(this.autoReview){
					this.autoReview = false;
				}else {
					this.autoReview = true;
					this.startAutoPlay();
				}
			},
			//自动复盘
			startAutoPlay(){ //默认延迟时间为1秒
				if(this.autoReview) this.reviewNext();
				if(this.reviewStepsHistory.length===this.reviewSteps.length){
					this.autoReview = false;
				}
				if(this.autoReview){
					this.sleep(1000).then(()=>{
						this.startAutoPlay();
					})
				}
			},
			//改变背景
			changeBg(url){
				this.background_image = url;
			},
			//求和
			peace(){
				if(this.isOver){
					this.toastMsg('对局已结束,请重新开始！');
					return;
				}
if(!this.couldPressOrNot())	return;
				let that = this;
				confirmDialog('请求和棋', '是否请求和棋？少于 3 回合的对局不会被保存。', '请求和棋', '取消', function(){
					let message={
						type:'peace',
						roomId:that.myRoom.id,
						from:that.user.id,
						to:that.oppoId
					}
					if(!that.sendWs(message)){ return; }
					that.requestWaiting = true; //等待响应中
					that.startWaitingTimeout();
					//构造系统消息
					that.addSystemMessage('你请求了和棋···');
				});
			},
			//重开
			restart(){
				if(!this.couldPressOrNot())	return;
				let that = this;
				confirmDialog('重新开局', '是否请求重新开局？未完成的对局不会被保存。', '重新开局', '取消', function(){
					let message = {
						type:'restart',
						roomId:that.myRoom.id,
						from:that.user.id,
						to:that.oppoId
					}
					if(!that.sendWs(message)){ return; }
					that.requestWaiting = true;
					that.startWaitingTimeout();
					that.addSystemMessage('你已发送重开请求！');
				});
			},
			//认输
			surrender(){
				if(this.isOver){
					this.toastMsg('对局已结束,请重新开始！');
					return;
				}
				if(!this.couldPressOrNot())	return;
				let that = this;
				confirmDialog('确认认输', '是否确认认输？少于 3 回合的对局不会被保存。', '认输', '取消', function(){
					let message={
						type:'surrender',
						roomId:that.myRoom.id,
						from:that.user.id,
						to:that.oppoId
					}
					//带上本局序号：服务端据此拒绝「上一局遗留、延迟到达」的认输，
					//避免它落到刚开的新局上直接判负
					if(that.curBoardVersion !== null && that.curBoardVersion !== undefined){
						message.boardVersion = that.curBoardVersion;
					}
					socket.send(JSON.stringify(message));
					//构造系统消息
					message = {
						type:'system',
						content:'系统消息：你认输了',
						time:that.getTimeNow(),
						position:'middle',
					}
					that.messages.push(message);
					that.scrollToBottom();
					that.isOver = true;
				});
			},
			//控制class
			resultClass(game){
				if(game.type==='normal'){
					if(game.win===this.user.id)	return "ui green button";
					else return "ui red button";
				}
				if(game.type==='peace')	return 'ui yellow button';
				if(game.type==='surrender'){
					if(game.win===this.user.id)	return 'ui green button';
					else return 'ui red button';
				}
			},
			//终局
			reviewFinal(){
				//取消自动
				this.autoReview = false;
				this.reviewIndex = this.reviewSteps.length;
				//历史栈按「越靠后越先出栈」维护，与上一步的 shift() 对应
				this.reviewStepsHistory = this.reviewSteps.slice().reverse();
				this.renderReview();
			},
			//开局
			reviewFirst(){
				//取消自动
				this.autoReview = false;
				this.reviewIndex = 0;
				this.reviewStepsHistory = [];
				this.renderReview();
			},
			//上一步
			reviewLast(){
				if(this.reviewStepsHistory.length===0){
					this.toastMsg('已经是开局了!');
					return;
				}
				this.reviewStepsHistory.shift();
				this.reviewIndex = Math.max(0, this.reviewIndex - 1);
				//整盘重绘：终局的五子连线必须一起擦掉，靠局部 clearRect 会在棋盘上留下红线残段
				this.renderReview();
			},
			//下一步
			reviewNext(){
				if(this.reviewIndex >= this.reviewSteps.length){
					this.toastMsg('已经是终局啦!');
					return;
				}
				let step = this.reviewSteps[this.reviewIndex];
				this.reviewIndex++;
				this.reviewStepsHistory.unshift(step);
				this.renderReview();
				if(this.reviewIndex >= this.reviewSteps.length){
					this.toastMsg('终局!');
				}
			},
			//按当前进度整盘重绘：网格 → 已落棋子 → 最后一步标记 / 终局连五高亮
			renderReview(){
				this.clearReviewBoard();
				let total = Math.min(this.reviewIndex || 0, this.reviewSteps.length);
				//只有回放到最后一手才判定胜负，避免中途就把结局剧透出来
				let line = (total > 0 && total === this.reviewSteps.length) ? this.reviewWinLine() : null;
				let lastStep = null;
				for(let i=0;i<total;i++){
					let step = this.reviewSteps[i];
					this.reviewContext.beginPath();
					this.reviewContext.arc(step.x*30+15, step.y*30+15, 13, 0, 2*Math.PI);
					this.reviewContext.closePath();
					this.reviewContext.fillStyle = step.color;
					this.reviewContext.fill();
					lastStep = step;
				}
				if(lastStep){
					this.lastReviewX = lastStep.x;
					this.lastReviewY = lastStep.y;
					if(line){
						//获胜终局：与房间内终局一致，只画五子连线（红线 + 红点），不再叠加青色末手标记
						this.drawReviewWinLine(line);
					}else{
						this.drawReviewMark(lastStep.x, lastStep.y);
					}
				}
			},
			//终局连五：把获胜的五个（或更长）棋子连成红线并点上红点，样式与房间内 drawWinLine 一致
			drawReviewWinLine(line){
				if(!line || line.length < 5){ return; }
				let ctx = this.reviewContext;
				let oldStyle = ctx.strokeStyle;
				let oldWidth = ctx.lineWidth;
				let oldFill = ctx.fillStyle;
				ctx.strokeStyle = '#e53935';
				ctx.lineWidth = 3;
				ctx.beginPath();
				ctx.moveTo(line[0].x*30+15, line[0].y*30+15);
				for(let n=1;n<line.length;n++){
					ctx.lineTo(line[n].x*30+15, line[n].y*30+15);
				}
				ctx.stroke();
				for(let m=0;m<line.length;m++){
					ctx.beginPath();
					ctx.arc(line[m].x*30+15, line[m].y*30+15, 5, 0, 2*Math.PI);
					ctx.closePath();
					ctx.fillStyle = 'red';
					ctx.fill();
				}
				//还原画笔，避免后续重画网格时把线也画成红色
				ctx.strokeStyle = oldStyle;
				ctx.lineWidth = oldWidth;
				ctx.fillStyle = oldFill;
			},
			//从最后一手出发找同色五连（横/竖/撇/捺），判定规则与后端 fiveInRowLine 保持一致
			reviewWinLine(){
				let steps = this.reviewSteps || [];
				if(steps.length < 5){ return null; }
				let last = steps[steps.length-1];
				if(!last){ return null; }
				let board = {};
				for(let i=0;i<steps.length;i++){
					board[steps[i].x+','+steps[i].y] = steps[i].color;
				}
				let dirs = [[1,0],[0,1],[1,1],[1,-1]];
				for(let d=0;d<dirs.length;d++){
					let dx = dirs[d][0], dy = dirs[d][1];
					let neg = [], pos = [];
					let cx = last.x - dx, cy = last.y - dy;
					while(board[cx+','+cy] === last.color){
						neg.unshift({x:cx,y:cy});
						cx -= dx; cy -= dy;
					}
					cx = last.x + dx; cy = last.y + dy;
					while(board[cx+','+cy] === last.color){
						pos.push({x:cx,y:cy});
						cx += dx; cy += dy;
					}
					let line = neg.concat([{x:last.x,y:last.y}], pos);
					if(line.length >= 5){ return line; }
				}
				return null;
			},
			//清理复盘棋盘
			clearReviewBoard(){
				this.reviewContext.clearRect(0,0,540,540);
				//网格必须用黑色细线画：画过终局红线后画笔是红的，不重置会把整盘网格画成红色
				this.reviewContext.strokeStyle = "black";
				this.reviewContext.lineWidth = 1;
				//重新绘制线条
				for(let i = 0; i < 18; i ++){
					//x轴
					this.reviewContext.beginPath();
					//设置起始点的坐标
					this.reviewContext.moveTo(15, 15+i*30)
					//设置结束点的坐标
					this.reviewContext.lineTo(525,15+i*30);
					//连接两个点
					this.reviewContext.stroke();
					//y轴
					//设置起始点的坐标
					this.reviewContext.moveTo(15+i*30, 15)
					//设置结束点的坐标
					this.reviewContext.lineTo(15+i*30, 525);
					//连接两个点
					this.reviewContext.stroke();
					this.reviewContext.closePath();
				}
				this.lastReviewX = -1;
				this.lastReviewY = -1;

			},
			//初始化复盘棋盘
			initReviewBoard(){
				this.reviewBoard=document.getElementById('reviewBoard');// getElementsByClassName('chessBoard')[0];
				this.reviewContext = this.reviewBoard.getContext("2d");
				this.reviewContext.strokeStyle = "black";
			},
			//复盘
			review(index,game){
				let that = this;
axios.post(that.url+'/getGameData',{
					id:game.data,
					token:that.user.token
}).then(function (response) {
					if(response.data.code===200){
						that.reviewSteps = (response.data.content && response.data.content.steps) || [];
						if(that.reviewSteps.length === 0){
							//空棋谱（历史异常数据）不能进入复盘，否则后面访问 reviewSteps[0] 会直接抛错
							serverNotice('提示', '该对局没有可复盘的有效棋谱');
							return;
						}
						that.reviewIndex = 0;
						that.reviewStepsHistory = [];
						that.clearReviewBoard();
						that.tableReady = false;
						that.reviewReady = true;
						that.autoReview = false;//默认不开启自动
						if(String(that.reviewSteps[0].player)===String(that.user.id)){
							if(that.reviewSteps[0].color==='white'){
								that.whiteName = '我方';
								that.blackName = '对手';
							}else{
								that.blackName = '我方';
								that.whiteName = '对手';
							}
						}else{
							if(that.reviewSteps[0].color==='black'){
								that.whiteName = '我方';
								that.blackName = '对手';
}else{
								that.blackName = '我方';
								that.whiteName = '对手';
							}
						}
						//记录复盘视图，刷新后可回到同一局的复盘
						that.saveViewState({view:'review', pageNum:that.pageNum, gameId:game.id});
						//进入复盘时用轻提示告知本局我方棋子颜色
						that.showReviewColors();
					}else{
						if(response.data.code===401){
							that.handleAuthExpired((response.data.content && response.data.content.reason) || 'expired');
							return;
						}
serverNotice('提示', response.data.message)
					}
				}).catch(function (e) {
					console.log(e);
				})
			},
//返回大厅
backToLobby(){
				//观战/管理员巡查下点「返回大厅」：先走各自的退出流程（含向服务端退房），
				//否则会出现大厅与棋盘同时显示、在线状态仍为「观战中」的叠加态
				if(this.spectateMode){
					this.quitSpectate(true);
				}
				if(this.adminViewOpen){
					this.adminCloseView();
				}
				this.tableReady = false;
				this.lobbyReady = true;
				this.reviewReady = false;
				this.publicChatFloating = false;
				this.saveViewState(null);
			},
			//复盘棋子颜色：进入复盘时用主题弹窗展示我方与对手各执何色
			showReviewColors(){
				//进入复盘时直接用文字说明本局我方执何色（轻提示，3 秒后自动关闭）
				let meIsBlack = (this.blackName === '我方');
				let myColor = meIsBlack ? '黑棋' : '白棋';
				this.toastMsg('本局我是' + myColor, 3000);
			},
			//退出复盘：停止自动播放并回到历史对局列表
			exitReview(){
				this.autoReview = false;
				this.reviewReady = false;
				this.reviewSteps = [];
				this.reviewStepsHistory = [];
				this.reviewIndex = 0;
				this.tableReady = true;
				this.clearReviewBoard();
				this.saveViewState({view:'history', pageNum:this.pageNum});
				this.toastMsg('已退出复盘');
			},
			showResult(game){
				if(game.type==='peace')	return '平局';
				if(game.type==='normal')
				{
					if(game.win===this.user.id)	return '获胜';
					else return '败北';
				}
				if(game.type==='surrender'){
					if(game.win===this.user.id)	return '对手认输';
					else return '认输';
				}
			},
			//获取历史局
			getHistoryGame(pageNum){
				let that = this;
				//axios.post(that.url+'/getRecords',{  //废弃非分页的方法
axios.post(that.url+'/getPage',{
					id:that.user.id,
					pageNum:pageNum,
					token:that.user.token
				}).then(function (response) {
					if(response.data.code===200){
						let content = response.data.content;
						that.historyGames = content.records; //本页的所有记录
						that.pageNum = content.current;     //当前页码
						that.totalNum = content.total;     //总条数
						that.hasLastPage = content.current !== 1; //如果时第一页 则 没有上一页
//每页条数以服务端返回的 size 为准（服务端每页 10 条），避免前端写死分页大小；
						//原先的判断在总条数为 0 时会错误地显示“下一页”
						let pageSize = content.size && content.size > 0 ? content.size : 10;
						that.hasNextPage = content.total > content.current * pageSize;
						//计算总共有多少页
						that.totalPage = Math.ceil(content.total/pageSize);

that.lobbyReady = false;
						that.roomReady = false;
						that.reviewReady = false;
						that.tableReady = true;
						that.restoring = false;
						//记录历史列表视图，刷新后可恢复；若刷新前在复盘，则恢复完成后重新进入该局复盘
						if(!that.pendingReview){
							that.saveViewState({view:'history', pageNum:that.pageNum});
						}
						if(that.pendingReview){
							var pr = that.pendingReview;
							that.pendingReview = null;
							var idx = -1;
							(that.historyGames || []).forEach(function(g,i){ if(String(g.id)===String(pr.gameId)){ idx = i; } });
							if(idx >= 0){ that.review(idx, that.historyGames[idx]); }
							else { that.saveViewState({view:'history', pageNum:that.pageNum}); }
						}
					}else{
if(response.data.code===401){
							that.handleAuthExpired((response.data.content && response.data.content.reason) || 'expired');
							return;
						}
						//历史列表拉取失败：结束恢复态并回到大厅，避免一直停在不显示的状态
						that.restoring = false;
						that.lobbyReady = true;
						serverNotice('提示', response.data.message)
					}
				}).catch(function (e) {
					console.log(e);
					that.restoring = false;
					that.lobbyReady = true;
				})
			},
			//处理悔棋结果：回退步数以服务端下发的为准（发起方收 regret-agree、同意方收 regret-applied）
			processRegretResult(data, selfAgreed){
				let back = parseInt(data && data.content, 10);
				if(isNaN(back)){
					//兼容未携带步数的旧报文：按原规则推断
					back = this.lastPlay ? 1 : 2;
				}
				if(back > this.historySteps.length){ back = this.historySteps.length; }
				let moved = back > 0;
				for(let i = 0; i < back; i++){ this.GoBackOneStep(); }
				//只有真的回退了才重算先后手：回退 0 步说明这条悔棋没有生效（棋局已重开），
				//这时再改 isMyTurn 会把刚开的新局轮次判错
				if(moved){
					//悔棋后按剩余棋谱推算该谁走：不能一律设为“该我走”，
					//否则轮次归对方时本地会允许落子、再被服务端拒绝。
					if(this.historySteps.length === 0){
						this.isMyTurn = (this.myChessColor === 'black');
					}else{
						this.isMyTurn = (this.historySteps[0].player === 'oppo');
					}
					this.lastPlay = false;
				}
				this.requestWaiting = false;
				this.pendingActionVersion = null;
				if(selfAgreed){
					this.toastMsg('已同意对方悔棋');
				}else{
					this.toastMsg('对手同意了悔棋');
					this.addSystemMessage('对方同意你悔棋');
				}
			},
			processRegretAgree(data){
				this.processRegretResult(data, false);
			},
			//处理悔棋请求
			processRegret(data){
				let that = this;
				//已有对手请求弹窗未处理：自动拒绝新的请求，避免两个弹窗叠加、
				//用户依次点「同意」时两条操作接连生效把棋局搅乱
				if(this.incomingRequest){
					//重连补推的同一条请求：本地弹窗还在，直接忽略，不能当成新请求自动拒绝
					if(data && data.resend){ return; }
					this.rejectExtraRequest('regret-reject','对手又发来一个悔棋请求，已自动拒绝');
					return;
				}
				//记录这条请求属于哪一局：同意时原样带回，服务端据此判断棋局是否已经重开
				this.pendingActionVersion = (data && data.boardVersion !== undefined) ? data.boardVersion : null;
				//弹窗期间挂起本地操作：避免双方同时发起悔棋/重开/认输，两个「同意」叠加执行
				this.incomingRequest = true;
				$.confirm({
					title: '对手请求悔棋',
					content: '是否同意···',
					confirmButton:'同意',
					cancelButton:'拒绝',
					confirm: function(){
						that.incomingRequest = false;
						let backNum = that.lastPlay?2:1; //返回步数
						let message = {
							type:'regret-agree',
							from:that.user.id,
							to:that.oppoId,
							roomId:that.myRoom.id,
							backNum:backNum
						}
						that.attachActionVersion(message);
						socket.send(JSON.stringify(message));
						//本地不先回退：等服务端按权威步数回发的 regret-applied 再统一回退。
						//否则双方同时同意时本地各退一次、服务端只退一次，棋盘就和服务端错位了
						that.addSystemMessage('你同意了对方的悔棋申请');
					},
					cancel: function(){
						that.incomingRequest = false;
						that.pendingActionVersion = null;
						let message = {
							type:'regret-reject',
							from:that.user.id,
							to:that.oppoId,
							roomId:that.myRoom.id
						}
						socket.send(JSON.stringify(message));
						that.addSystemMessage('你拒绝了对方的悔棋申请');
					}
				});
			},
			//把待处理请求的局序号附到「同意」报文上（旧版服务端不认这个字段，无副作用）
			attachActionVersion(message){
				if(this.pendingActionVersion !== null && this.pendingActionVersion !== undefined){
					message.boardVersion = this.pendingActionVersion;
				}
				this.pendingActionVersion = null;
				return message;
			},
			//对手连发多个请求时自动拒绝后到的那个，并给出提示
			rejectExtraRequest(type, tip){
				let message = {
					type:type,
					from:this.user.id,
					to:this.oppoId,
					roomId:this.myRoom ? this.myRoom.id : ''
				}
				try{ if(socket && socket.readyState === 1){ socket.send(JSON.stringify(message)); } }catch(e){}
				this.toastMsg(tip, 3000, true);
				this.addSystemMessage(tip);
			},
			//悔棋
			regretOne(){
				if(this.isOver){
					this.toastMsg('对局已结束,请重新开始！');
					return;
				}
				if(!this.couldPressOrNot())	return;
				if(this.historySteps.length===0||(this.historySteps.length===1&&!this.lastPlay)){
					this.toastMsg('现在不允许悔棋');
					return;
				}
				let message = {
					type:'regret',
					roomId:this.myRoom.id,
					from:this.user.id,
					to:this.oppoId
				}
				if(!this.sendWs(message)){ return; }
				//构造系统消息
				message = {
					type:'system',
					content:'系统消息：你已发起悔棋请求',
					time:this.getTimeNow(),
					position:'middle',
				}
				this.messages.push(message);
				this.scrollToBottom();
				this.toastMsg('等待对方响应···');
				this.requestWaiting = true;
				this.startWaitingTimeout();
			},
			//发送消息
sendText(){
				// 被管理员禁言时冻结发言（服务端同样会拒绝）
				if(this.mutedSelf){
					this.toastMsg('你已被管理员禁言，暂时无法发言');
					return;
				}
// 与公共聊天保持一致：纯空格 / 换行也算空消息，避免"看起来什么都没发、也没有任何提示"
			if(String(this.text || '').trim()===''){
				//alert('消息不能为空！');
				this.toastMsg('消息不能为空！')
				return;
			}
				//与服务端限制保持一致，避免超长消息被静默丢弃、用户不知道是否发送成功
				if(this.text.trim().length > 2000){
					this.toastMsg('消息不能超过 2000 个字符');
					return;
				}
				//房间号：自己在房间时用 myRoom，管理员隐身查看时用被查看的房间
				let roomId = this.adminViewOpen ? this.adminRoomId : (this.myRoom ? this.myRoom.id : null);
				if(roomId===undefined||roomId===null||roomId===''){
					return;
				}
				//构造消息
				let message={
					type:'chat-message',
					content:this.text,
					time:this.getTimeNow(),
					roomId:roomId,
					fromId:this.user.id,
					fromName:this.user.name
				}
				//连接未就绪时不发也不清空，避免用户以为已发出
				if(!this.sendWs(message)){ return; }
				this.text = '';
			},
			// 发送公共大厅聊天消息
sendPublicText() {
				// 被管理员禁言时冻结发言（服务端同样会拒绝）
				if (this.mutedSelf) {
					this.toastMsg('你已被管理员禁言，暂时无法发言');
					return;
				}
				// 空消息拦截
if (this.publicText.trim() === '') {
					this.toastMsg('公共聊天消息不能为空！');
					return;
				}
				if (this.publicText.trim().length > 2000) {
					this.toastMsg('消息不能超过 2000 个字符');
					return;
				}
				// 构造和后端约定的消息格式（type必须是public-chat-message）
				const message = {
					type: 'public-chat-message',
					content: this.publicText.trim()
				};
				// 发送消息：连接未就绪时不发也不清空
				if(!this.sendWs(message)){ return; }
				// 清空输入框
				this.publicText = '';
			},
// 公共聊天只保留最近 300 条，避免长时间运行内存无限增长
			trimPublicMessages() {
				if(this.publicMessages.length > 300){
					this.publicMessages.splice(0, this.publicMessages.length - 300);
				}
				//系统提示不参与上限裁剪，始终保留在公共聊天顶部
				this.ensurePublicSystemTip();
			},
			// 公共聊天系统提示：连接、刷新回填聊天记录后都要保证它仍在，避免被历史记录替换掉
			ensurePublicSystemTip() {
				var tipContent = '网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。';
				var exists = this.publicMessages.some(function(m){
					return m && m.content && String(m.content).indexOf('网络空间并非法外之地') >= 0;
				});
				if(!exists){
					this.publicMessages.unshift({
						senderNick: '系统提示',
						content: tipContent,
						position: 'message-left'
					});
				}
			},
			// 房间聊天系统提示：刷新回填 room-chat-history 后补回，避免刚进房间的守法提示被覆盖
			ensureRoomSystemTip() {
				var tipContent = '系统消息：网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。';
				var exists = this.messages.some(function(m){
					return m && m.content && String(m.content).indexOf('网络空间并非法外之地') >= 0;
				});
				if(!exists){
					this.messages.unshift({
						type: 'system',
						content: tipContent,
						time: this.getTimeNow(),
						position: 'middle'
					});
				}
			},
			// 禁言提示：放得下居中静态显示，放不下则用两段相同文字首尾衔接做标准循环滚动。
			// 禁言状态变化、窗口缩放、巡查视图刷新时都会重新计算；双 rAF 保证刚切换出来的
			// DOM 已完成布局，禁言后无需刷新页面即可立即开始滚动。
			updateMutedMarquee() {
				var apply = function(){
					var nodes = document.querySelectorAll('.muted-scroll-text');
					for(var i = 0; i < nodes.length; i++){
						var text = nodes[i];
						var parent = text.parentElement;
						var seg = text.querySelector('.muted-seg');
						if(!parent || !seg){ continue; }
						//先按静态状态量一次文字宽度，避免滚动时的 padding 影响“是否溢出”的判断
						text.classList.remove('scrolling');
						parent.classList.remove('muted-scrolling');
						var textWidth = seg.getBoundingClientRect().width;
						var overflow = textWidth > (parent.clientWidth - 2);
						if(!overflow){ continue; }
						text.classList.add('scrolling');
						parent.classList.add('muted-scrolling');
						//第二段带上间隔后的宽度就是循环位移距离（整段宽度）；按约 40px/s 匀速滚动
						var cycleWidth = seg.getBoundingClientRect().width;
						var duration = Math.max(6, cycleWidth / 40);
						text.style.animationDuration = duration.toFixed(1) + 's';
					}
				};
				var run = function(){
					apply();
					window.requestAnimationFrame(function(){
						apply();
						window.requestAnimationFrame(apply);
					});
				};
				this.$nextTick(run);
			},
			// 公共聊天自动滚动到底部
			scrollPublicToBottom() {
				this.$nextTick(() => {
					const container = document.getElementById('public-chat-body');
					if (container) container.scrollTop = container.scrollHeight;
				});
			},
			// 切换公共聊天窗口显示/隐藏
togglePublicChat() {
				this.publicChatVisible = !this.publicChatVisible;
				try{
					window.sessionStorage.setItem('fcPublicChat', this.publicChatVisible ? '1' : '0');
				}catch(e){}
			},
			//滚动到聊天位置末尾
scrollToBottom() {
				this.$nextTick(()=>{
					let container = this.$el.querySelector('#chat-body');
					if(container){ container.scrollTop = container.scrollHeight; }
				})
			},
			//加入房间
joinRoom(room){
				//被该房间房主踢出过：不再弹密码框，直接提示无法加入（公开/私人房间一致）
				if(room.kicked){
					this.toastMsg('你已被房主移出该房间，无法再次加入！');
					return;
				}
				if(room.playerId2!=='-1')	return;//房间满了
				//不能加入自己创建的房间，否则会和自己开局
				if(room.playerId1==this.user.id){
					this.toastMsg('不能加入自己创建的房间');
					return;
				}
				if(!this.ensureConnection(() => this.joinRoom(room))){ return; }
				var that = this;
				//私人房间：先输入房主设置的密码，再发起加入；公开房间直接加入
				if(room.open === false){
askRoomPassword('加入私人房间',
						'该房间为私人房间，请输入房主设置的密码（4-20 位）。',
						function(password){ that.sendJoinRoom(room, password); },
						{keepOpenOnConfirm:true, roomId:room.id});
					return;
				}
				this.sendJoinRoom(room, null);
			},
			//发送加入房间请求；密码仅在私人房间时携带
			sendJoinRoom(room, password){
				let message={
					type:'join-room',
					roomId:room.id,
					user:this.user
				};
				if(password){ message.password = password; }
				socket.send(JSON.stringify(message));
			},
getReady(){
				if(!this.ensureConnection(() => this.getReady())){ return; }
				//对局已开始后按钮不应出现；即使被脚本触发也拒绝撤销准备
				if(this.gameStarted){
					this.toastMsg('对局已开始，不能取消准备');
					return;
				}
				//已准备时再次点击就是取消准备
				if(this.gameReady){
					this.cancelReady();
					return;
				}
				//房内还没有对手时不能进入“已准备/对局中”状态，否则会误显示悔棋、认输等对局按钮
				if(!this.myRoom || !this.myRoom.playerId2 || this.myRoom.playerId2 === '-1'){
					this.toastMsg('等待对手加入房间后再准备');
					return;
				}
				this.gameReady = true;
				let message = {
					type:'system',
					content:'系统消息：你已经准备',
					time:this.getTimeNow(),
					position:'middle',
				}
				this.messages.push(message);
				this.scrollToBottom();
				if(this.myRoom.playerId1==this.user.id){
					if(this.myRoom.playerId2!='-1'){
						message = {
							type:'info-ready',
							content: this.gameReady,
							to:this.myRoom.playerId2,
							from:this.user.id
						}
						socket.send(JSON.stringify(message))
					}
				}else {
message = {
						type:'info-ready',
						content: this.gameReady,
						to:this.myRoom.playerId1,
						from:this.user.id
					}
					socket.send(JSON.stringify(message))
				}
				//若对手比我先准备，我点击准备时不会再次收到对方的 info-ready，
				//必须在这里主动发起开局，否则双方都已准备却永远不开局。
				if(this.oppoReady){
					socket.send(JSON.stringify({type:'start-game', roomId:this.myRoom.id}));
				}
			},
			//取消准备：仅在对局真正开始前可用，撤销后双方状态回到「房间中」
			cancelReady(){
				if(this.gameStarted || !this.gameReady){
					return;
				}
				this.gameReady = false;
				this.requestWaiting = false;
				this.addSystemMessage('系统消息：你已取消准备');
				let target = null;
				if(this.myRoom && this.myRoom.playerId1 && this.myRoom.playerId2 && this.myRoom.playerId2 !== '-1'){
					target = (String(this.myRoom.playerId1) === String(this.user.id))
						? this.myRoom.playerId2 : this.myRoom.playerId1;
				}
				if(target && socket && socket.readyState === 1){
					socket.send(JSON.stringify({
						type:'info-ready',
						content:false,
						to:target,
						from:this.user.id
					}));
				}
			},
			showClass(position){
				if(position==='middle')	return "message-middle";
				else if(position==='left') return "message-left";
				else return "message-right"
			},
			//创建对战房间
createRoom(){
				//房间号由服务端自动分配，前端无需提交任何名称/编号
				//断线时先自动重连，连上后把这次建房补发出去
				if(!this.ensureConnection(() => this.createRoom())){ return; }
				let message={
					type:'create-room',
					user:this.user
				}
				socket.send(JSON.stringify(message));
			},
			//初始化我的房间
			initMyRoom(room){
				this.myRoom = room;
				//新房间有自己的局序号（尚未开局为 0），不能沿用上个房间的
				this.curBoardVersion = null;
				this.syncBoardVersion(room);
				this.roomReady = true;
				this.lobbyReady = false;
				this.gameReady = false;
				this.oppoReady = false;
				this.gameStarted = false;
				this.messages = [];//清空消息
				//初始化系统消息
				let message = {
					type:'system',
					content:'系统消息：你已创建房间',
					time:this.getTimeNow(),
					position:'middle',
				}
				this.messages.push(message);
				this.scrollToBottom();
				// ========== 房间聊天自动提示 ==========
this.addSystemMessage("网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。");
				this.saveViewState({view:'room'});
			},
			getOnlineRoom(){
				//获取在线房间
				let message={
					type:'get-online-room'
				}
				socket.send(JSON.stringify(message));
			},
			//退出房间
			quitRoom(){
				//离开房间是安全操作，不应被"等待对手响应"挡住：
				//对手长时间不响应时否则只能刷新页面才能退出
				if(!this.roomReady){
					//alert('你还没有房间！');
					this.toastMsg('你还没有房间！');
					return;
				}
let that = this;
				confirmDialog('离开房间', '是否离开当前房间？未完成的对局不会被保存。', '离开房间', '取消', function(){
					let message={
						type:'quit-room',
						userId:that.user.id
					}
					socket.send(JSON.stringify(message));
					//服务端的权威房间列表要一个往返才回来；这段间隙里本地列表仍把自己画在座位上，
					//大厅会先闪一下「自己还在房间里」（单人房间则是整张卡片闪一下才消失）。
					//先在本地把这次退出投影到缓存列表，等权威列表到达再覆盖。
					that.projectSelfLeftRoom(that.myRoom && that.myRoom.roomNo);
that.roomReady=false;
					that.lobbyReady=true;
					that.publicChatFloating=false;
					that.gameReady=false;
					that.oppoReady=false;
					that.gameStarted=false;
					that.oppoId = '-1';
					that.myRoom = {};
					//换房间后局序号失效：不清掉会让下次认输带上别的房间的序号而被服务端判成过期
					that.curBoardVersion = null;
					that.historySteps = [];
					that.initChessArray();
					that.clearBoard();
					that.saveViewState(null);
				});
			},
			/**
			 * 把自己「已经离开某房间」这件事先投影到本地的在线房间列表上。
			 * 服务端处理 quit-room 后会广播新的房间列表，但那要一个网络往返；在此之前大厅用的是旧列表，
			 * 会把自己还画在座位上（单人房间则是整张卡片闪一下才消失）。
			 * 投影规则与服务端 quitRoom 一致：
			 *   房客离开 → 座位清空、房间回到「等待加入」；
			 *   房主离开且还有房客 → 房客升为房主、座位清空、比分与准备状态清零；
			 *   房主单独离开 → 服务端会解散该房间，直接从列表移除。
			 * 只是本地投影，服务端下发的权威列表到达后会整体覆盖。
			 */
			projectSelfLeftRoom(roomNo){
				let myId = (this.user && this.user.id) ? String(this.user.id) : '';
				let no = roomNo ? String(roomNo) : '';
				if(!myId || !no){ return; }
				let rooms = this.onlineRooms || [];
				let next = [];
				for(let i = 0; i < rooms.length; i++){
					let room = rooms[i];
					if(!room || String(room.roomNo) !== no){ next.push(room); continue; }
					let isP1 = String(room.playerId1) === myId;
					let isP2 = String(room.playerId2) === myId;
					if(!isP1 && !isP2){ next.push(room); continue; }
					if(isP1){
						let guest = room.playerId2;
						let hasGuest = guest && String(guest) !== '-1' && String(guest) !== '';
						//单人房间：房间会被解散，不必等一个往返才让它消失
						if(!hasGuest){ continue; }
						next.push(Object.assign({}, room, {
							playerId1: room.playerId2,
							playerName1: room.playerName2,
							playerAvatar1: room.playerAvatar2,
							playerId2: '-1',
							playerName2: '',
							playerAvatar2: '',
							state: 'waiting',
							started: false,
							readyId1: false,
							readyId2: false,
							scoreId1: 0,
							scoreId2: 0
						}));
					}else{
						next.push(Object.assign({}, room, {
							playerId2: '-1',
							playerName2: '',
							playerAvatar2: '',
							state: 'waiting',
							started: false,
							readyId1: false,
							readyId2: false,
							scoreId1: 0,
							scoreId2: 0
						}));
					}
				}
				this.onlineRooms = next;
			},
			setOnlineRoom(rooms){
				this.onlineRooms = rooms;
			},
			/**
			 * 把「某个房间已经解散」这件事先投影到本地的在线房间列表上。
			 * room-closed 的所有来源（房主独处退出、房主另建房间、管理员解散、失联回收）
			 * 在服务端都已经把房间从列表里移除，但权威列表要一个网络往返才回来；
			 * 这段间隙里回大厅会先闪一下该房间的旧卡片。这里直接摘卡片，等权威列表覆盖。
			 */
			projectRoomGone(roomNo){
				let no = roomNo ? String(roomNo) : '';
				if(!no){ return; }
				let rooms = this.onlineRooms || [];
				this.onlineRooms = rooms.filter(function(room){
					return !room || String(room.roomNo) !== no;
				});
			},
			//收到房间消息
			receiveChatMessage(data){
				let position;
				if(data.fromId===this.user.id)	position = 'right';
				else		position = 'left';
				let message = {
					type:'chat',
					senderNick: data.fromName, // 单独存储昵称
					content: data.content,     // 单独存储内容，不再拼接
					time: this.getTimeNow(),    // 完整时间（年月日时分秒）
					position: position,
				}
this.messages.push(message);
				//房间消息同样只保留最近 300 条，避免长对局中聊天无限堆积
				if(this.messages.length > 300){
					this.messages.splice(0, this.messages.length - 300);
				}
				this.scrollToBottom();
			},
			//有人加入房间
			someoneJoinRoom(data){
				let room = data.content;
				this.myRoom.playerId2 = room.playerId2;
				this.oppoId = room.playerId2;
				this.myRoom.playerName2 = room.playerName2;
				//状态以服务端视图为准：新人刚坐下时是「房间中」，双方都准备后才会变成「游戏中」
				this.myRoom.state = room.state || 'room';
				//同步服务端房间视图里的准备状态与管理员标记，供昵称旁状态和「踢出玩家」判断使用
				this.myRoom.readyId1 = !!room.readyId1;
				this.myRoom.readyId2 = !!room.readyId2;
				this.myRoom.playerAdmin1 = !!room.playerAdmin1;
				this.myRoom.playerAdmin2 = !!room.playerAdmin2;
				//新对手加入时服务端已把双方准备状态清零，本地必须同步，否则旧房主会停留在“已准备”假象
				this.gameReady = false;
				this.oppoReady = false;
				this.gameStarted = false;
				this.isOver = false;
				//构造系统消息
				let message = {
					type:'system',
					position:'middle',
					content:'系统消息： 棋手:\t'+room.playerName2+'\t已加入房间',
					time:this.getTimeNow()
				}
				this.messages.push(message);
				this.scrollToBottom();
				//需要告诉对方自己时候已经准备了
				message = {
					type:'info-ready',
					content: this.gameReady,
					to:this.myRoom.playerId2,
					from:this.user.id
				}
				socket.send(JSON.stringify(message))
			},
			//收到对方
			oppoReadyChange(data){
				if(this.myRoom.id===undefined||this.myRoom.id===null){
					return;
				}
if(data.content){
//对手已经准备好了：重复收到同一通知时直接返回，避免消息无限堆积、重复请求开局
					if(this.oppoReady){
						return;
					}
					this.oppoReady = true;
					//服务端在双方都准备时会直接下发 start-game，这里的准备提示只在对局尚未开始时才需要；
					//对局已开始说明开局消息先到了，不再重复提示/请求
					if(this.gameStarted){
						return;
					}
					let message = {
						type:'system',
						position:'middle',
						content:'系统消息：对手已经准备好了',
						time:this.getTimeNow()
					}
					this.messages.push(message);
					this.scrollToBottom();
					//如果自己也准备好了  就可以开始游戏了
					if(this.gameReady){
						let message={
							type:'start-game',
							roomId:this.myRoom.id
						}
						socket.send(JSON.stringify(message));
					}
				}else{
					//对手取消准备：对局开始前同步回「房间中」，并提示
					if(!this.oppoReady || this.gameStarted){
						return;
					}
					this.oppoReady = false;
					this.addSystemMessage('系统消息：对手已取消准备');
				}
			},
			//主动加入别人的房间
joinRoomSuccess(data){
				//接受邀请进入房间时，必须同时退出巡查/观战/复盘等旧视图，避免“退出查看”等旧控件残留
				this.adminViewOpen = false;
				this.adminRoomId = '';
				this.adminRoomView = {};
				this.adminChatRecords = [];
				this.adminSteps = [];
				this.spectateMode = false;
				this.spectateRoom = {};
				this.reviewReady = false;
				this.tableReady = false;
				//加入别人的房间同样要记录视图，否则刷新后会先闪一下大厅再被 room-restore 拉回房间
				this.saveViewState({view:'room'});
				//清空消息
				this.messages = [];
				this.myRoom = data.content;
				//入房后同步该房间的局序号（房内可能已有对局，认输时要带正确序号）
				this.curBoardVersion = null;
				this.syncBoardVersion(data.content);
				this.lobbyReady = false;
				this.gameReady = false;
				this.oppoReady = false;
				this.gameStarted = false;
				this.roomReady = true;
				this.oppoId = this.myRoom.playerId1;
				this.initChessArray();
				this.clearBoard();
				//构造系统消息
				let message = {
					type:'system',
					position:'middle',
					content:'系统消息：你已经加入房间',
					time:this.getTimeNow()
				}
				this.messages.push(message);
				this.scrollToBottom();
				// ========== 房间聊天自动提示 ==========
				this.addSystemMessage("网络空间并非法外之地，请自觉遵守法律法规，严禁发布、传播涉黄、涉赌、涉毒及各类违法违规信息，共同维护健康文明的聊天环境。");
			},
			//开始游戏
startGame(data){
				//新的一局开始前先清空上一局的棋局数据：对局结束后本地仍保留终局画面，
				//只有这里统一清理，才能保证「准备→开局」和「重开→开局」两条入口表现一致
				this.syncBoardVersion(data);
				this.historySteps = [];
				this.initChessArray();
				this.clearBoard();
				//重新初始化赢法
				this.initWins();
				//新开局不应保留上一局的对手最后一步标记，否则下一手清理旧标记时会在新棋盘上画出幽灵棋子
				this.lastOppoX = -1;
				this.lastOppoY = -1;
				this.lastPlay = false;
				let color = data.content;
				this.myChessColor = color; //棋子颜色
				if(color==='black'){
					this.isMyTurn = true; //黑棋优先
					//构造系统消息
					this.addSystemMessage('游戏开始 你是黑棋 请先手!')
				}else{
					this.isMyTurn = false;
					//构造系统消息
					this.addSystemMessage('游戏开始 你是白棋 等待对方先手!')
				}
this.isOver = false;
				this.requestWaiting = false;
				this.incomingRequest = false;
				this.pendingActionVersion = null;
				//开局时双方都已经准备：本地状态与服务端保持一致
				this.gameReady = true;
				this.oppoReady = true;
				this.gameStarted = true;
				this.toastMsg('游戏开始');
			},
			oppoPlay(data){
				let i = data.x;
				let j = data.y;
				//对手下棋  画棋子
				this.context.beginPath();
				this.context.arc(i*30+15, j*30+15, 13, 0, 2*Math.PI);//画圆
				this.context.closePath();
				this.context.fillStyle=data.color;
				this.context.fill();
				// 对手最后一步标注
				this.clearLastOppoMark(); // 清除上一次的旧标注
				this.drawLastOppoMark(i, j); // 绘制最新标注
				this.lastOppoX = i; // 更新最后一步坐标
				this.lastOppoY = j;
				// ==================================================================
				this.chess_Board[i][j] = 1;
				//判断对手是否赢了
				for(let k = 0; k < this.count; k ++){
					if(this.wins[i][j][k]){
						this.OppoWin[k] += 1;
if(this.OppoWin[k] === 5){
							this.toastMsg('败北');
							this.isOver = true;
							//终局棋盘由服务端下发的 game-over（含连五坐标）统一绘制，保证胜负双方效果一致
							this.addSystemMessage('很遗憾 本局 你输了');
						}
					}
				}
				//记录步骤
				this.lastPlay = false;  //上一步是我下的
				//记录步数
				let step = {
					player:'oppo',
					i:i,
					j:j
				}
				this.historySteps.unshift(step);
				//该我下了
				this.isMyTurn = true;
			},
			//处理认输
			processSurrender(data){
				this.isOver = true;
				//对手认输结束本局：此前挂着的一切等待/弹窗状态都要清掉，
				//否则自己发过的求和请求会让 requestWaiting 一直为 true，按钮全部点不动
				this.requestWaiting = false;
				this.incomingRequest = false;
				this.pendingActionVersion = null;
				this.toastMsg('对手已认输！');
				//构造系统消息
				let message = {
					type:'system',
					content:'系统消息：对手认输',
					time:this.getTimeNow(),
					position:'middle',
				}
				this.messages.push(message);
				this.scrollToBottom();
			},
			//服务端判定某次操作没有生效：解除本地等待状态并提示原因，但不动棋盘
			processActionInvalid(data){
				let reason = (data && data.content) ? String(data.content) : '该操作已失效';
				this.requestWaiting = false;
				this.incomingRequest = false;
				this.pendingActionVersion = null;
				this.toastMsg(reason, 4000, true);
				this.addSystemMessage('系统消息：' + reason);
				//服务端会明确告知本局是否已经结束：
				//若这一局其实还在进行（例如对手的和棋先生效并已开了新局，本次认输被丢弃），
				//必须把本地从「已认输/已和棋」的终局态解出来，否则按钮与棋盘全部点不动，看起来像卡死。
				if(data && typeof data.roundOver === 'boolean'){
					this.isOver = data.roundOver;
				}else if(reason.indexOf('重新开始') !== -1){
					//兼容旧版服务端（不带 roundOver）
					this.isOver = false;
				}
			},
			//处理和棋
			processPeace(data){
				let that = this;
				//已有对手请求弹窗未处理：自动拒绝，避免两个弹窗叠加后两条操作接连生效
				if(this.incomingRequest){
					//重连补推的同一条请求：本地弹窗还在，直接忽略
					if(data && data.resend){ return; }
					this.rejectExtraRequest('peace-reject','对手又发来一个和棋请求，已自动拒绝');
					return;
				}
				//记录请求所属的局：同意时回传，棋局已重开时服务端会作废这条同意
				this.pendingActionVersion = (data && data.boardVersion !== undefined) ? data.boardVersion : null;
				//弹窗期间挂起本地操作，避免双方同时发起请求导致两套「同意」互相叠加
				this.incomingRequest = true;
				$.confirm({
					title: '对方请求和棋!',
					content: '是否同意？(少于3回合的对局不会被保存)',
					confirmButton:'同意',
					cancelButton:'拒绝',
					confirm: function(){
						that.incomingRequest = false;
						let message = {
							type:'peace-agree',
							roomId:that.myRoom.id,
							from:that.user.id,
							to:that.oppoId
						}
						that.attachActionVersion(message);
						socket.send(JSON.stringify(message));
						//构造系统消息
						message = {
							type:'system',
							content:'系统消息：\t本局和棋',
							time:that.getTimeNow(),
							position:'middle',
						}
						that.messages.push(message);
						that.scrollToBottom();
						that.isOver = true;
					},
					cancel: function(){
						that.incomingRequest = false;
						that.pendingActionVersion = null;
						let message = {
							type:'peace-reject',
							roomId:that.myRoom.id,
							from:that.user.id,
							to:that.oppoId
						}
						socket.send(JSON.stringify(message));
						//构造系统消息
						message = {
							type:'system',
							content:'系统消息：\t你拒绝了对方的和棋请求',
							time:that.getTimeNow(),
							position:'middle',
						}
						that.messages.push(message);
						that.scrollToBottom();
					}
				});
			},
			processPeaceAgree(data){
				this.toastMsg('对方同意和棋');
				this.pendingActionVersion = null;
				this.incomingRequest = false;
				let message = {
					type:'system',
					content:'系统消息：\t本局和棋',
					time:this.getTimeNow(),
					position:'middle',
				}
				this.messages.push(message);
				this.scrollToBottom();
				this.isOver = true;
				this.requestWaiting = false;
			},
			//和棋通过：服务端已自动重开一局，本地清空棋盘并按新颜色开局，同时给出提示
			processPeaceRestart(data){
				this.toastMsg('本局和棋，已自动重新开局');
				this.addSystemMessage('本局和棋，已自动重新开局');
				this.historySteps = [];
				this.initChessArray();
				this.clearBoard();
				this.startGame(data);
			},
			processRestart(data){
				let that = this;
				//已有对手请求弹窗未处理：自动拒绝，避免叠加
				if(this.incomingRequest){
					//重连补推的同一条请求：本地弹窗还在，直接忽略
					if(data && data.resend){ return; }
					this.rejectExtraRequest('restart-reject','对手又发来一个重开请求，已自动拒绝');
					return;
				}
				//记录请求所属的局：同意时回传，棋局已重开时服务端会作废这条同意
				this.pendingActionVersion = (data && data.boardVersion !== undefined) ? data.boardVersion : null;
				//弹窗期间挂起本地操作，避免双方同时发起重开导致两次重置
				this.incomingRequest = true;
				$.confirm({
					title: '对方请求重新开局!',
					content: '未完成的对局不会被保存!',
					confirmButton:'同意',
					cancelButton:'拒绝',
					confirm: function(){
						that.incomingRequest = false;
						let message = {
							type:'restart-agree',
							roomId:that.myRoom.id,
							from:that.user.id,
							to:that.oppoId
						}
						that.attachActionVersion(message);
						socket.send(JSON.stringify(message));
						//本地不先清空棋盘：等服务端回 start-game 再统一重置。
						//否则请求被服务端判为过期（棋局已重开）时，本地已经清空，与服务端不一致
						that.addSystemMessage('你同意了对方的重新开局请求');
					},
					cancel: function(){
						that.incomingRequest = false;
						that.pendingActionVersion = null;
						let message = {
							type:'restart-reject',
							roomId:that.myRoom.id,
							from:that.user.id,
							to:that.oppoId
						}
						socket.send(JSON.stringify(message));
						that.addSystemMessage('你已拒绝重新开局！');
					}
				});
			},
			processRestartAgree(data){
				this.toastMsg('对方同意重新开局！');
				this.addSystemMessage('重新开局');
				//清空历史
				this.historySteps = [];
				//清空棋盘
				this.initChessArray();
				//重新绘制棋盘
				this.clearBoard();
				this.startGame(data);
				this.requestWaiting = false;
			},
			//自己变成了房主
			becomeOwner(data){
				this.requestWaiting = false;
				this.oppoReady = false;
				this.myRoom = data.content;
				this.gameReady = false;
				this.isMyTurn = false;
				this.isOver = true;
				this.oppoId = '-1';
				//重新绘制棋盘
				this.historySteps = [];
				this.initChessArray();
				this.clearBoard();
				this.toastMsg('房主已经离开，你成为房间房主');
				this.addSystemMessage('对手已离开');
				this.addSystemMessage('你成为房主');
			},
			//房客退了
			clientLeave(data){
				this.requestWaiting = false;
				this.myRoom = data.content;
				this.toastMsg('对手离开了房间');
				this.oppoReady = false;
				this.gameReady = false;
				this.gameStarted = false;
				this.isMyTurn = false;
				this.isOver = true;
				this.oppoId = '-1';
				//重新绘制棋盘
				this.historySteps = [];
				this.initChessArray();
this.clearBoard();
				this.addSystemMessage('对手离开了房间');
			},
			//登录状态失效：已登录时在当前页弹提示；刷新/首次连接阶段直接带原因回登录页。
			//reason 由服务端给出（restart=服务重启 / superseded=账号在别处登录 / loggedout 等），
			//前端据此显示不同的文案，而不是笼统的「登录状态已失效」。
			handleAuthExpired(reason){
				if(window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(!window.__fcAuthOk){
					redirectLoginWithNotice(reason || 'expired');
					return;
				}
				this.showSessionLost(reason || 'expired');
			},
			//统一处理“会话已失效”：弹一个说明原因的弹窗，确认后回登录页
			showSessionLost(reason){
				if(window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				//弹窗才是这一刻的主角，提示条立即撤掉，不占用最短停留时间
				this.hideConnBanner(true);
				this.connProbeBanner = false;
				if(this.reconnectTimer){ clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
				sessionNotice(reason || 'expired');
				try{
					if(socket){
						socket.onclose = null;
						socket.onerror = null;
						socket.close();
						socket = null;
					}
				}catch(e){}
			},
			//服务端判定的一局结束：双方准备状态已被清零，界面退回「准备」状态。
			//此后双方各自准备，服务端会自动开下一局，不再需要点「重开」。
			processRoundReset(data){
				let view = (data && data.roomView) ? data.roomView : null;
				this.requestWaiting = false;
				this.incomingRequest = false;
				this.pendingActionVersion = null;
				this.isMyTurn = false;
				this.lastPlay = false;
				//本局已结束：隐藏悔棋/认输/求和/重开，显示「准备」按钮；
				//终局棋盘继续留在画面上，等下一局的开局消息再统一清盘
				this.gameStarted = false;
				this.isOver = true;
				if(view){
					this.applyRoomView(view);
					//准备状态以服务端快照为准，避免本地还停留在「已准备」的假象
					let meIsPlayer1 = String(view.playerId1) === String(this.user.id);
					this.gameReady = meIsPlayer1 ? !!view.readyId1 : !!view.readyId2;
					this.oppoReady = meIsPlayer1 ? !!view.readyId2 : !!view.readyId1;
				}else{
					this.gameReady = false;
					this.oppoReady = false;
				}
				this.addSystemMessage('系统消息：本局已结束，双方已回到未准备状态；双方都准备后自动开始下一局');
			},
			//服务端判定的对局结果（winId 为 0 表示和棋）
processGameOver(data){
				let winId = data.winId;
				this.isMyTurn = false;
				this.isOver = true;
				this.requestWaiting = false;
				//终局棋盘效果统一：清除最后一步的临时标注，按服务端下发的连五坐标高亮，
				//使获胜方与失败方屏幕上看到的棋盘效果一致
				this.clearLastOppoMark();
				this.drawWinLine(data.line);
				if(winId === 0){
					this.toastMsg('和棋');
					this.addSystemMessage('系统消息：本局和棋');
				}else if(winId == this.user.id){
					this.toastMsg('获胜！');
					this.addSystemMessage('系统消息：本局获胜');
				}else{
					this.toastMsg('败北');
					this.addSystemMessage('系统消息：本局失败');
				}
			},
			//终局：按服务端下发的连五坐标绘制五子连线高亮（双方与观战者使用同一份数据，效果一致）
			drawWinLine(line){
				if(!line || !line.length){ return; }
				this.context.strokeStyle = '#e53935';
				this.context.lineWidth = 3;
				this.context.beginPath();
				this.context.moveTo(line[0].x*30+15, line[0].y*30+15);
				for(let n=1; n<line.length; n++){
					this.context.lineTo(line[n].x*30+15, line[n].y*30+15);
				}
				this.context.stroke();
				for(let m=0; m<line.length; m++){
					this.context.beginPath();
					this.context.arc(line[m].x*30+15, line[m].y*30+15, 5, 0, 2*Math.PI);
					this.context.closePath();
					this.context.fillStyle = 'red';
					this.context.fill();
				}
				this.context.lineWidth = 1;
			},
			//服务端拒绝本次落子（例如还没轮到自己）：撤回本地棋盘上的这一步
			processPlayReject(data){
				let i = data.x;
				let j = data.y;
				this.clearOneStep(i, j);
				this.chess_Board[i][j] = 0;
				this.isMyTurn = true;
				this.lastPlay = false;
				this.historySteps.shift();   //撤销刚记录的一步
				this.toastMsg(data.content || '本次落子无效');
			},
			//房主另建房间导致当前房间被解散：回到大厅
processRoomClosed(data){
				var content = (data && data.content) || '房间已解散';
				//房间已在服务端解散：先记下房间号，回大厅前把卡片从本地列表摘掉，
				//否则权威列表回来前大厅会闪一下这张旧卡片
				var goneNo = (this.myRoom && this.myRoom.roomNo)
					|| (this.spectateRoom && this.spectateRoom.roomNo) || '';
				this.projectRoomGone(goneNo);
				this.spectateMode = false;
				this.spectateRoom = {};
				this.myRoom = {};
				this.curBoardVersion = null;
				this.adminViewOpen = false;
				this.adminRoomId = '';
				this.adminRoomView = {};
				this.adminChatRecords = [];
				this.adminSteps = [];
				this.oppoId = '-1';
				this.oppoReady = false;
				this.gameReady = false;
				this.gameStarted = false;
				this.roomReady = false;
				this.tableReady = false;
				this.lobbyReady = true;
				this.publicChatFloating = false;
				this.isMyTurn = false;
				this.isOver = true;
this.historySteps = [];
				this.initChessArray();
				this.clearBoard();
				//房间已解散，清除刷新恢复记录
				this.saveViewState(null);
				if(this.isAdmin){
					//管理员视角保持轻提示
					this.toastMsg(content);
				}else{
					//房内普通成员使用带「知道了」按钮的弹窗提示，点击后关闭
					serverNotice('房间已解散', content);
				}
			},
			//撤回一步：擦掉该点上的棋子并补回棋盘线
			clearOneStep(i, j){
				this.context.clearRect(i*30, j*30, 30, 30);
				this.context.beginPath();
				this.context.moveTo(15, 15+j*30);
				this.context.lineTo(525, 15+j*30);
				this.context.stroke();
				this.context.beginPath();
				this.context.moveTo(15+i*30, 15);
				this.context.lineTo(15+i*30, 525);
				this.context.stroke();
			},
			// ===== 连接管理：首次连接、断线重连、页面恢复共用一套逻辑 =====
			// 设计要点：
			// 1) 连接意外断开不再直接判定“登录失效”，先静默重连（令牌通常仍然有效）；
			// 2) 只有服务端明确回答“令牌无效”才提示，并且提示里说明原因（重启 / 别处登录）；
			// 3) 页面被切到后台、跳转别的页面再回来时只重连不弹窗，避免“刚回来就说登录失效”；
			// 4) 心跳回执 + 静默超时监测，服务被强杀（连接不报错）也能及时发现并重连。
			// ===== 连接状态提示条 =====
			//任何一次连接/重连都立即出现，不再等 800ms：用户要求「不管什么情况下重连都要有提示」，
			//而延迟显示会让快速重连完全无感、慢重连又要干等 0.8 秒才知道在连。
			//为避免「闪一下就没」，配套两条：① 出现即带转圈动画；② 最短停留 FC_BANNER_MIN_MS 再淡出。
			showConnectingBanner(){
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(window.__fcStopReconnect){ return; }
				//已经有更准确的文案（断线退避带「第 N 次」、探活中、点击重试）就别覆盖
				if(this.connBanner){ return; }
				//刷新后的首次连接不该说「断开」；只有已经连上过再掉线才用「重新连接」
				this.setConnBanner(window.__fcEverAuthed ? '正在重新连接服务器…' : '正在连接服务器…', true);
			},
			//统一的赋值入口：转圈开关与出现时刻都在这里维护，调用方只管文案
			//文案里用全角空格「　」分隔主体与动作（例：「…正在重连（第 2 次）…　立即重试」）：
			//主体是状态描述，动作渲染成右侧的小胶囊，桌面和手机上都不会因为一整句话太长而顶到边
			setConnBanner(text, busy){
				if(this.connBannerHideTimer){ clearTimeout(this.connBannerHideTimer); this.connBannerHideTimer = null; }
				if(!this.connBanner){ this.connBannerAt = Date.now(); }
				let raw = text || '';
				let cut = raw.indexOf('　');
				this.connBanner = cut >= 0 ? raw.slice(0, cut) : raw;
				this.connBannerHint = cut >= 0 ? raw.slice(cut + 1).trim() : '';
				this.connBannerBusy = !!busy;
			},
			//撤掉提示条：默认遵守最短停留时间，避免快速恢复时一闪而过；immediate 用于弹窗接管等场景
			hideConnBanner(immediate){
				if(this.connBannerHideTimer){ clearTimeout(this.connBannerHideTimer); this.connBannerHideTimer = null; }
				if(!this.connBanner){ this.connBannerBusy = false; this.connBannerHint = ''; return; }
				let that = this;
				let wait = immediate ? 0 : Math.max(0, FC_BANNER_MIN_MS - (Date.now() - (this.connBannerAt || 0)));
				if(wait <= 0){
					this.connBanner = '';
					this.connBannerHint = '';
					this.connBannerBusy = false;
					return;
				}
				this.connBannerHideTimer = setTimeout(function(){
					that.connBannerHideTimer = null;
					that.connBanner = '';
					that.connBannerHint = '';
					that.connBannerBusy = false;
				}, wait);
			},
			clearConnectingBanner(){
				this.hideConnBanner();
			},
			// ===== 刷新恢复等待提示 =====
			//刷新时大厅与棋盘都被刻意隐藏（否则会先闪一下大厅再跳回房间），中间只剩背景图；
			//恢复超过 400ms 就居中显示「正在恢复上次的房间…」，让用户知道是在等房间而不是卡死。
			armRestoreMask(){
				let that = this;
				if(this.restoreMaskTimer){ clearTimeout(this.restoreMaskTimer); }
				//按刷新前的视图换文案：房间/观战、管理员查看、历史/复盘说的不是一回事
				try{
					let sv = this.readViewState();
					let view = sv && sv.view;
					if(view === 'room' || view === 'spectate'){ this.restoreMaskText = '正在恢复上次的房间…'; }
					else if(view === 'adminView'){ this.restoreMaskText = '正在恢复房间查看页…'; }
					else if(view === 'history' || view === 'review'){ this.restoreMaskText = '正在恢复上次的对局…'; }
				}catch(e){}
				this.restoreMaskTimer = setTimeout(function(){
					that.restoreMaskTimer = null;
					if(!that.restoring){ return; }
					//恢复在这 400ms 内已经完成（房间已渲染、或已回大厅）就不再弹，避免一闪而过
					if(that.roomReady || that.lobbyReady || that.spectateMode || that.adminViewOpen){ return; }
					that.restoreMask = true;
				}, 400);
			},
			disarmRestoreMask(){
				if(this.restoreMaskTimer){ clearTimeout(this.restoreMaskTimer); this.restoreMaskTimer = null; }
				this.restoreMask = false;
			},
			//用户操作前的连接保障：连接正常返回 true；
			//未连接时先把这次操作排队、立即重连，等鉴权成功后自动补发，用户不必再刷新页面
			ensureConnection(action){
				if(socket && socket.readyState === 1 && this.connState === 'open'){
					if(this.probeTimer){
						//正在确认连接是否可用：这次操作先排队，探活成功后自动补发
						if(action){ this.pendingAction = action; }
						this.toastMsg('正在确认连接状态，请稍候…', 2000, true);
						return false;
					}
					return true;
				}
				if(action){ this.pendingAction = action; }
				//正在建立连接：别打断这次尝试，连上后会自动补发
				if(this.connState === 'connecting' && socket && socket.readyState === 0){
					this.toastMsg('正在连接服务器，连接成功后会自动继续…', 2000, true);
					return false;
				}
				this.toastMsg('连接已断开，正在重新连接…', 2000, true);
				//手动操作即视为主动重试：解除“连接数超限暂停重连”并立刻发起
				window.__fcStopReconnect = false;
				if(this.reconnectTimer){ clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
				this.reconnectAttempt = 0;
				this.connectSocket();
				return false;
			},
			//连接看门狗：页面可见却没连上就定期重试。
			//跳转外部页面再返回时 pagehide/pageshow 未必按预期触发，靠定时巡检兜底
			startConnWatchdog(){
				let that = this;
				if(this.connWatchdogTimer){ clearInterval(this.connWatchdogTimer); }
				this.connWatchdogTimer = setInterval(function(){
					if(document.visibilityState === 'hidden'){ return; }
					if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
					if(window.__fcStopReconnect){ return; }
					if(that.connState === 'connecting'){ return; }
					if(that.probeTimer){ return; }
					if(that.reconnectTimer){ return; }
					if(socket && socket.readyState === 1){ return; }
					console.log('连接未建立，主动重试');
					that.reconnectAttempt = 0;
					that.connectSocket();
				}, 5000);
			},
			initSocket(){
				this.reconnectAttempt = 0;
				this.connectSocket();
			},
			//建立一条 WebSocket 连接
			connectSocket(){
				let that = this;
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(that.reconnectTimer){
					clearTimeout(that.reconnectTimer);
					that.reconnectTimer = null;
				}
				if(that.probeTimer){
					clearTimeout(that.probeTimer);
					that.probeTimer = null;
				}
				if(typeof (WebSocket) == "undefined"){
					console.log("您的浏览器不支持websocket");
					return;
				}
				if(that.heartbeatTimer){
					clearInterval(that.heartbeatTimer);
					that.heartbeatTimer = null;
				}
				//旧连接显式作废：它的迟到事件会被 socket!==ws 守卫忽略，不会误报会话失效。
				//注意：这里不把 socket 置空——断开期间用户点按钮时 send 落在已关闭的连接上会被丢弃，
				//比落在 null 上直接抛错更安全。
				let old = socket;
				if(old){
					try{
						old.onopen = null;
						old.onclose = null;
						old.onerror = null;
						old.onmessage = null;
						old.close();
					}catch(e){}
				}
				let ws = new WebSocket(getWsUrl()+"/socket/"+this.user.id);
				socket = ws;
				that.connState = 'connecting';
				//每一次连接都立刻在顶部显示「正在连接/重新连接服务器…」并带转圈，
				//快于 500ms 的恢复也会停留满再淡出，不会一闪而过
				that.showConnectingBanner();
				//连接超时：网络黑洞时浏览器可能迟迟不报错，8 秒未建立就当作失败进入重连
				let connectTimer = setTimeout(function(){
					if(ws === socket && that.connState === 'connecting'){
						console.log('建立连接超时，转入重连');
						that.handleSocketDown(ws);
					}
				}, 8000);
				//打开事件
				ws.onopen = function () {
					if(ws !== socket){ return; }
					clearTimeout(connectTimer);
					console.log("websocket已经连接");
					// ========== 先完成登录令牌鉴权，鉴权通过前服务端不处理任何业务指令 ==========
					let authMsg = {
						type: "auth",
						userId: that.user.id,
						token: that.user.token
					};
					try{ ws.send(JSON.stringify(authMsg)); }catch(e){ that.handleSocketDown(ws); return; }
					//周期性上报心跳：服务端超过 30 秒未收到心跳会把该用户移出在线列表
					that.heartbeatTimer = setInterval(function(){
						if(ws === socket && ws.readyState === 1){
							try{ ws.send(JSON.stringify({type:'heartbeat', userId: that.user.id})); }catch(e){}
						}
					}, 10000);
					that.startLivenessWatch();
					// ========== 公共聊天系统提示：确保连接后立即可见，且刷新回填历史后仍保留 ==========
					that.ensurePublicSystemTip();
					that.scrollPublicToBottom();
				}
				ws.onerror = function (){
					that.handleSocketDown(ws);
				}
				ws.onclose = function (ev){
					that.handleSocketDown(ws, ev);
				}
				//获取消息事件.
				ws.onmessage = function (msg) {
					if(ws !== socket){ return; }
					//任何来自服务端的消息都说明连接是活的（心跳回执也计入）
					that.lastServerAt = Date.now();
					let data;
					try{
						data = JSON.parse(msg.data);
					}catch(e){
						//非法报文不应让整个消息处理函数抛错，忽略即可
						console.log('忽略无法解析的服务端消息', msg.data);
						return;
					}
					that.handleServerMessage(data, ws);
				}
			},
			//连接断开/出错：先判断是“临时断开可重连”还是“会话确实失效”
			handleSocketDown(ws, ev){
				if(ws && ws !== socket){ return; }
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(window.__fcStopReconnect){ return; }
				//注意：这里不能撤提示条。一次失败会先后触发 onerror 与 onclose，
				//第二次进来时 connState 已是 down、会直接 return；若在 return 之前清理，
				//就会把 scheduleReconnect 刚设好的「第 N 次重试…」又抹掉，造成提示条一闪一闪。
				//提示条只由「鉴权成功 / 会话失效 / 连接数超限」三处负责撤。
				if(this.connState === 'down'){ return; }
				this.connState = 'down';
				if(this.heartbeatTimer){
					clearInterval(this.heartbeatTimer);
					this.heartbeatTimer = null;
				}
				let code = ev && ev.code;
				//1012 = Service Restart：服务端重启前主动关闭，原因明确，立即提示
				if(code === 1012 || window.__fcRestartAnnounced){
					window.__fcRestartAnnounced = false;
					this.showSessionLost('restart');
					return;
				}
				//页面在后台/正在跳转：不弹窗、不重连，等回到页面时（visibilitychange / pageshow）立刻重连
				if(window.__fcPageHidden || document.visibilityState === 'hidden'){
					return;
				}
				this.scheduleReconnect();
			},
			//安排一次重连：退避等待，避免网络抖动时疯狂重连
			scheduleReconnect(){
				let that = this;
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				let delays = [0, 1000, 2000, 3000, 5000, 8000, 12000, 15000];
				let attempt = this.reconnectAttempt;
				let wait = delays[Math.min(attempt, delays.length - 1)];
				this.reconnectAttempt = attempt + 1;
				//首次进入页面（从未鉴权成功）时说「连接已断开」会让人以为是自己掉线了，
				//这类场景多半是后端冷启动或首屏网络慢，用「正在连接」描述更准确
				this.connProbeBanner = false;
				if(window.__fcEverAuthed){
					this.setConnBanner(attempt === 0
						? '连接已断开，正在重新连接…'
						: '连接已断开，正在重连（第 ' + attempt + ' 次）…　立即重试', true);
				}else{
					this.setConnBanner(attempt === 0
						? '正在连接服务器…'
						: '正在连接服务器（第 ' + attempt + ' 次重试）…　立即重试', true);
				}
				if(this.reconnectTimer){ clearTimeout(this.reconnectTimer); }
				this.reconnectTimer = setTimeout(function(){
					that.reconnectTimer = null;
					that.tryReconnect(attempt);
				}, wait);
			},
			//重连前先探测登录态：能拿到明确结论就立刻提示，服务不可达则继续重连
			tryReconnect(attempt){
				let that = this;
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(attempt >= 2){
					fcCheckSession(function(result){
						if(result && result.valid === false){
							that.showSessionLost(result.reason || 'expired');
							return;
						}
						that.connectSocket();
					});
					return;
				}
				this.connectSocket();
			},
			//用户手动点顶部提示条：立即重试一次
			forceReconnect(){
				if(window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				//手动点击即视为用户主动重试：解除“连接数超限暂停重连”的状态
				window.__fcStopReconnect = false;
				if(this.reconnectTimer){ clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
				this.reconnectAttempt = 0;
				this.connProbeBanner = false;
				//立刻把「点击重试」换成「正在连接」，转圈继续转
				this.setConnBanner(window.__fcEverAuthed ? '正在重新连接服务器…' : '正在连接服务器…', true);
				this.connectSocket();
			},
			//心跳回执监测：服务端被强杀时连接可能长时间不报错，靠“安静超时”发现并重连
			startLivenessWatch(){
				let that = this;
				if(this.livenessTimer){ clearInterval(this.livenessTimer); }
				this.livenessTimer = setInterval(function(){
					if(document.visibilityState === 'hidden'){ return; }
					if(that.connState !== 'open'){ return; }
					//正在探活：由探活结果决定，避免同时再来一条连接
					if(that.probeTimer){ return; }
					//服务端未回执心跳（旧版本）时不做判定，避免误重连
					if(!that.gotHeartbeatAck || !that.lastServerAt){ return; }
					if(Date.now() - that.lastServerAt < 25000){ return; }
					console.log('超过 25 秒未收到服务端消息，判定连接已失效，开始重连');
					//保留 socket 引用（已关闭），断开期间用户点按钮的 send 会被安全丢弃
					let dead = socket;
					that.connState = 'down';
					if(that.heartbeatTimer){ clearInterval(that.heartbeatTimer); that.heartbeatTimer = null; }
					try{
						if(dead){
							dead.onclose = null;
							dead.onerror = null;
							dead.close();
						}
					}catch(e){}
					that.scheduleReconnect();
				}, 5000);
			},
			//回到页面时，旧连接可能“看着还是 OPEN、其实已经死了”（服务重启、网络切换、切后台被系统断流），
			//这种情况下直接发业务指令会被静默丢弃。这里发一次心跳，1.2 秒内没有任何服务端消息就判定失效并立即重建。
			probeConnection(){
				let that = this;
				if(!socket || socket.readyState !== 1 || this.connState !== 'open'){ return false; }
				if(this.probeTimer){ return true; }
				//服务端不回执心跳（旧版本）时无法探活，交给心跳超时判定，避免误重连
				if(!this.gotHeartbeatAck){ return true; }
				let mark = Date.now();
				try{
					socket.send(JSON.stringify({type:'heartbeat', userId: this.user.id}));
				}catch(e){
					return false;
				}
				this.probeTimer = setTimeout(function(){
					that.probeTimer = null;
					if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
					if(that.lastServerAt >= mark){
						//探活成功：连接还能用，撤掉「正在确认连接状态…」（不足 500ms 会等满再淡出）
						if(that.connProbeBanner){
							that.connProbeBanner = false;
							that.clearConnectingBanner();
						}
						that.replayPending();
						return;
					}
					console.log('回到页面后连接无应答，判定已失效，立即重建');
					that.connState = 'down';
					if(that.heartbeatTimer){ clearInterval(that.heartbeatTimer); that.heartbeatTimer = null; }
					try{
						if(socket){ socket.onclose = null; socket.onerror = null; socket.close(); }
					}catch(e){}
					that.reconnectAttempt = 0;
					if(that.connProbeBanner){
						that.connProbeBanner = false;
						that.setConnBanner('正在重新连接服务器…', true);
					}
					that.connectSocket();
				}, 1200);
				return true;
			},
			//连接确认可用后，补发断线/探活期间用户点过的操作（只补发一次）
			replayPending(){
				let that = this;
				if(!this.pendingAction){ return; }
				let pending = this.pendingAction;
				this.pendingAction = null;
				setTimeout(function(){
					if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
					if(that.connState !== 'open'){ that.pendingAction = pending; return; }
					try{ pending(); }catch(e){}
				}, 0);
			},
			//页面重新可见 / 从浏览器缓存恢复：连接可能已经不存在，静默重连（不弹“登录失效”）
			resumeConnection(fromCache){
				if(window.__fcLeaving || window.__fcLoginRedirecting || window.__fcSessionNoticeShown){ return; }
				if(!fromCache && document.visibilityState === 'hidden'){ return; }
				if(!fromCache && socket && socket.readyState === 1 && this.connState === 'open'){
					//离开超过 30 秒：连接可能已被系统断流（手机锁屏、切后台太久），
					//探活要等 1.2 秒才出结果，先给提示，别让用户以为已经好了
					let hiddenFor = window.__fcHiddenAt ? (Date.now() - window.__fcHiddenAt) : 0;
					if(hiddenFor >= 30000){
						this.connProbeBanner = true;
						this.setConnBanner('正在确认连接状态…', true);
					}
					//连接看着还在：先探活，通了就继续用，不通立即重建，避免“显示在线却发不出消息”
					if(this.probeConnection()){ return; }
					this.connState = 'down';
					if(this.connProbeBanner){
						this.connProbeBanner = false;
						this.setConnBanner('正在重新连接服务器…', true);
					}
				}
				this.reconnectAttempt = 0;
				//直接重连：令牌若已失效，服务端会在鉴权时回答 auth-fail（带原因），
				//提示同样准确，且不会被“探测请求”拖慢恢复速度
				this.connectSocket();
			},
			//重连成功后确认房间是否还在：掉线时间过长时服务端已把用户移出房间，
			//此时若继续停留在旧房间界面，后续操作都会失败，这里统一回到大厅并说明原因
			verifyRestoreAfterReconnect(){
				let that = this;
				if(!(this.roomReady || this.spectateMode || this.adminViewOpen)){ return; }
				let seq = (this.restoreSeq || 0) + 1;
				this.restoreSeq = seq;
				this.roomRestoredFlag = false;
				setTimeout(function(){
					if(seq !== that.restoreSeq || that.roomRestoredFlag){ return; }
					if(window.__fcLeaving || window.__fcSessionNoticeShown || window.__fcLoginRedirecting){ return; }
					//没收到 room-restore：房间已在掉线期间结束/解散
					that.spectateMode = false;
					that.spectateRoom = {};
					that.adminViewOpen = false;
					that.adminRoomId = '';
					that.roomReady = false;
					that.myRoom = {};
					that.messages = [];
					that.backToLobby();
					that.saveViewState(null);
					that.toastMsg('连接中断时间过长，原房间已结束，已回到大厅', 3000, true);
				}, 2500);
			},
			//服务端消息分发
			handleServerMessage(data, ws){
				let that = this;
switch (data.type) {
case 'auth-success'://登录令牌鉴权通过，再上报在线昵称
								window.__fcAuthOk = true;
								that.connState = 'open';
								that.reconnectAttempt = 0;
								//连接恢复：撤掉提示条（若出现不足 500ms 会等满再淡出，避免一闪而过）
								that.connProbeBanner = false;
								that.clearConnectingBanner();
								window.__fcRestartAnnounced = false;
								window.__fcStopReconnect = false;
								//断线期间用户点过的操作：连上并鉴权通过后补发一次
								if(that.pendingAction){
									let pending = that.pendingAction;
									that.pendingAction = null;
									setTimeout(function(){
										try{ pending(); }catch(e){}
									}, 150);
								}
								try{
									ws.send(JSON.stringify({
										type:'report-nick',
										userId:that.user.id,
										nickName:that.user.name
									}));
								}catch(e){}
								//刷新后恢复刷新前的页面：房间/观战由服务端 room-restore 下发，历史/复盘在此恢复。
								//断线重连不重复恢复，避免把当前界面状态重置一遍。
								if(!window.__fcEverAuthed){
									window.__fcEverAuthed = true;
									that.restoreView();
								}else{
									//重连成功：确认原房间是否还在（掉线过久时服务端已把用户移出房间）
									that.verifyRestoreAfterReconnect();
								}
								//管理员：拉取封号名单（大厅左侧面板默认展开）
								if(that.isAdmin){ that.loadBannedList(); }
								//重连/刷新后向服务端确认头像（服务重启后本地缓存可能与服务端不一致）
								that.refreshAvatar();
								break;
							case 'auth-fail'://鉴权失败：服务端会带回原因（restart/superseded/...）
								that.handleAuthExpired(data.reason);
								break;
							case 'game-over'://服务端判定的对局结果
								if(that.spectateMode){
									that.spectateOver(data);
								}else if(that.adminViewOpen){
									//管理员隐身查看：只提示结果并保留查看界面，不自动退出
									if(data.line){ that.drawWinLine(data.line); }
									that.isOver = true;
									if(data.winId === 0){
										that.adminAppendSystem('本局和棋');
									}else{
										that.adminAppendSystem('本局结束：' + that.spectateNickById(data.winId) + ' 获胜');
									}
									if(data.winId === 0){
										that.toastMsg('本局和棋', 3000, true);
									}else{
										that.toastMsg('本局结束：' + that.spectateNickById(data.winId) + ' 获胜', 3000, true);
									}
								}else{
									that.processGameOver(data);
								}
								break;
							case 'round-reset'://一局结束：双方回到未准备，双方准备后自动开始下一局
								if(that.spectateMode || that.adminViewOpen){
									//观战/巡查视图只同步房间视图（准备标签），不参与准备流程
									if(data && data.roomView){ that.applyRoomView(data.roomView); }
								}else{
									that.processRoundReset(data);
								}
								break;
							case 'spectate-notice'://操作说明（悔棋/认输/求和/重开及其结果）：观战者与隐身查看的管理员都能看到
								if(that.spectateMode){
									that.addSystemMessage(data.content || '');
									//观战时房间消息面板可能不在视线内，同时用轻提示即时提醒，确保观战者能看到
									if(data.content){
										that.toastMsg(data.content, 2500, true);
									}
								}else if(that.adminViewOpen){
									//管理员隐身查看时：轻提示 + 同步写入房间消息窗口，保证消息完整
									if(data.content){
										that.toastMsg(data.content, 2500, true);
										that.adminAppendSystem(data.content);
									}
								}
								break;
							case 'spectate-end'://对局因玩家离开/被踢/被封号而强制结束：询问是否继续观看
								if(that.spectateMode){
									that.toastMsg(data.content || '观战已结束', 3000, true);
									that.addSystemMessage(data.content || '观战已结束');
									that.promptSpectateEnded(data.content || '对局已因玩家离开而结束');
								}
								break;
							case 'play-reject'://服务端拒绝了本次落子
								that.processPlayReject(data);
								break;
							case 'room-closed'://房主另建房间，当前房间已解散
								that.processRoomClosed(data);
								break;
case 'kicked'://账号在别处登录（服务端把旧连接顶掉了）
								that.user = {};
								that.showSessionLost('superseded');
								break;
							case 'heartbeat-ack'://心跳回执：用于判断服务端是否还在响应
								that.gotHeartbeatAck = true;
								break;
							case 'chat-limit'://发言过于频繁：服务端丢掉了这条消息，给用户明确反馈（原先没有任何提示）
								that.toastMsg(data.content || '发言过于频繁，请稍后再试！');
								break;
							case 'ws-limit'://单 IP 并发连接数超限：服务端随后会断开，这里不再自动重试，避免反复撞限
								window.__fcStopReconnect = true;
								that.connProbeBanner = false;
								//已暂停自动重连，不转圈（转圈会让人以为还在重试）；点提示条才恢复
								that.setConnBanner((data.content || '当前网络连接数过多，请稍后再试！') + '　立即重试', false);
								break;
							case 'server-restart'://服务端重启前主动通知：立即提示，不必等请求失败
								window.__fcRestartAnnounced = true;
								that.showSessionLost('restart');
								break;
							case 'online-rooms':
								that.setOnlineRoom(data.content);
								break;
							case 'create-room-success':
								that.initMyRoom(data.content);
								break;
case 'join-room-success':  //加入房间成功
								closeRoomPasswordDialog();
								that.joinRoomSuccess(data);
								break;
							case 'join-room-fail':  //加入房间失败：房间已满、已不存在或不能加入自己的房间
								var joinFailContent = data.content || '加入房间失败';
								if(roomPasswordDialog && joinFailContent.indexOf('密码') >= 0){
									//密码错误：保持密码弹窗与已输入内容，在输入框下方就地提示，等待重新输入
									setRoomPasswordError(joinFailContent);
								}else{
									closeRoomPasswordDialog();
									that.toastMsg(joinFailContent);
								}
								break;
							case 'spectator-update'://房间内观战人数变化
								that.applyRoomView(data.roomView);
								break;
							case 'invite-sent'://邀请已发出
								that.setInvitePending(data.targetId);
								that.toastMsg('已邀请 ' + (data.targetNick || '对方') + ' 加入房间');
								break;
							case 'invite-fail'://邀请未能发出（含拒绝冷却/当天屏蔽）
								that.clearInvitePending(data.targetId);
								if(data.targetId && data.cooldown){ that.startInviteCooldown(data.targetId, data.cooldown); }
								if(data.blocked){ that.setInviteBlocked(data.targetId); }
								that.toastMsg(data.content || '邀请失败');
								break;
							case 'invite-blocked-list'://登录后同步当天屏蔽名单
								that.applyInviteBlockedList(data.content);
								break;
							case 'spectate-fail'://无法观战
								that.toastMsg(data.content || '无法观战');
								break;
case 'spectate-success'://进入观战
								that.enterSpectate(data);
								break;
							case 'room-restore'://刷新页面后恢复房间/观战视图
								that.restorePending = null;
								that.restoreRoom(data);
								break;
case 'spectate-reset'://观战中的对局重开
								if(that.adminViewOpen){
									if(data && data.roomView){ that.adminRoomView = data.roomView; }
									that.adminSteps = ((data || {}).steps || []).slice();
									that.drawSpectateBoard(that.adminSteps);
									that.isOver = false;
								}else{
									that.spectateReset(data);
								}
								break;
							case 'invite-received'://收到邀请，可接受或拒绝
								that.processInvite(data);
								break;
							case 'invite-accepted'://对方接受了邀请
								that.clearInvitePending(data.byId);
								that.toastMsg((data.byName || '对方') + ' 接受了邀请');
								break;
							case 'invite-timeout'://邀请超过 30 秒未被处理，自动拒绝并关闭弹窗
								that.stopInviteCountdown();
								try{ if(that.inviteDialog){ that.inviteDialog.close(); } }catch(e){}
								that.inviteDialog = null;
								that.toastMsg('邀请已超时自动拒绝');
								break;
							case 'invite-rejected'://对方拒绝了邀请（含超时未处理）
								that.clearInvitePending(data.byId);
								if(data.blocked){ that.setInviteBlocked(data.byId); }
								if(data.byId && data.cooldown){ that.startInviteCooldown(data.byId, data.cooldown); }
								that.toastMsg((data.byName || '对方') + (data.reason === 'timeout' ? ' 未在 30 秒内响应，邀请已自动拒绝' : ' 拒绝了你的邀请'));
								break;
							case 'kicked-from-room'://被房主踢出房间
								that.processKicked(data);
								break;
							case 'room-open-changed'://房主切换了房间可见性
								if(data.roomView){ that.applyRoomView(data.roomView); }
								that.toastMsg(data.roomView && data.roomView.open === false ? '房间已设为私人，其他玩家需输入密码加入' : '房间已设为公开');
								break;
							case 'kick-success'://踢人成功
								if(data.roomView){ that.applyRoomView(data.roomView); }
								that.toastMsg('已将该玩家移出房间');
								break;
							case 'room-open-fail'://仅房主可切换
								that.toastMsg(data.content || '只有房主可以设置房间可见性');
								break;
							case 'kick-fail'://仅房主可踢人
								that.toastMsg(data.content || '无法踢出该玩家');
								break;
case 'chat-message':
								if(that.adminViewOpen){
									//管理员正在隐身查看某个房间：把该房间的实时消息追加到查看面板
									that.adminAppendChat(data);
								}else{
									that.receiveChatMessage(data); //处理收到消息
								}
								break;
							case "someone-join-room":  //处理有人加入房间
								that.someoneJoinRoom(data);
								break;
							case "info-ready":
								that.oppoReadyChange(data);
								break;
case 'start-game'://开始游戏
								that.startGame(data);
								break;
							case 'start-game-fail'://服务端校验到双方未全部准备（例如对手刚取消准备）
								that.toastMsg(data.content || '开局失败，请等待双方准备');
								break;
case 'play':   //下棋
								if(that.spectateMode){
									that.spectatePlay(data);
								}else if(that.adminViewOpen){
									//管理员隐身查看：同步绘制该房间的落子
									that.spectateDrawStone(data.x, data.y, data.color);
									that.adminSteps.push({x:data.x, y:data.y, color:data.color});
								}else{
									that.oppoPlay(data);
								}
								break;
							case 'regret':  //收到对方的悔棋申请
								that.processRegret(data);
								break;
							case 'regret-reject':
								that.toastMsg('对方拒绝了你悔棋请求');
								that.addSystemMessage('对方拒绝了你悔棋请求');
								that.requestWaiting = false;
								break;
							case 'regret-agree':
								that.processRegretAgree(data);
								break;
							case 'regret-applied'://自己同意了对方的悔棋：按服务端的权威步数回退
								that.processRegretResult(data, true);
								break;
							case 'surrender':
								that.processSurrender(data);
								break;
							case 'peace':
								that.processPeace(data); //处理和棋请求
								break;
							case 'peace-reject':
								that.toastMsg('对方拒绝和棋！');
								that.requestWaiting = false;
								break;
case 'peace-agree':
								that.processPeaceAgree(data);
								break;
							case 'peace-restart'://和棋通过后服务端自动重开一局
								that.processPeaceRestart(data);
								break;
							case 'restart':
								that.processRestart(data);
								break;
							case 'restart-reject':
								that.toastMsg('对方拒绝重新开局！');
								that.addSystemMessage('对方拒绝重新开局!');
								that.requestWaiting = false;
								break;
							case 'action-invalid'://服务端判定这次悔棋/求和/重开已失效（对局已结束或棋局已重开）
								that.processActionInvalid(data);
								break;
							case 'restart-agree':
								that.processRestartAgree(data)
								break;
							case 'become-owner':
								that.becomeOwner(data);
								break;
							case 'client-leave':
								that.clientLeave(data);
								break;
								// ====================== 监听在线用户列表推送 ======================
case 'online-user-list':
								that.onlineUserList = data.content.userList;
								that.onlineUsers = data.content.userInfos || [];
								that.onlineCount = data.content.onlineCount;
								break;
							// ====================== 刷新后回填聊天内容 ======================
							case 'public-chat-history'://最近公共聊天记录：刷新后公共聊天窗口不丢内容
								that.publicMessages = (data.content || []).map(function(m){
									return {
										time: m.time || '',
										senderNick: m.nick || '',
										content: m.content || '',
										position: String(m.player) === String(that.user.id) ? 'message-right' : 'message-left'
									};
								});
								that.trimPublicMessages();
								that.scrollPublicToBottom();
								break;
							case 'room-chat-history'://最近房间聊天记录：刷新后房间聊天窗口不丢内容
								that.messages = (data.content || []).map(function(m){
									return {
										type: 'chat',
										time: m.time || '',
										senderNick: m.nick || '',
										content: m.content || '',
position: String(m.player) === String(that.user.id) ? 'message-right' : 'message-left'
									};
								});
								that.ensureRoomSystemTip();
								that.scrollToBottom();
								break;
							// ====================== 管理员相关消息 ======================
case 'self-state'://自身状态（目前为禁言状态）：用于刷新后保持聊天窗口冻结
								that.mutedSelf = data.content === true;
								that.updateMutedMarquee();
								break;
							case 'muted'://被管理员禁言：冻结公共聊天与房间聊天窗口
								that.mutedSelf = true;
								that.updateMutedMarquee();
								that.toastMsg(data.content || '你已被管理员禁言，暂时无法发言');
								break;
							case 'unmuted'://被解除禁言：恢复发言
								that.mutedSelf = false;
								that.toastMsg(data.content || '你已被解除禁言');
								break;
							case 'banned'://被封号：统一单弹窗，点击后回登录页
								that.user = {};
								that.showSessionLost('banned');
								break;
							case 'admin-action-ok'://管理员操作成功回执
								that.toastMsg(data.content || '操作成功');
								//封号/解封后刷新大厅左侧的封号名单
								if(/已封号|已解除封号/.test(data.content || '')){ that.loadBannedList(); }
								break;
							case 'admin-fail'://管理员操作失败（如房间已不存在、无权限）
								that.toastMsg(data.content || '操作失败');
								break;
case 'admin-room-view'://管理员隐身查看的房间视图（含人员与聊天记录）
								that.enterAdminRoomView(data);
								break;
							case 'admin-room-update'://管理员巡查期间房间成员/状态变化
								that.applyAdminRoomUpdate(data);
								break;
							case 'admin-room-event'://管理员巡查期间房间系统事件（加入/离开/准备/开局/终局）
								that.adminAppendEvent(data);
								break;
							case 'admin-room-closed'://管理员巡查的房间被解散
								that.adminRoomClosed(data);
								break;
							case 'admin-banned-list'://已封号账号列表（左侧面板默认展开，这里只更新数据）
								that.adminBannedList = data.content || [];
								break;

								// 接收公共大厅聊天消息
							case 'public-chat-message':
								// 核心：和房间聊天一样，判断是否是自己发的消息
								// 是否为自己发送的消息以服务端下发的发送者id为准，
								// 昵称可以重复，用昵称判断会把别人的消息显示成自己发的
								let isSelf = (data.senderId == that.user.id);
								let position = isSelf ? 'message-right' : 'message-left';
								let chatMsg = {
									time: data.time,
									senderNick: data.senderNick,
									content: data.content,
									position: position // 自己发的用message-right（绿色背景），别人的用message-left
								};
that.publicMessages.push(chatMsg);
								that.trimPublicMessages();
								that.scrollPublicToBottom();
								break;
							// 管理员处置通报：在公共聊天中公示禁言/封号，起到警戒作用（仅实时公示，不入库）
							case 'public-notice':
								that.publicMessages.push({
									type:'notice',
									time: data.time || that.getTimeNow(),
									senderNick: '',
									content: data.content || '',
									position: 'message-left'
								});
								//公共聊天只保留最近 300 条，避免长时间运行内存无限增长
								if(that.publicMessages.length > 300){
									that.publicMessages.splice(0, that.publicMessages.length - 300);
								}
								that.scrollPublicToBottom();
								break;
							default:
								console.log(data)

						}
			},
			//等不能点 悔棋  认输  求和 重开等按钮
			couldPressOrNot(){
				if(this.oppoId==="-1"){
					this.toastMsg('等待对手加入房间');
					return false;
				}
				if(!this.oppoReady){
					this.toastMsg('等待对手准备···');
					return false;
				}
				if(this.incomingRequest){
					this.toastMsg('请先处理对手的请求');
					return false;
				}
				if(this.requestWaiting){
					this.toastMsg('正在等待对手响应···');
					return false;
				}
				return true;
			},
			//构造系统消息
			addSystemMessage(content){
				let message = {
					type:'system',
					content:content,
					time:this.getTimeNow(),
					position:'middle',
				}
this.messages.push(message);
				//房间消息只保留最近 300 条，避免长时间对局/异常客户端刷屏时无限堆积占用内存
				if(this.messages.length > 300){
					this.messages.splice(0, this.messages.length - 300);
				}
				this.scrollToBottom();
			},
			//回到上一步
			GoBackOneStep(){
				if(this.historySteps.length===0){
					this.toastMsg('现在不允许悔棋');
					return;
				}
				let step = this.historySteps.shift();
				this.context.stroke();
				//清除掉棋子痕迹
				this.context.clearRect(step.i*30,step.j*30,30,30);
				//画出线
				this.context.strokeStyle = "black";
				//设置起始点的坐标

				let x1,y1,x2,y2;
				///画出被擦去的x轴  需要考虑边界问题
				if(step.i===0){
					x1=15;
					x2=30;
				}else if(step.i===17){
					x1=17*30;
					x2=17*30+15;
				}else{
					x1 = step.i * 30;
					x2 = step.i * 30+30;
				}
				this.context.beginPath();
				this.context.moveTo(x1,step.j*30+15);
				this.context.lineTo(x2,step.j*30+15);
				this.context.closePath();
				this.context.stroke();

				///画出被擦去的y轴  需要考虑边界问题
				if(step.j===0){
					y1=15;
					y2=30;
				}else if(step.j===17){
					y1=17*30;
					y2=17*30+15;
				}else{
					y1 = step.j * 30;
					y2 = step.j * 30+30;
				}
				//设置起始点的坐标
				this.context.beginPath();
				this.context.moveTo(step.i * 30+15,y1);
				//设置结束点的坐标
				this.context.lineTo(step.i * 30+15,y2);
				this.context.closePath();
				//连接两个点
				this.context.stroke();
				//棋盘置零
				this.chess_Board[step.i][step.j] = 0;
				//赢法回到上一步
				for(let k=0; k<this.count; k ++){
					if(this.wins[step.i][step.j][k]){
						if(step.player==='me')
							this.myWin[k] --;
						else this.OppoWin[k] --;
					}
				}
				if(step.player === 'oppo'){
					this.lastOppoX = -1;
					this.lastOppoY = -1;
				}
			},

			//画赢法
			drawWin(k){
				for(let x=0;x<18;x++){
					for(let y=0;y<18;y++){
						if(this.wins[x][y][k])
						{
							this.context.beginPath();
							this.context.arc(x*30+15, y*30+15, 5, 0, 2*Math.PI);//画圆
							this.context.closePath();
							this.context.fillStyle="red";
							this.context.fill();
						}
					}
				}
			},
			//清空棋盘
			clearBoard(){
				this.context.clearRect(0,0,540,540);
				this.context.strokeStyle = "black";
				for(let i = 0; i < 18; i ++){
					//x轴
					this.context.beginPath();
					//设置起始点的坐标
					this.context.moveTo(15, 15+i*30)
					//设置结束点的坐标
					this.context.lineTo(525,15+i*30);
					//连接两个点
					this.context.stroke();
					//y轴
					//设置起始点的坐标
					this.context.moveTo(15+i*30, 15)
					//设置结束点的坐标
					this.context.lineTo(15+i*30, 525);
					//连接两个点
					this.context.stroke();
					this.context.closePath();
				}
				// 重置标注
				this.lastOppoX = -1;
				this.lastOppoY = -1;
			},
			//画棋盘
drawChessBoard:function (){
				let that = this;
				//脚本位于页面末尾，canvas 已经存在，直接初始化；
				//原先放在 window.onload 里，一旦该事件已触发棋盘就不会被初始化
				let initChessBoard = function (){
					that.chessBoard=document.getElementById('chessBoard');// getElementsByClassName('chessBoard')[0];
					that.context = that.chessBoard.getContext("2d");
					that.context.strokeStyle = "black";
					for(let i = 0; i < 18; i ++){
						//x轴
						//设置起始点的坐标
						that.context.moveTo(15, 15+i*30)
						//设置结束点的坐标
						that.context.lineTo(525,15+i*30);
						//连接两个点
						that.context.stroke();
						//y轴
						//设置起始点的坐标
						that.context.moveTo(15+i*30, 15)
						//设置结束点的坐标
						that.context.lineTo(15+i*30, 525);
						//连接两个点
						that.context.stroke();
					}

					that.chessBoard.onclick=function (e){
						//如果游戏结束，则不可以再下棋
						if(!that.roomReady)	return;
						if(that.requestWaiting){
							that.toastMsg('正在等待对方相应···');
							return;
						}
						if(!that.gameReady){
							//alert('请准备！');
							that.toastMsg('请先准备！');
							return;
						}
						if(that.myRoom.playerId2==="-1"){
							//alert('等待对手加入房间');
							that.toastMsg('等待对手加入房间···');
							return;
						}
						if(!that.oppoReady){
							//alert('等待对手准备···');
							that.toastMsg('等待对手准备···');
							return;
						}
						if(that.isOver){
							that.toastMsg('对局已结束,请重新开始！');
							return;
						}
						//该不该我下棋
						if(!that.isMyTurn){
							return;
						}
//按 canvas 的实际显示尺寸换算落子坐标：棋盘在移动端被等比缩放后依然准确
						let rect = that.chessBoard.getBoundingClientRect();
						let x = (e.clientX - rect.left) * (that.chessBoard.width / rect.width);
						let y = (e.clientY - rect.top) * (that.chessBoard.height / rect.height);

						let i = Math.floor(x/30);
						let j = Math.floor(y/30);
						if(i<0||i>17||j<0||j>17)	return;
						if(that.chess_Board[i][j]===0){
							//可以下一个子
							that.oneStep(i, j);
							that.chess_Board[i][j] = 1;
							that.isMyTurn = false;
							that.lastPlay = true;  //上一步是我下的
							//自己落子，清空对手标记
							that.clearLastOppoMark();
							that.lastOppoX = -1;
							that.lastOppoY = -1;
							//=========================================

							//记录步数
							let step = {
								player:'me',
								i:i,
								j:j
							}
							that.historySteps.unshift(step);
							//构造消息
							let message = {
								type:'play',
								x:i,
								y:j,
								color:that.myChessColor,
								roomId:that.myRoom.id,
								from:that.user.id,
								to:that.oppoId
							}
socket.send(JSON.stringify(message));
							that.isWin(i, j);
						}
					}
				};
				//直接调用初始化，不依赖 window.onload
				initChessBoard();
			},
initWins:function (){
				//每次开局都重新从0开始编号，否则赢法数组与编号会不断膨胀
				this.count = 0;
				//初始化数组大小
				for(let i = 0; i < 18; i ++){
					this.wins[i] = [];
					for(let j = 0; j < 18; j ++){
						this.wins[i][j] = [];
					}
				}
				//赢法的编号

				//统计横线赢法
				for(let i = 0; i  < 18; i ++){
					for(let j = 0; j < 14; j ++){
						for(let k = 0; k < 5; k ++){
							this.wins[j+k][i][this.count] = true;
						}
						this.count++;
					}
				}
				//统计竖线赢法
				for(let i = 0; i  < 18; i ++){
					for(let j = 0; j < 14; j ++){
						for(let k = 0; k < 5; k ++){
							this.wins[i][j+k][this.count] = true;
						}
						this.count++;
					}
				}
				//统计正斜线赢法
				for(let i = 0; i  < 14; i ++){
					for(let j = 0; j < 14; j ++){
						for(let k = 0; k < 5; k ++){
							this.wins[i+k][j+k][this.count] = true;
						}
						this.count++;
					}
				}
				//统计反斜线赢法
				for(let i = 0; i  < 14; i ++){
					for(let j = 17; j > 3; j --){
						for(let k = 0; k < 5; k ++){
							this.wins[i+k][j-k][this.count] = true;
						}
						this.count++;
					}
				}
				//初始化赢法编号
				for(let i = 0; i < this.count; i++){
					this.myWin[i] = 0;   //我的赢法
					this.OppoWin[i] = 0;  //对手的赢法
				}
			},
			//初始化下棋数组
			initChessArray:function (){
				for(let i = 0; i < 18; i ++){
					this.chess_Board[i] = [];
					for(let j = 0; j < 18; j++){
						this.chess_Board[i][j] = 0;//初始设置为0，表示没有棋子
					}
				}
			},
			//计算机下子
			oneStep:function (i, j){
				this.context.beginPath();
				this.context.arc(i*30+15, j*30+15, 13, 0, 2*Math.PI);//画圆
				this.context.closePath();
				this.context.fillStyle=this.myChessColor;
				this.context.fill();
			},
			isWin:function (i, j){
				for(let k=0; k<this.count; k ++){
					if(this.wins[i][j][k]){
						this.myWin[k] ++;
					}
if(this.myWin[k] === 5)
					{
						this.toastMsg('获胜！');
						this.isOver = true;
						//终局棋盘由服务端下发的 game-over（含连五坐标）统一绘制，保证胜负双方效果一致
						this.addSystemMessage('恭喜你 本局 获胜！');
					}
				}
			},
			//获取前时间
			getTimeNow(){
				let myDate = new Date();
				let year = myDate.getFullYear();   //获取系统的年；
				let nowMonth = myDate.getMonth() + 1;
				let strDate = myDate.getDate(); // 获取系统日，
				let hour = myDate.getHours(); //获取系统时，
				let minute = myDate.getMinutes(); //分
				let second = myDate.getSeconds(); //秒
				nowMonth = nowMonth<10?'0' + nowMonth:nowMonth;
				strDate = strDate < 10? '0' +strDate:strDate;
				hour = hour < 10? '0' +hour:hour;
				minute = minute < 10? '0' +minute:minute;
				second = second < 10? '0' +second:second;
				return year + '-' + nowMonth + '-' + strDate + ' ' + hour + ':' + minute+":"+second;
			},
//把用户可控文本（昵称等）转义后再拼进 HTML，避免 XSS
			escapeHtml(text){
				if(text === null || text === undefined){ return ''; }
				return String(text)
					.replace(/&/g,'&amp;')
					.replace(/</g,'&lt;')
					.replace(/>/g,'&gt;')
					.replace(/"/g,'&quot;')
					.replace(/'/g,'&#39;');
			},
//轻提示定位基准：大厅取大厅面板中心，对局/观战/巡查取棋盘中心，复盘取复盘棋盘中心；
			//这样无论左右两侧面板占多宽，提示都落在中间主内容的视觉中心。
			toastAnchorElement(){
				var visible = function(selector){
					var el = document.querySelector(selector);
					if(!el || el.offsetParent === null){ return null; }
					var rect = el.getBoundingClientRect();
					return (rect.width > 0 && rect.height > 0) ? el : null;
				};
				if(this.reviewReady){
					var review = visible('#reviewBoard');
					if(review){ return review; }
				}
				if(this.roomReady || this.spectateMode || this.adminViewOpen){
					var board = visible('#chessBoard');
					if(board){ return board; }
				}
				if(this.lobbyReady){
					var lobby = visible('.lobby');
					if(lobby){ return lobby; }
				}
				return visible('.middle');
			},
			//弹出框提示（duration 为展示毫秒数，默认 2500，不传或非法时用默认值）
			// ---------- 头像：点击更换 ----------
			chooseAvatar(){
				if(this.avatarBusy){ return; }
				var input = this.$refs && this.$refs.avatarInput;
				if(!input){ return; }
				input.value = '';
				input.click();
			},
			onAvatarPicked(e){
				var file = e && e.target && e.target.files && e.target.files[0];
				if(!file){ return; }
				if(!/^image\//.test(file.type || '')){ this.toastMsg('请选择图片文件'); return; }
				if(file.size > 8 * 1024 * 1024){ this.toastMsg('图片不能超过 8MB'); return; }
				this.uploadAvatar(file);
			},
			//压成 256x256 方图再上传：体积小，且各端裁剪方式一致
			shrinkAvatar(file){
				return new Promise(function(resolve, reject){
					var reader = new FileReader();
					reader.onerror = function(){ reject(new Error('读取图片失败')); };
					reader.onload = function(){
						var img = new Image();
						img.onerror = function(){ reject(new Error('图片解析失败')); };
						img.onload = function(){
							try{
								var size = 256;
								var canvas = document.createElement('canvas');
								canvas.width = size;
								canvas.height = size;
								var ctx = canvas.getContext('2d');
								//先铺白底：PNG 透明区域压成 JPEG 时不会变黑
								ctx.fillStyle = '#ffffff';
								ctx.fillRect(0, 0, size, size);
								var scale = Math.max(size / img.width, size / img.height);
								var w = img.width * scale, h = img.height * scale;
								ctx.drawImage(img, (size - w) / 2, (size - h) / 2, w, h);
								resolve(canvas.toDataURL('image/jpeg', 0.88));
							}catch(err){ reject(err); }
						};
						img.src = reader.result;
					};
					reader.readAsDataURL(file);
				});
			},
			uploadAvatar(file){
				var that = this;
				if(typeof axios === 'undefined'){ return; }
				this.avatarBusy = true;
				this.shrinkAvatar(file).then(function(dataUrl){
					return axios.post(that.url + '/avatarUploadPost', {
						id: String(that.user.id), token: that.user.token, image: dataUrl
					});
				}).then(function(response){
					var body = response.data || {};
					if(body.code === 200 && body.content && body.content.avatar){
						that.applyAvatar(body.content.avatar);
						that.toastMsg('头像已更新');
					}else if(body.code === 401){
						that.handleAuthExpired((body.content && body.content.reason) || 'expired');
					}else{
						that.toastMsg(body.message || '头像更新失败');
					}
				}).catch(function(){
					that.toastMsg('头像上传失败，请检查网络后重试');
				}).then(function(){
					that.avatarBusy = false;
				});
			},
			// 页面所在的基路径：/chess/index.html → /chess/，根部署（/index.html）→ /
			pageBasePath(){
				var path = (window.location && window.location.pathname) || '/';
				var cut = path.lastIndexOf('/');
				return cut <= 0 ? '/' : path.substring(0, cut + 1);
			},
			// 头像地址解析：服务端下发的是根绝对路径（/static/image/...）。
			// 本项目部署在站点子路径 /box/Chess/ 下，浏览器会把 /static/... 解析到域名根
			// 而不是 /box/Chess/，结果 404 —— 表现就是自己头像退回默认图、卡片上的头像变破图。
			// 这里把 /static/ 前缀统一改写到 avatarStaticBase（默认 /box/Chess/）；
			// 若服务端已按 fivechess.avatar.static-base 下发带前缀的地址，则原样使用，不重复拼接。
			// /avatar/{id} 属于后端接口，保持原样交给反代/跨域配置。
			resolveAvatarUrl(url){
				if(url === null || url === undefined){ return this.placeholderAvatar; }
				var text = String(url).trim();
				if(!text){ return this.placeholderAvatar; }
				//完整地址、协议相对地址、内联数据：原样使用
				if(/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(text) || /^data:/i.test(text) || /^blob:/i.test(text)){
					return text;
				}
				if(text.charAt(0) === '/'){
					if(text.indexOf('/static/') === 0){
						//已带配置前缀（服务端下发 /box/Chess/static/...）时不再叠加
						if(this.avatarStaticBase && text.indexOf(this.avatarStaticBase) === 0){
							return text;
						}
						var base = this.avatarStaticBase || this.pageBasePath();
						if(base.charAt(base.length - 1) !== '/'){ base += '/'; }
						return base + text.substring(1);
					}
					return text;
				}
				return text;
			},
			// 头像候选地址：按部署方式依次尝试，最后一个一定是占位图。
			// 顺序：配置的基路径 → 按页面实际路径推导（部署目录改名后自愈）→ 域名根 → 占位图。
			avatarCandidates(raw){
				var list = [];
				var text = (raw === null || raw === undefined) ? '' : String(raw).trim();
				if(text){
					var primary = this.resolveAvatarUrl(text);
					if(list.indexOf(primary) < 0){ list.push(primary); }
					if(text.indexOf('/static/') === 0){
						var pageBase = this.pageBasePath();
						if(pageBase !== '/'){
							var byPage = pageBase + text.substring(1);
							if(list.indexOf(byPage) < 0){ list.push(byPage); }
						}
					}
					if(text.charAt(0) === '/' && list.indexOf(text) < 0){ list.push(text); }
				}
				if(list.indexOf(this.placeholderAvatar) < 0){ list.push(this.placeholderAvatar); }
				return list;
			},
			// 卡片头像的失败标记键：同一房间同一侧、同一张图才算同一次失败
			roomAvatarKey(room, side){
				var avatar = room && (side === 1 ? room.playerAvatar1 : room.playerAvatar2);
				return (room && room.id) + '#' + side + '#' + (avatar || '');
			},
			// 房间卡片头像：座位为空显示加号；否则用该用户自己的头像（不再统一回退到本人头像）
			roomAvatarSrc(room, side){
				if(!room){ return this.placeholderAvatar; }
				if(side === 2 && String(room.playerId2) === '-1'){ return this.addImage; }
				var raw = side === 1 ? room.playerAvatar1 : room.playerAvatar2;
				var list = this.avatarCandidates(raw || this.avatar);
				var stage = this.roomAvatarStages[this.roomAvatarKey(room, side)] || 0;
				return list[Math.min(stage, list.length - 1)];
			},
			onRoomAvatarError(room, side){
				//单张头像取不到（用户没设、静态目录没同步、网络异常）时逐级降级，避免卡片出现破图
				if(!room){ return; }
				var raw = side === 1 ? room.playerAvatar1 : room.playerAvatar2;
				var list = this.avatarCandidates(raw || this.avatar);
				var key = this.roomAvatarKey(room, side);
				var cur = this.roomAvatarStages[key] || 0;
				var next = Math.min(cur + 1, list.length - 1);
				if(next === cur){ return; }
				this.$set(this.roomAvatarStages, key, next);
			},
			//写入页面的同时更新本地登录信息，刷新后无需等接口也能立即显示
			applyAvatar(url){
				if(!url){ return; }
				this.avatarStage = 0;
				this.avatarFallbackUsed = false;
				this.avatar = url;
				try{
					var saved = JSON.parse(localStorage.getItem('user') || '{}');
					saved.avatar = url;
					localStorage.setItem('user', JSON.stringify(saved));
				}catch(e){}
			},
			refreshAvatar(){
				var that = this;
				//测试桩环境没有 axios / 未登录时不发请求，避免初始化被打断
				if(!this.user || !this.user.id || !this.user.token || typeof axios === 'undefined'){ return; }
				try{
					axios.post(this.url + '/avatarPost', {
					id: String(this.user.id), token: this.user.token
					}).then(function(response){
						var body = response.data || {};
						if(body.code === 200 && body.content && body.content.avatar){
							that.applyAvatar(body.content.avatar);
						}
					}).catch(function(){});
				}catch(e){}
			},
			onAvatarError(){
				//头像加载失败（文件没部署、路径取不到、网络异常）时逐级降级，
				//最后一律落到中性占位图。这里绝不能用 ./static/image/avatar.jpg ——
				//那是管理员专属默认头像，普通用户一旦加载失败就会「变成管理员的样子」。
				var list = this.avatarCandidates(this.avatar);
				var cur = this.avatarStage || 0;
				var next = Math.min(cur + 1, list.length - 1);
				if(next === cur){ return; }
				this.avatarStage = next;
				this.avatarFallbackUsed = (next === list.length - 1);
			},
			toastMsg(msg, duration, centerOnBoard) {
				var showMs = (duration && duration > 0) ? duration : 2500;
				var that = this;
				if ($("#toastMessage").length) {  // 判断页面是否存在  存在的话删除
					$("#toastMessage").remove();
				}
				//用 DOM 文本节点组装提示内容：昵称等内容里的 HTML 不会被解析执行（防 XSS）
				var $toast = $('<div>', {id:'toastMessage', 'class':'toast-message'})
						.append($('<span>').text(msg === null || msg === undefined ? '' : String(msg)));
				$("body").append($toast); //再添加上去替代
				var toastMessage = $("#toastMessage");
				var node = toastMessage[0];
				var positionToast = function(){
					if(!node || !document.body.contains(node)){ return; }
					// 可视区边界：手机软键盘弹起时 layout viewport 不变，用 visualViewport 才能拿到真实可见范围
					var vv = window.visualViewport;
					var viewLeft = vv ? vv.offsetLeft : 0;
					var viewTop = vv ? vv.offsetTop : 0;
					var viewRight = vv ? (vv.offsetLeft + vv.width) : window.innerWidth;
					var viewBottom = vv ? (vv.offsetTop + vv.height) : window.innerHeight;
					var anchor = that.toastAnchorElement();
					var rect = anchor ? anchor.getBoundingClientRect() : null;
					var left, top;
					// 窄屏是纵向堆叠的长页面，滚到聊天区后棋盘/大厅锚点会整个滚出视口。
					// 此时若仍按锚点中心给 fixed 提示定位，坐标会是负数，提示被放到屏幕外（用户以为没提示）。
					// 锚点不可见时改为显示在可视区中间偏上，避开软键盘。
					var anchorVisible = rect && rect.bottom > viewTop && rect.top < viewBottom;
					if(rect && anchorVisible){
						left = rect.left + rect.width / 2;
						top = rect.top + rect.height / 2;
					}else{
						left = (viewLeft + viewRight) / 2;
						top = viewTop + (viewBottom - viewTop) * 0.4;
					}
					// 兜底钳制：任何情况下都让提示完整落在可视区内
					var halfW = (node.offsetWidth || 0) / 2;
					var halfH = (node.offsetHeight || 0) / 2;
					var pad = 12;
					left = Math.max(viewLeft + halfW + pad, Math.min(viewRight - halfW - pad, left));
					top = Math.max(viewTop + halfH + pad, Math.min(viewBottom - halfH - pad, top));
					$(node).css({
						left: left + "px",
						top: top + "px",
						marginLeft: "0px",
						transform: "translate(-50%, -50%)"
					});
				};
				//先按当前布局定位，再等视图切换后的 DOM 更新完成校正一次
				positionToast();
				if(this.$nextTick){ this.$nextTick(positionToast); }
				setTimeout(function () {    // 设置消失时间
					//只移除本次创建的提示，避免误删随后出现的新提示
					if (node && document.body.contains(node)) {
						$(node).remove();
					}
				}, showMs);
			},
			//延时函数
			sleep(time){
				return new Promise((resolve) => setTimeout(resolve,time));
			}

		}
	})
