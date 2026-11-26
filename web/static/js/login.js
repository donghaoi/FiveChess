/* login.html 页面脚本：登录/注册表单校验与请求、密码可见性切换与就地提示。 */
let url = getHttpUrl();

/* ===== 提交按钮的等待态 =====
   弱网或后端冷启动时一次登录可能要等十几秒，期间必须让用户看到「在动」，
   否则会反复点击（重复登录会让两次签发的令牌互相顶掉）。
   - 按钮：禁用 + 转圈 + 文案变「登录中…」
   - 提示：2 秒后才出现（正常网速根本看不到），10 秒后升级为「服务器可能在冷启动」，
     这条对 Render 免费实例的首访很有用（实测冷启动约 75 秒） */
var fcWaitTimers = {};

function fcSetSubmitting(btnId, waitId, busy, busyText){
    var btn = document.getElementById(btnId);
    if(btn){
        var label = btn.querySelector('.fc-btn-text');
        if(busy){
            //首次进入等待态时记住原标题，恢复时用，避免写死文案与 HTML 不一致
            if(!btn.getAttribute('data-idle-text')){
                btn.setAttribute('data-idle-text', label ? label.textContent : '');
            }
            if(label){ label.textContent = busyText; }
            btn.classList.add('is-loading');
            btn.setAttribute('aria-busy', 'true');
            btn.disabled = true;
        }else{
            if(label){
                var idle = btn.getAttribute('data-idle-text');
                if(idle){ label.textContent = idle; }
            }
            btn.classList.remove('is-loading');
            btn.removeAttribute('aria-busy');
            btn.disabled = false;
        }
    }
    if(!waitId){ return; }
    var wait = document.getElementById(waitId);
    var textEl = wait ? wait.querySelector('.fc-wait-text') : null;
    var timers = fcWaitTimers[waitId];
    if(timers){
        clearTimeout(timers.slow);
        clearTimeout(timers.cold);
        delete fcWaitTimers[waitId];
    }
    if(!busy){
        if(wait){ wait.classList.remove('show', 'is-cold'); }
        return;
    }
    fcWaitTimers[waitId] = {
        slow: setTimeout(function(){
            if(!wait){ return; }
            wait.classList.remove('is-cold');
            if(textEl){ textEl.textContent = '网络较慢，正在等待服务器响应'; }
            wait.classList.add('show');
        }, 2000),
        cold: setTimeout(function(){
            if(!wait || !textEl){ return; }
            //\n 由 CSS 的 white-space: pre-line 渲染成换行，长句按语义断成两行
            textEl.textContent = '服务器还在努力赶路中，请再给它一点点时间，\n不要离开我，马上就好……';
            wait.classList.add('is-cold');
        }, 10000)
    };
}

    let loginPage = document.getElementById('login');
    let registerPage = document.getElementById('register');

    registerPage.style.display = 'none';


    function toRegister(){
        loginPage.style.display = 'none';
        registerPage.style.display = 'block';
        //切面板时清掉另一个面板残留的红字，避免「注册成功」和上一次「账号或密码错误」同时出现
        clearFieldError('loginId');
        clearFieldError('loginPassword');
        panelTip('loginPanel', '');
    }
    function toLoginPage(){
        loginPage.style.display = 'block';
        registerPage.style.display = 'none';
        clearFieldError('registerId');
        clearFieldError('registerName');
        clearFieldError('registerPassword');
        panelTip('registerPanel', '');
    }
    //密码可见性切换：点击小眼睛在明文与密文之间切换
    function togglePassword(inputId, btn){
        let input = document.getElementById(inputId);
        let icon = btn.querySelector('i');
        if(input.type === 'password'){
            input.type = 'text';
            icon.className = 'eye slash icon';
            btn.setAttribute('title', '隐藏密码');
        }else{
            input.type = 'password';
            icon.className = 'eye icon';
            btn.setAttribute('title', '显示密码');
        }
    }
    //输入框就地提示（替代弹窗）：红框抖动 + 输入框下方文案
    function fieldError(inputId, message){
        var input = document.getElementById(inputId);
        if(input){
            var wrap = input.closest('.ui.input') || input.parentElement;
            if(wrap){
                wrap.classList.remove('field-error');
                void wrap.offsetWidth;
                wrap.classList.add('field-error');
            }
        }
        var tip = document.getElementById('tip-' + inputId);
        if(tip){
            if(message){ tip.textContent = message; tip.style.display = 'block'; }
            else{ tip.textContent = ''; tip.style.display = 'none'; }
        }
    }
    function clearFieldError(inputId){
        var input = document.getElementById(inputId);
        if(input){
            var wrap = input.closest('.ui.input') || input.parentElement;
            if(wrap){ wrap.classList.remove('field-error'); }
        }
        var tip = document.getElementById('tip-' + inputId);
        if(tip){ tip.textContent = ''; tip.style.display = 'none'; }
    }
    //面板级提示（如注册成功后），仅文字提示，不弹窗
    function panelTip(panelId, message, isError){
        var tip = document.getElementById('tip-' + panelId);
        if(!tip){ return; }
        if(message){
            tip.textContent = message;
            tip.style.color = isError ? '#e74c3c' : '#2e8b40';
            tip.style.display = 'block';
        }else{
            tip.textContent = '';
            tip.style.display = 'none';
        }
    }

    //管理员专用名称：账号与昵称都不允许使用（不区分大小写），与后端校验保持一致
    var RESERVED_ADMIN_NAMES = ['管理员', 'admin'];
    function isReservedAdminName(value){
        if(!value){ return false; }
        var lower = String(value).trim().toLowerCase();
        for(var i = 0; i < RESERVED_ADMIN_NAMES.length; i++){
            if(RESERVED_ADMIN_NAMES[i].toLowerCase() === lower){ return true; }
        }
        return false;
    }

    var registerSubmitting = false;
    function register(){
        //防止连点「提交」重复发起注册请求
        if(registerSubmitting){ return; }
        let registerId = document.querySelector('#registerId').value.trim();
        let registerName = document.querySelector('#registerName').value.trim();
        let registerPassword = document.querySelector('#registerPassword').value;
        clearFieldError('registerId');
        clearFieldError('registerName');
        clearFieldError('registerPassword');
        panelTip('registerPanel', '');
        //参数不符合规范时：直接提示规范范围（红框抖动）
        if(registerId === ''){ fieldError('registerId', '请输入账号（3–20 位）'); return; }
        if(registerId.indexOf(' ') !== -1){ fieldError('registerId', '账号不能包含空格'); return; }
        if(isReservedAdminName(registerId)){ fieldError('registerId', '该账号为管理员账号，禁止使用！'); return; }
        //长度上限与服务端保持一致（Controller.MAX_LOGIN_LENGTH / MAX_NAME_LENGTH），
        //否则前端放过的超长内容会被后端直接拒绝，用户填完才知道不行
        if(registerId.length < 3 || registerId.length > 12){ fieldError('registerId', '账号长度需为 3–12 位'); return; }
        if(registerName === ''){ fieldError('registerName', '请输入昵称（1–10 位）'); return; }
        if(registerName.indexOf(' ') !== -1){ fieldError('registerName', '昵称不能包含空格'); return; }
        if(isReservedAdminName(registerName)){ fieldError('registerName', '该昵称为管理员昵称，禁止使用！'); return; }
        if(registerName.length > 10){ fieldError('registerName', '昵称长度需为 1–10 位'); return; }
        if(registerPassword === ''){ fieldError('registerPassword', '请输入密码（6–50 位）'); return; }
        if(registerPassword.indexOf(' ') !== -1){ fieldError('registerPassword', '密码不能包含空格'); return; }
        if(registerPassword.length < 6 || registerPassword.length > 50){ fieldError('registerPassword', '密码长度需为 6–50 位'); return; }
        registerSubmitting = true;
        fcSetSubmitting('registerBtn', 'wait-register', true, '注册中…');
        axios.post(url+'/registerPost',{
            id:registerId,
            name:registerName,
            password: registerPassword
        }).then(function (response) {
            registerSubmitting = false;
            fcSetSubmitting('registerBtn', 'wait-register', false);
            if(response.data.code===200){
                //注册成功：只提示、不弹窗；自动切回登录面板并预填账号密码
                document.querySelector('#loginId').value = registerId;
                document.querySelector('#loginPassword').value = registerPassword;
                document.querySelector('#registerId').value = '';
                document.querySelector('#registerName').value = '';
                document.querySelector('#registerPassword').value = '';
                toLoginPage();
                //清掉上一次登录失败留下的红字，否则「注册成功」和「账号或密码错误」会同时显示
                clearFieldError('loginId');
                clearFieldError('loginPassword');
                panelTip('loginPanel', '注册成功，请点击「登录」进入游戏');
            }else{
                var msg = response.data.message || '注册失败';
                //管理员专用名称：服务端返回的是精确文案，直接原样显示在对应输入框下方，
                //不能被下面的「已被使用」映射改写成「已经被其他人使用」
                if(msg.indexOf('管理员') !== -1){
                    if(msg.indexOf('昵称') !== -1){ fieldError('registerName', msg); }
                    else{ fieldError('registerId', msg); }
                    return;
                }
                var taken = (msg.indexOf('已') !== -1 || msg.indexOf('使用') !== -1);
                if(msg.indexOf('昵称') !== -1 && taken){
                    fieldError('registerName', '昵称 ' + registerName + ' 已经被其他人使用了哦，换个试试');
                }else if((msg.indexOf('账号') !== -1 || msg.indexOf('账户') !== -1) && taken){
                    fieldError('registerId', '账号 ' + registerId + ' 已经被其他人使用了哦，换个试试');
                }else if(msg.indexOf('昵称') !== -1){
                    fieldError('registerName', msg);
                }else if(msg.indexOf('账号') !== -1 || msg.indexOf('账户') !== -1){
                    fieldError('registerId', msg);
                }else{
                    fieldError('registerPassword', msg);
                }
            }
        }).catch(function (e) {
            registerSubmitting = false;
            fcSetSubmitting('registerBtn', 'wait-register', false);
            console.log(e);
            panelTip('registerPanel', '网络连接失败，请检查服务是否可用后重试', true);
        })
    }
    var loginSubmitting = false;
    function login(){
        //防连点：两次登录会让服务端签发的两个令牌互相顶掉，
        //先返回的那次若后写入 localStorage，进首页就会立刻被判"已在别处登录"
        if(loginSubmitting){ return; }
        let loginId = document.querySelector('#loginId').value.trim();
        let password = document.querySelector('#loginPassword').value;
        clearFieldError('loginId');
        clearFieldError('loginPassword');
        panelTip('loginPanel', '');
        if(loginId === '' || password === ''){
            if(loginId === ''){ fieldError('loginId', '请输入账号'); }
            if(password === ''){ fieldError('loginPassword', '请输入密码'); }
            return;
        }
        loginSubmitting = true;
        fcSetSubmitting('loginBtn', 'wait-login', true, '登录中…');
        axios.post(url+'/loginPost',{
            login:loginId,
            password:password
        }).then(function (response) {
            if(response.data.code===200){
                //登录成功不弹提示，直接进入对局页
                localStorage.setItem('user',JSON.stringify(response.data.content));
                //记住用户名；勾选「记住密码」时才一并保存密码
                fcRememberUser(loginId, (response.data.content && response.data.content.name) || '', password,
                        !!(rememberInput && rememberInput.checked));
                //标记本次是刚刚登录，进入对局页时才提示「欢迎登录」（刷新不会提示）
                try{ sessionStorage.setItem('fcJustLoggedIn','1'); }catch(e){}
                window.location.href='index.html';
                //跳转期间保持「登录中…」：先恢复按钮会闪一下再消失，也等于又把按钮交还给用户
                return;
            }
            loginSubmitting = false;
            fcSetSubmitting('loginBtn', 'wait-login', false);
            var msg = response.data.message || '登录失败';
            if(msg.indexOf('密码') !== -1){ fieldError('loginPassword', msg); }
            else if(msg.indexOf('账号') !== -1 || msg.indexOf('账户') !== -1){ fieldError('loginId', msg); }
            else { fieldError('loginId', msg); }
        }).catch(function (e) {
            loginSubmitting = false;
            fcSetSubmitting('loginBtn', 'wait-login', false);
            console.log(e);
            panelTip('loginPanel', '网络连接失败，请检查服务是否可用后重试', true);
        })
    }

    //输入时自动清除该输入框的错误提示
    ['loginId','loginPassword','registerId','registerName','registerPassword'].forEach(function(fieldId){
        var el = document.getElementById(fieldId);
        if(el){ el.addEventListener('input', function(){ clearFieldError(fieldId); }); }
    });

    //会话失效提示：对局页（或页面打开时的登录态探测）会写入 sessionStorage，
    //这里在页面加载后立即弹出唯一一个说明原因的提示，不需要用户先点任何东西
    (function(){
        try{
            var raw = sessionStorage.getItem('fcLoginNotice');
            if(!raw){ return; }
            sessionStorage.removeItem('fcLoginNotice');
            var info = JSON.parse(raw) || {};
            var title = info.title || '登录提示';
            var content = info.content || '请重新登录后继续操作。';
            var okText = info.ok || '知道了';
            if(window.jQuery && jQuery.alert){
                jQuery.alert({title:title, content:content, confirmButton:okText});
            }else{
                window.alert(title + '\n' + content);
            }
        }catch(e){}
    })();

    /* ===== 已登录账号记忆 + 记住密码 =====
       账号默认自动记住（下次打开登录页自动填上次用的账号，点右侧箭头可查看/切换/删除）；
       密码只在该账号勾选「记住密码」并登录成功后才保存，切换未勾选的账号不会自动填密码。
       密码在本地做一层混淆后存放，仅避免明文裸露，不能替代系统账号安全。 */
    var FC_USERS_KEY = 'fcLoginUsers';
    var FC_USERS_MAX = 8;
    var FC_MASK_KEY = 'fc-five-chess-2024';
    var FC_AVATAR_COLORS = ['#2185d0', '#21ba45', '#f2711c', '#a333c8', '#00a3a3', '#e061a1'];

    var accountMenu = document.getElementById('accountMenu');
    var accountToggle = document.getElementById('accountToggle');
    var rememberInput = document.getElementById('rememberPassword');
    var loginIdInput = document.getElementById('loginId');
    var loginPasswordInput = document.getElementById('loginPassword');
    var accountWrap = accountMenu && accountMenu.parentElement;
    var accountMenuOpen = false;

    //本地混淆：encodeURIComponent 后逐字符与固定串异或，再 base64；解码为其逆过程
    function fcMask(text){
        try{
            var s = encodeURIComponent(String(text));
            var out = '';
            for(var i = 0; i < s.length; i++){
                out += String.fromCharCode(s.charCodeAt(i) ^ FC_MASK_KEY.charCodeAt(i % FC_MASK_KEY.length));
            }
            return 'v1:' + btoa(out);
        }catch(e){
            return '';
        }
    }
    function fcUnmask(value){
        try{
            if(!value || value.indexOf('v1:') !== 0){ return ''; }
            var raw = atob(value.slice(3));
            var s = '';
            for(var i = 0; i < raw.length; i++){
                s += String.fromCharCode(raw.charCodeAt(i) ^ FC_MASK_KEY.charCodeAt(i % FC_MASK_KEY.length));
            }
            return decodeURIComponent(s);
        }catch(e){
            return '';
        }
    }
    function fcLoadUsers(){
        try{
            var list = JSON.parse(localStorage.getItem(FC_USERS_KEY) || '[]');
            if(!Array.isArray(list)){ return []; }
            return list.filter(function(u){ return u && typeof u.login === 'string' && u.login !== ''; });
        }catch(e){
            return [];
        }
    }
    function fcStoreUsers(list){
        try{
            localStorage.setItem(FC_USERS_KEY, JSON.stringify(list.slice(0, FC_USERS_MAX)));
        }catch(e){}
    }
    function fcFindUser(login){
        var key = String(login || '').trim();
        if(key === ''){ return null; }
        var list = fcLoadUsers();
        for(var i = 0; i < list.length; i++){
            if(list[i].login === key){ return list[i]; }
        }
        return null;
    }
    //登录成功后记录/更新该账号：默认记住用户名，勾选记住密码才保存密码（取消勾选会清掉已存的密码）
    function fcRememberUser(login, name, password, remember){
        var key = String(login || '').trim();
        if(key === ''){ return; }
        var list = fcLoadUsers();
        var item = null;
        var rest = [];
        for(var i = 0; i < list.length; i++){
            if(list[i].login === key){ item = list[i]; } else { rest.push(list[i]); }
        }
        if(!item){ item = { login: key }; }
        item.name = name || item.name || '';
        item.time = Date.now();
        item.remember = !!remember;
        if(remember && password){ item.password = fcMask(password); }
        else { delete item.password; }
        rest.unshift(item);
        fcStoreUsers(rest);
    }
    //移除某个账号（含已保存的密码）
    function fcRemoveUser(login){
        var key = String(login || '').trim();
        var list = fcLoadUsers().filter(function(u){ return u.login !== key; });
        fcStoreUsers(list);
    }
    function fcEscapeHtml(text){
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function fcAvatarText(login){
        var text = String(login || '').trim();
        return text ? text.charAt(0).toUpperCase() : '?';
    }
    function fcAvatarColor(login){
        var text = String(login || '');
        var sum = 0;
        for(var i = 0; i < text.length; i++){ sum += text.charCodeAt(i); }
        return FC_AVATAR_COLORS[sum % FC_AVATAR_COLORS.length];
    }
    function fcAgoText(ts){
        var diff = Date.now() - (ts || 0);
        if(!ts || diff < 0){ return ''; }
        if(diff < 60 * 1000){ return '刚刚'; }
        if(diff < 60 * 60 * 1000){ return Math.floor(diff / 60000) + ' 分钟前'; }
        if(diff < 24 * 60 * 60 * 1000){ return Math.floor(diff / 3600000) + ' 小时前'; }
        if(diff < 7 * 24 * 60 * 60 * 1000){ return Math.floor(diff / 86400000) + ' 天前'; }
        var d = new Date(ts);
        return (d.getMonth() + 1) + '月' + d.getDate() + '日';
    }
    function fcCurrentLogin(){
        return loginIdInput ? String(loginIdInput.value || '').trim() : '';
    }
    function fcClosest(node, className){
        while(node && node !== document){
            if(node.classList && node.classList.contains(className)){ return node; }
            node = node.parentElement;
        }
        return null;
    }
    //渲染账号下拉列表：昵称/最近使用时间做副标题，当前账号打勾，右侧 × 删除
    function fcRenderUsers(){
        if(!accountMenu){ return; }
        var list = fcLoadUsers();
        if(!list.length){
            accountMenu.innerHTML = '<div class="fc-user-empty">还没有登录过的账号</div>';
            return;
        }
        var active = fcCurrentLogin();
        var html = '';
        for(var i = 0; i < list.length; i++){
            var u = list[i];
            var isActive = (u.login === active);
            var sub = fcEscapeHtml(u.name || '');
            var ago = fcAgoText(u.time);
            if(ago){ sub = sub ? (sub + ' · ' + ago) : ago; }
            html += '<div class="fc-user-item' + (isActive ? ' active' : '') + '" data-login="' + fcEscapeHtml(u.login)
                + '" role="option" aria-selected="' + (isActive ? 'true' : 'false') + '">'
                + '<span class="fc-user-avatar" style="background:' + fcAvatarColor(u.login) + '">' + fcEscapeHtml(fcAvatarText(u.login)) + '</span>'
                + '<span class="fc-user-meta"><span class="fc-user-name">' + fcEscapeHtml(u.login) + '</span>'
                + '<span class="fc-user-sub">' + sub + '</span></span>'
                + '<span class="fc-user-check">✓</span>'
                + '<span class="fc-user-del" data-del="1" title="移除该账号">×</span>'
                + '</div>';
        }
        accountMenu.innerHTML = html;
    }
    function fcOpenMenu(){
        if(!accountMenu || !accountToggle || accountMenuOpen){ return; }
        if(!fcLoadUsers().length){ return; }
        fcRenderUsers();
        accountMenu.classList.add('open');
        accountToggle.classList.add('open');
        accountToggle.setAttribute('aria-expanded', 'true');
        accountMenuOpen = true;
    }
    function fcCloseMenu(){
        if(!accountMenu || !accountMenuOpen){ return; }
        accountMenu.classList.remove('open');
        accountToggle.classList.remove('open');
        accountToggle.setAttribute('aria-expanded', 'false');
        accountMenuOpen = false;
    }
    //选中某个账号：填账号；只有该账号勾选过记住密码才自动填密码，否则清空密码框
    function fcApplyUser(user){
        if(!user || !loginIdInput){ return; }
        loginIdInput.value = user.login;
        var remembered = !!(user.remember && user.password);
        if(loginPasswordInput){
            loginPasswordInput.value = remembered ? fcUnmask(user.password) : '';
        }
        if(rememberInput){ rememberInput.checked = !!user.remember; }
        clearFieldError('loginId');
        clearFieldError('loginPassword');
        panelTip('loginPanel', '');
        fcCloseMenu();
    }

    if(accountToggle){
        accountToggle.addEventListener('click', function(e){
            e.preventDefault();
            e.stopPropagation();
            if(accountMenuOpen){ fcCloseMenu(); } else { fcOpenMenu(); }
        });
    }
    if(loginIdInput){
        //点账号框也能展开（和点箭头同效），但不在 focus 时自动弹，避免用键盘编辑时被打断
        loginIdInput.addEventListener('click', function(){ fcOpenMenu(); });
        //一旦开始手动输入就收起列表，不挡住输入内容
        loginIdInput.addEventListener('input', function(){ fcCloseMenu(); });
    }
    if(accountMenu){
        accountMenu.addEventListener('click', function(e){
            e.stopPropagation();
            var delBtn = fcClosest(e.target, 'fc-user-del');
            var itemEl = fcClosest(e.target, 'fc-user-item');
            if(!itemEl){ return; }
            var login = itemEl.getAttribute('data-login') || '';
            if(delBtn){
                fcRemoveUser(login);
                //删掉的正是当前正在填的账号：清空密码与勾选，避免留下已不可用的密码
                if(login === fcCurrentLogin()){
                    if(loginPasswordInput){ loginPasswordInput.value = ''; }
                    if(rememberInput){ rememberInput.checked = false; }
                }
                fcRenderUsers();
                return;
            }
            fcApplyUser(fcFindUser(login));
        });
    }
    document.addEventListener('click', function(e){
        if(!accountMenuOpen){ return; }
        if(accountWrap && accountWrap.contains(e.target)){ return; }
        fcCloseMenu();
    });
    document.addEventListener('keydown', function(e){
        if(e.key === 'Escape' || e.keyCode === 27){ fcCloseMenu(); }
    });

    //打开登录页时自动带出上次使用的账号；该账号勾选过记住密码才自动填密码
    (function fcRestoreLastUser(){
        var list = fcLoadUsers();
        if(!list.length){ return; }
        var last = list[0];
        if(loginIdInput && !loginIdInput.value){ loginIdInput.value = last.login; }
        var remembered = !!(last.remember && last.password);
        if(rememberInput){ rememberInput.checked = !!last.remember; }
        if(remembered && loginPasswordInput && !loginPasswordInput.value){
            loginPasswordInput.value = fcUnmask(last.password);
        }
    })();
