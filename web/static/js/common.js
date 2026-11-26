/* index.html 页面通用脚本：登录态校验、退出登录、系统与会话提示、房间密码弹窗、通用确认弹窗。 */

/* ===== 会话失效文案表：按「原因」统一措辞，避免各处的模糊提示 =====
   服务端会带回明确原因（restart / superseded / loggedout / missing），
   提示里说清「为什么失效」「会有什么影响」「下一步该做什么」。 */
	var FC_NOTICE_TEXT = {
		//账号在另一台设备/另一个浏览器窗口登录，旧连接被顶掉
		superseded:{
			title:'该账号已在其他设备登录',
			content:'你的账号刚刚在另一台设备或另一个浏览器窗口中登录。同一账号同时只允许一处在线，本页的登录已被迫退出。'
				+ '如果这不是你本人操作，建议立即联系管理员处理 https://blog.donghao.ltd/，以防账号被盗用。',
			ok:'重新登录'
		},
		//服务重启/发布更新：服务端令牌是内存态，重启即全部失效
		restart:{
			title:'服务已重启，请重新登录',
			content:'系统刚完成重启或发布更新，服务端保存的登录状态已被清空——这不是账号异常，也不是密码错误。'
				+ '重启前所在的房间与对局已结束，重新登录后即可继续游戏。',
			ok:'重新登录'
		},
		//服务端已注销本次登录
		loggedout:{
			title:'登录状态已失效',
			content:'服务端已没有本次登录的记录（可能是在其他地方点过「退出登录」，或登录信息已被注销）。请重新登录后继续操作。',
			ok:'重新登录'
		},
		//没有登录信息
		missing:{
			title:'尚未登录',
			content:'没有检测到有效的登录信息，请先登录再进入游戏。',
			ok:'去登录'
		},
		//通用失效（令牌过期等）
		expired:{
			title:'登录状态已过期',
			content:'长时间未使用，登录状态已自动过期。请重新登录后继续游戏；过期期间所在的房间与对局已结束。',
			ok:'重新登录'
		},
		banned:{
			title:'账号已被封禁',
			content:'该账号已被管理员封号，无法继续登录或游戏。如有疑问请移步 https://blog.donghao.ltd/ 联系管理员。',
			ok:'知道了'
		},
		//连不上服务器（未确认登录态是否失效）
		network:{
			title:'无法连接服务器',
			content:'与服务器的连接已断开，可能是网络不稳定或服务正在维护。请检查网络后重试；如果服务刚重启过，需要重新登录。',
			ok:'知道了'
		}
	};
	//取某个原因的提示文案；second 既可以是旧的 content 字符串，也可以是覆盖字段对象
	function fcNoticeOf(key, second){
		var base = FC_NOTICE_TEXT[key] || null;
		var text = base ? {title:base.title, content:base.content, ok:base.ok} : {title:String(key||'提示'), content:'', ok:'知道了'};
		if(typeof second === 'string'){
			text.content = second;
		}else if(second && typeof second === 'object'){
			if(second.title){ text.title = second.title; }
			if(second.content){ text.content = second.content; }
			if(second.ok){ text.ok = second.ok; }
		}
		return text;
	}
	function fcIsNoticeKey(value){
		return !!(value && FC_NOTICE_TEXT[value]);
	}

	//刷新时若上一次会话已失效（用户还没点“重新登录”就刷新），直接回登录页并带上同样的原因；
	//避免在已失效的页面上再次建立连接、再弹一次窗。
	(function(){
		try{
			if(sessionStorage.getItem('fcSessionLost') === '1'){
				var lostReason = sessionStorage.getItem('fcSessionLostReason') || 'expired';
				sessionStorage.removeItem('fcSessionLost');
				sessionStorage.removeItem('fcSessionLostReason');
				var lostText = fcNoticeOf(lostReason);
				sessionStorage.setItem('fcLoginNotice', JSON.stringify({
					reason:lostReason,
					title:lostText.title,
					content:lostText.content,
					ok:lostText.ok
				}));
				localStorage.removeItem('user');
				sessionStorage.removeItem('fcViewState');
				window.location.replace('./login.html');
			}
		}catch(e){}
	})();

	let user = localStorage.getItem('user');
	if(user===null||user===undefined){
		console.log('请先登录！');
		window.location.href = 'login.html';
	}

	function logout(){
		//退出登录确认：使用与邀请弹窗一致的主题弹窗（jquery-confirm），替代原生 confirm
		$.confirm({
			title:'退出登录',
			content:'是否退出登录？',
			confirmButton:'退出登录',
			cancelButton:'取消',
confirm:function(){
				//退出登录时同步注销服务端令牌，避免旧令牌在退出后仍然有效
				try{
					var current = JSON.parse(localStorage.getItem('user') || 'null');
					if(current && current.token){
						fetch(getHttpUrl() + '/logoutPost', {
							method:'POST',
							headers:{'Content-Type':'application/json'},
							body:JSON.stringify({token:current.token}),
							keepalive:true
						}).catch(function(){});
					}
				}catch(e){}
				//主动退出：关闭页面/连接时不要再弹“服务器已断开”
				window.__fcLeaving = true;
				localStorage.removeItem('user');
				//退出登录后清理页面恢复状态，避免下次登录时自动回到上次的房间/历史/复盘视图
				try{ window.sessionStorage.removeItem('fcViewState'); }catch(e){}
				window.location.href = 'login.html';
			}
		});
	}

	//服务器连接/被迫下线等系统提示：使用与邀请、退出登录一致的主题弹窗（jquery-confirm），替代浏览器原生 alert
	function serverNotice(title, content, onOk, okText){
		if(typeof $ === 'undefined' || !$.alert){
			//极端情况下弹窗库不可用时兜底为控制台日志，并保证回调仍会执行
			console.log(title + '：' + content);
			if(typeof onOk === 'function'){ onOk(); }
			return;
		}
		try{
			$.alert({
				title: title,
				content: content,
				confirmButton: okText || '知道了',
				confirm: function(){ if(typeof onOk === 'function'){ onOk(); } }
			});
		}catch(e){
			if(typeof onOk === 'function'){ onOk(); }
		}
	}

	//会话失效统一提示：整页只允许出现一个弹窗；点击按钮后才关闭并回到登录页。
	//第一个参数可以是原因（superseded / restart / loggedout / missing / expired / banned），
	//也可以是直接给的中文标题（兼容旧调用）。
	function sessionNotice(reasonOrTitle, content){
		if(window.__fcSessionNoticeShown){ return; }
		window.__fcSessionNoticeShown = true;
		var reason = fcIsNoticeKey(reasonOrTitle) ? reasonOrTitle : null;
		var text = fcNoticeOf(reasonOrTitle, content);
		try{
			sessionStorage.setItem('fcSessionLost','1');
			if(reason){ sessionStorage.setItem('fcSessionLostReason', reason); }
		}catch(e){}
		serverNotice(text.title, text.content, function(){
			try{
				sessionStorage.removeItem('fcSessionLost');
				sessionStorage.removeItem('fcSessionLostReason');
				sessionStorage.removeItem('fcLoginNotice');
				sessionStorage.removeItem('fcViewState');
			}catch(e){}
			try{ localStorage.removeItem('user'); }catch(e){}
			window.location.href = './login.html';
		}, text.ok);
	}

	//直接带提示跳登录页：用于页面刚加载就发现会话失效（刷新后不会在旧页面再弹窗）
	function redirectLoginWithNotice(reasonOrTitle, content){
		if(window.__fcLoginRedirecting){ return; }
		window.__fcLoginRedirecting = true;
		var reason = fcIsNoticeKey(reasonOrTitle) ? reasonOrTitle : null;
		var text = fcNoticeOf(reasonOrTitle, content);
		try{
			sessionStorage.setItem('fcLoginNotice', JSON.stringify({
				reason:reason || '',
				title:text.title,
				content:text.content,
				ok:text.ok
			}));
			sessionStorage.removeItem('fcSessionLost');
			sessionStorage.removeItem('fcSessionLostReason');
			sessionStorage.removeItem('fcViewState');
			localStorage.removeItem('user');
		}catch(e){}
		window.location.replace('./login.html');
	}

	//登录态探测：返回 {valid:true|false|null, reason:'ok|restart|superseded|loggedout|missing|network'}
	//valid=null 表示探测本身失败（服务不可达），不能据此认定登录失效。
	function fcCheckSession(callback){
		var done = false;
		var guard = null;
		function finish(result){
			if(done){ return; }
			done = true;
			if(guard){ clearTimeout(guard); }
			callback(result || {});
		}
		//兜底超时：网络黑洞时请求可能永远不返回，6 秒后按“探测失败”处理并继续后续流程
		guard = setTimeout(function(){ finish({valid:null, reason:'network'}); }, 6000);
		var current = null;
		try{ current = JSON.parse(localStorage.getItem('user') || 'null'); }catch(e){}
		if(!current || !current.id || !current.token){
			finish({valid:false, reason:'missing'});
			return;
		}
		var payload = {id:String(current.id), token:current.token};
		var url = getHttpUrl() + '/sessionCheck';
		try{
			if(typeof axios !== 'undefined' && axios.post){
				axios.post(url, payload, {timeout:5000}).then(function(response){
					var body = (response && response.data) || {};
					var content = body.content || {};
					var valid = content.valid === true;
					finish({valid:valid, reason: valid ? 'ok' : (content.reason || 'expired'), bootId:content.bootId});
				}).catch(function(){
					finish({valid:null, reason:'network'});
				});
				return;
			}
			fetch(url, {
				method:'POST',
				headers:{'Content-Type':'application/json'},
				body:JSON.stringify(payload)
			}).then(function(r){ return r.json(); }).then(function(body){
				var content = (body && body.content) || {};
				var valid = content.valid === true;
				finish({valid:valid, reason: valid ? 'ok' : (content.reason || 'expired'), bootId:content.bootId});
			}).catch(function(){
				finish({valid:null, reason:'network'});
			});
		}catch(e){
			finish({valid:null, reason:'network'});
		}
	}

	//页面一打开就校验一次登录态：失效时立刻带原因回登录页，
	//不用先建立 WebSocket、也不需要用户点任何按钮（浏览器关闭后重开、服务重启过都能第一时间提示）。
	(function(){
		try{
			if(window.__fcLeaving){ return; }
			var raw = localStorage.getItem('user');
			if(!raw){ return; }
			var cached = JSON.parse(raw);
			if(!cached || !cached.id || !cached.token){ return; }
			window.__fcPrechecking = true;
			fcCheckSession(function(result){
				window.__fcPrechecking = false;
				if(result && result.valid === false){
					redirectLoginWithNotice(result.reason || 'expired');
				}
			});
		}catch(e){
			window.__fcPrechecking = false;
		}
	})();

//加入私人房间时保留密码弹窗，直到服务端确认密码正确并成功进房
	var roomPasswordDialog = null;
	var roomPasswordRoomId = null;
	function setRoomPasswordError(message){
		var error = document.getElementById('fc-room-password-error');
		if(error){ error.textContent = message || ''; }
		var input = document.getElementById('fc-room-password-input');
		if(input){ input.focus(); input.select(); }
	}
	function closeRoomPasswordDialog(){
		if(roomPasswordDialog){
			try{ roomPasswordDialog.close(); }catch(e){}
		}
		roomPasswordDialog = null;
		roomPasswordRoomId = null;
	}
	//房间密码输入弹窗：用于设置私人房间密码或加入私人房间时输入密码
	//options.keepOpenOnConfirm=true 时先保持弹窗，由服务端结果决定关闭或就地提示错误
	function askRoomPassword(title, hint, onConfirm, options){
		options = options || {};
		if(typeof $ === 'undefined' || !$.confirm){
			//极端情况下弹窗库不可用时兜底为原生输入框，保证功能仍可用
			var fallback = window.prompt(title + '\n' + hint, '');
			if(fallback !== null && typeof onConfirm === 'function'){ onConfirm(fallback); }
			return;
		}
		try{
			var dialog = $.confirm({
				title: title,
				content: '<div class="room-password-dialog">'
					+ '<p style="margin:0 0 8px;color:#666;font-size:13px;line-height:1.6">' + hint + '</p>'
					+ '<div class="room-password-field" style="position:relative">'
					+ '<input id="fc-room-password-input" type="password" maxlength="20" autocomplete="new-password" '
					+ 'style="width:100%;padding:9px 36px 9px 11px;border:1px solid #c8d6e2;border-radius:4px;font-size:14px;box-sizing:border-box" '
					+ 'placeholder="请输入 4-20 位密码">'
					+ '<button type="button" class="room-password-eye" title="显示密码" aria-label="显示密码" '
					+ 'style="position:absolute;right:4px;top:50%;transform:translateY(-50%);width:28px;height:28px;padding:0;border:none;background:transparent;color:#888;cursor:pointer;line-height:1">'
					+ '<i class="eye icon"></i>'
					+ '</button>'
					+ '</div>'
					+ '<div id="fc-room-password-error" style="color:#e74c3c;font-size:12px;line-height:18px;min-height:18px;margin-top:5px"></div>'
					+ '</div>',
				confirmButton: '确定',
				cancelButton: '取消',
				onContentReady: function(){
					var input = document.getElementById('fc-room-password-input');
					if(input){
						input.focus();
						input.addEventListener('keydown', function(e){
							if(e.key === 'Enter'){
								e.preventDefault();
								// jquery-confirm 当前版本确认按钮不带 .btn-confirm 类，按文案查找兼容
								var btn = [...document.querySelectorAll('.jconfirm button')]
									.find(function(b){ return (b.innerText || '').trim() === '确定'; });
								if(btn){ btn.click(); }
							}
						});
					}
				},
				confirm: function(){
					var input = document.getElementById('fc-room-password-input');
					var error = document.getElementById('fc-room-password-error');
					var value = input ? input.value.trim() : '';
					if(value.length < 4 || value.length > 20){
						if(error){ error.textContent = '房间密码长度必须为 4-20 位'; }
						if(input){ input.focus(); }
						return false;
					}
					if(options.keepOpenOnConfirm){
						roomPasswordDialog = dialog;
						roomPasswordRoomId = options.roomId || null;
						setRoomPasswordError('');
						if(typeof onConfirm === 'function'){ onConfirm(value); }
						//保持弹窗：密码错误时由 join-room-fail 在输入框下方提示，正确时由 join-room-success 关闭
						return false;
					}
					if(typeof onConfirm === 'function'){ onConfirm(value); }
				},
				cancel: function(){
					roomPasswordDialog = null;
					roomPasswordRoomId = null;
				}
			});
}catch(e){
			var value = window.prompt(title + '\n' + hint, '');
			if(value !== null && typeof onConfirm === 'function'){ onConfirm(value); }
		}
	}

	//房间密码输入框右侧的小眼睛：用文档级事件委托，确保弹窗内容后挂载时点击仍然生效
	document.addEventListener('mousedown', function(e){
		var eye = e.target && e.target.closest ? e.target.closest('.room-password-eye') : null;
		if(eye){ e.preventDefault(); }
	});
	document.addEventListener('click', function(e){
		var eye = e.target && e.target.closest ? e.target.closest('.room-password-eye') : null;
		if(!eye){ return; }
		e.preventDefault();
		e.stopPropagation();
		var field = eye.closest('.room-password-field');
		var input = field ? field.querySelector('#fc-room-password-input') : document.getElementById('fc-room-password-input');
		if(!input){ return; }
		var icon = eye.querySelector('i');
		if(input.type === 'password'){
			input.type = 'text';
			if(icon){ icon.className = 'eye slash icon'; }
			eye.title = '隐藏密码';
			eye.setAttribute('aria-label', '隐藏密码');
		}else{
			input.type = 'password';
			if(icon){ icon.className = 'eye icon'; }
			eye.title = '显示密码';
			eye.setAttribute('aria-label', '显示密码');
		}
		input.focus();
	});

	//二次确认：使用与退出登录一致的 jquery-confirm 主题弹窗，替代浏览器原生 confirm
	function confirmDialog(title, content, confirmText, cancelText, onConfirm, onCancel){
		if(typeof $ === 'undefined' || !$.confirm){
			//弹窗库不可用时兜底：直接执行确认逻辑，避免流程卡死
			if(typeof onConfirm === 'function'){ onConfirm(); }
			return;
		}
		try{
			$.confirm({
				title: title,
				content: content,
				confirmButton: confirmText || '确定',
				cancelButton: cancelText || '取消',
				confirm: function(){ if(typeof onConfirm === 'function'){ onConfirm(); } },
				cancel: function(){ if(typeof onCancel === 'function'){ onCancel(); } }
			});
		}catch(e){
			if(typeof onConfirm === 'function'){ onConfirm(); }
		}
	}
