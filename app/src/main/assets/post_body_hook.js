/**
 * ZT Browser POST Body 劫持脚本
 *
 * 劫持 fetch 和 XMLHttpRequest，在请求实际发出前把 body 经
 * AndroidBodyCollector.submitBody() 传给原生层。
 * 原生层 shouldInterceptRequest 时按 (method,url) 取出 body 再用 OkHttp 代理转发。
 *
 * 注意：此脚本必须在页面任何应用 JS 执行前注入（onPageStarted 时 evaluateJavascript）。
 */
(function() {
    'use strict';

    if (window.__ztBodyHooked) return;
    window.__ztBodyHooked = true;

    function toArrayBuffer(base64) {
        // base64 → ArrayBuffer（用于 fetch body 是 Blob/ArrayBuffer 时也走 base64 通道）
        var binary = atob(base64);
        var len = binary.length;
        var bytes = new Uint8Array(len);
        for (var i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
        return bytes.buffer;
    }

    function bodyToBase64(body) {
        // 把各种 body 形式统一转 Base64 字符串
        if (body == null || body === undefined) return '';
        if (typeof body === 'string') {
            // UTF-8 安全：用 encodeURIComponent + unescape 转 btoa 兼容
            try { return btoa(unescape(encodeURIComponent(body))); }
            catch(e) { return ''; }
        }
        if (body instanceof ArrayBuffer) {
            var bytes = new Uint8Array(body);
            var bin = '';
            for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
            return btoa(bin);
        }
        if (body instanceof Uint8Array) {
            var bin2 = '';
            for (var j = 0; j < body.length; j++) bin2 += String.fromCharCode(body[j]);
            return btoa(bin2);
        }
        if (body instanceof Blob) {
            // Blob 是异步的，这里用同步方式无法处理——跳过（fetch 带 Blob body 较少）
            return '';
        }
        if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
            try { return btoa(unescape(encodeURIComponent(body.toString()))); }
            catch(e) { return ''; }
        }
        if (typeof FormData !== 'undefined' && body instanceof FormData) {
            // FormData 无法同步序列化，跳过
            return '';
        }
        // 兜底：尝试 toString
        try { return btoa(unescape(encodeURIComponent(String(body)))); }
        catch(e) { return ''; }
    }

    function safeSubmit(method, url, contentType, body) {
        try {
            var b64 = bodyToBase64(body);
            AndroidBodyCollector.submitBody(method, url, contentType || '', b64);
        } catch(e) {
            // 原生接口可能未注入，静默失败
        }
    }

    // ======== 劫持 fetch ========
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function(input, init) {
            try {
                var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                var method = (init && init.method) ? init.method.toUpperCase() : 'GET';
                if (method === 'POST' || method === 'PUT' || method === 'PATCH') {
                    var headers = (init && init.headers) ? init.headers : {};
                    var ct = '';
                    if (headers instanceof Headers) {
                        ct = headers.get('Content-Type') || '';
                    } else if (typeof headers === 'object') {
                        ct = headers['Content-Type'] || headers['content-type'] || '';
                    }
                    var body = init ? init.body : null;
                    safeSubmit(method, url, ct, body);
                }
            } catch(e) {}
            return origFetch.apply(this, arguments);
        };
    }

    // ======== 劫持 XMLHttpRequest ========
    var OrigXHR = window.XMLHttpRequest;
    if (OrigXHR) {
        var origOpen = OrigXHR.prototype.open;
        var origSend = OrigXHR.prototype.send;
        var origSetHeader = OrigXHR.prototype.setRequestHeader;

        OrigXHR.prototype.open = function(method, url) {
            this.__ztMethod = (method || 'GET').toUpperCase();
            this.__ztUrl = url || '';
            this.__ztContentType = '';
            return origOpen.apply(this, arguments);
        };

        OrigXHR.prototype.setRequestHeader = function(name, value) {
            if (name && name.toLowerCase() === 'content-type') {
                this.__ztContentType = value || '';
            }
            return origSetHeader.apply(this, arguments);
        };

        OrigXHR.prototype.send = function(body) {
            if (this.__ztMethod === 'POST' || this.__ztMethod === 'PUT' || this.__ztMethod === 'PATCH') {
                safeSubmit(this.__ztMethod, this.__ztUrl, this.__ztContentType, body);
            }
            return origSend.apply(this, arguments);
        };
    }

    // 标记注入完成（调试用）
    console.log('[ZTBrowser] POST body hook installed');
})();
