// 后端服务地址（主机:端口），默认 127.0.0.1:8010，可通过 window.FIVECHESS_SERVER 覆盖
function getServerHost(){
    return window.FIVECHESS_SERVER || '127.0.0.1:8010';
}

// 接口基地址：默认跟随页面协议，https 页面自动使用 https（避免混合内容被浏览器拦截）
// 也可通过 window.FIVECHESS_PROTOCOL = 'http' | 'https' 强制指定
function getHttpUrl(){
    var protocol = window.FIVECHESS_PROTOCOL || (location.protocol === 'https:' ? 'https' : 'http');
    return protocol + '://' + getServerHost();
}

// WebSocket 地址：与接口协议保持一致（http -> ws，https -> wss）
function getWsUrl(){
    var protocol = window.FIVECHESS_PROTOCOL
        ? (window.FIVECHESS_PROTOCOL === 'https' ? 'wss' : 'ws')
        : (location.protocol === 'https:' ? 'wss' : 'ws');
    return protocol + '://' + getServerHost();
}

// 兼容旧调用：仅返回 主机:端口
function getUrl(){
    return getServerHost();
}