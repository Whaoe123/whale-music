package com.whale.music.data.lx

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 洛雪（LX）v2 自定义音源的离线 JS 运行时 —— 一个脚本一个 WebView 沙箱。
 *
 * 协议对齐真实 LX 宿主实现（参考 lx-music 与 MyGo 的接法）：
 * - 脚本按 CommonJS 跑：`new Function(globalThis, lx, module, exports, require, Buffer, console, process, code)`
 * - `lx` 提供 version / env / currentScriptInfo / EVENT_NAMES / on / send /
 *   request(url, options, callback) 回调式 / utils(buffer·crypto·zlib)
 * - 音源通过 `module.exports.sources` 或 `lx.send('inited', {sources})` 暴露，
 *   每个条目形如 `{ qualitys: [...], musicUrl(musicInfo, type), musicSearch?, lyric?, pic? }`
 *
 * 安全沙箱（能掐的全掐了）：
 * - `blockNetworkLoads` / `blockNetworkImage`：浏览器层禁止一切直连，
 *   脚本的所有网络只能走 [Bridge.request] 这一个原生桥；
 * - 关闭文件访问与 DOM 存储，不暴露任何其它 JS 接口；
 * - 桥只提供 request / crypto / zlib 三个能力，播放地址只由脚本解析。
 *
 * 线程约定：WebView 的所有操作 post 到主线程；[invoke] 从后台线程阻塞调用。
 */
@SuppressLint("SetJavaScriptEnabled")
class LxRuntime(private val context: Context) {

    /** 脚本编译探针结果 */
    data class Probe(
        val ok: Boolean,
        val error: String? = null,
        val sources: List<String> = emptyList(),
        val hasSearch: Boolean = false,
        val hasLyric: Boolean = false,
        val hasPic: Boolean = false,
        val qualitys: Map<String, List<String>> = emptyMap()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    @Volatile
    private var ready = false
    private var probe: Probe? = null

    private val pending = ConcurrentHashMap<String, (Boolean, String) -> Unit>()
    private val seq = AtomicLong(0)

    @Volatile
    private var scriptText: String = ""

    // ───────── 加载脚本 ─────────

    /**
     * 创建沙箱并编译脚本；[onReady] 回调在主线程。
     * 脚本正文通过桥按需取（避免超长字符串塞进 evaluateJavascript）。
     */
    fun loadScript(scriptText: String, scriptInfoJson: String, onReady: (Probe) -> Unit) {
        this.scriptText = scriptText
        mainHandler.post {
            try {
                val wv = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.blockNetworkImage = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.setSupportZoom(false)
                    addJavascriptInterface(Bridge(), "WhaleLxBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(PRELUDE) {
                                view.evaluateJavascript(
                                    "(function(){window.__lxScriptInfo = " + scriptInfoJson + ";" +
                                        "return 'ok';})();"
                                ) {
                                    view.evaluateJavascript(FACTORY) { result ->
                                        val p = parseProbe(result)
                                        probe = p
                                        ready = p.ok
                                        onReady(p)
                                    }
                                }
                            }
                        }
                    }
                }
                webView = wv
                wv.loadUrl("about:blank")
            } catch (e: Exception) {
                onReady(Probe(false, "运行时初始化失败：${e.message}"))
            }
        }
    }

    /**
     * 调音源条目方法：`entry[action](...args)`。
     * @param source module.exports.sources 里的源 key（如 wy / tx / 默认单源）
     * @param action  musicUrl / musicSearch / lyric / pic
     * @param argsJson JSON 数组，原样作为参数列表
     */
    fun invoke(source: String, action: String, argsJson: String, timeoutMs: Long = 20_000L): Result {
        if (action !in ALLOWED_ACTIONS) return Result(false, "不允许调用 $action")
        if (!ready) return Result(false, probe?.error ?: "运行时未就绪")
        val id = "i${seq.incrementAndGet()}"
        val latch = CountDownLatch(1)
        var ok = false
        var payload = ""
        pending[id] = { o, p ->
            ok = o
            payload = p
            latch.countDown()
        }
        mainHandler.post {
            try {
                val wv = webView
                if (wv == null) {
                    pending.remove(id)?.invoke(false, "运行时已销毁")
                    return@post
                }
                val js = """
                    (function(){try{
                      var rt = window.__lxRuntime;
                      var args = $argsJson;
                      var p = rt.call('$source', '$action', args);
                      Promise.resolve(p).then(
                        function(v){WhaleLxBridge.resolve('$id',1,__lxStr(v));},
                        function(e){WhaleLxBridge.resolve('$id',0,String(e));});
                    }catch(e){WhaleLxBridge.resolve('$id',0,String(e));}})();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                pending.remove(id)?.invoke(false, e.message ?: "执行失败")
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            return Result(false, "脚本响应超时")
        }
        return Result(ok, payload)
    }

    fun destroy() {
        mainHandler.post {
            runCatching { webView?.destroy() }
            webView = null
            ready = false
        }
    }

    data class Result(val ok: Boolean, val payload: String)

    private fun parseProbe(raw: String?): Probe {
        // evaluateJavascript 返回的是 JSON 字符串字面量，先解一层引号
        val inner = runCatching {
            val v = org.json.JSONTokener(raw ?: "").nextValue()
            if (v is String) v else raw
        }.getOrNull() ?: return Probe(false, "脚本编译失败")
        val json = runCatching { JSONObject(inner) }.getOrNull()
            ?: return Probe(false, "脚本编译失败")
        json.optString("error").takeIf { it.isNotBlank() }?.let { return Probe(false, it) }
        val sources = json.optJSONObject("sources") ?: JSONObject()
        val qualitys = HashMap<String, List<String>>()
        val keys = sources.keys().asSequence().toList()
        keys.forEach { k ->
            val arr = sources.optJSONObject(k)?.optJSONArray("qualitys")
            qualitys[k] = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
        }
        return Probe(
            ok = true,
            sources = keys,
            hasSearch = json.optBoolean("hasSearch"),
            hasLyric = json.optBoolean("hasLyric"),
            hasPic = json.optBoolean("hasPic"),
            qualitys = qualitys
        )
    }

    // ───────── JS ↔ 原生桥 ─────────

    inner class Bridge {

        /** 脚本正文（FACTORY 第一步取） */
        @JavascriptInterface
        fun scriptText(): String = scriptText

        /** 脚本唯一的网络出口：同步执行 HTTP，返回 base64 化的响应 */
        @JavascriptInterface
        fun request(optsJson: String): String {
            return try {
                val o = JSONObject(optsJson)
                val url = o.optString("url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return errResp("只支持 http(s) 地址")
                }
                val method = o.optString("method", "GET").uppercase()
                val timeout = o.optInt("timeout", 0).takeIf { it in 1..120_000 } ?: 20_000
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeout
                    readTimeout = timeout
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    val headers = o.optJSONObject("headers")
                    headers?.keys()?.forEach { k ->
                        headers.optString(k)?.let { setRequestProperty(k, it) }
                    }
                    val body = o.opt("body")
                    val form = o.optJSONObject("form")
                    val write: ByteArray? = when {
                        body != null && body != JSONObject.NULL && body !is JSONObject &&
                            body.toString().isNotEmpty() ->
                            body.toString().toByteArray(Charsets.UTF_8)
                        body is JSONObject -> body.toString().toByteArray(Charsets.UTF_8)
                        form != null -> {
                            setRequestProperty(
                                "Content-Type",
                                "application/x-www-form-urlencoded; charset=utf-8"
                            )
                            form.keys().asSequence().joinToString("&") { k ->
                                "$k=" + URLEncoder.encode(form.optString(k), "UTF-8")
                            }.toByteArray(Charsets.UTF_8)
                        }
                        else -> null
                    }
                    if (write != null) {
                        if (method == "GET" || method == "HEAD") {
                            // 有 body 却是 GET：按 POST 语义发（脚本常见写法）
                            doOutput = true
                            requestMethod = "POST"
                        } else {
                            doOutput = true
                            requestMethod = method
                        }
                        outputStream.use { s -> s.write(write) }
                    } else {
                        requestMethod = method
                    }
                }
                val code = conn.responseCode
                val respHeaders = JSONObject()
                conn.headerFields?.forEach { (k, vs) ->
                    if (k != null) respHeaders.put(k, vs.joinToString(", "))
                }
                val raw = runCatching {
                    val stream = try {
                        conn.inputStream
                    } catch (e: Exception) {
                        conn.errorStream
                    }
                    stream?.use { s ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = s.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull() ?: ByteArray(0)
                conn.disconnect()
                val gzip = respHeaders.optString("Content-Encoding").contains("gzip", true)
                val bytes = if (gzip) gunzip(raw) else raw
                JSONObject().apply {
                    put("statusCode", code)
                    put("headers", respHeaders)
                    put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.toString()
            } catch (e: Exception) {
                errResp(e.message ?: "请求失败")
            }
        }

        private fun errResp(msg: String): String = JSONObject().apply {
            put("statusCode", 0)
            put("headers", JSONObject())
            put("body", "")
            put("error", msg)
        }.toString()

        /** 对称哈希类：入参 base64，返回 hex（加密类返回 base64） */
        @JavascriptInterface
        fun crypto(op: String, argsJson: String): String {
            return try {
                val args = JSONArray(argsJson)
                val bytes = { i: Int -> Base64.decode(args.optString(i), Base64.NO_WRAP) }
                when (op) {
                    "md5" -> hex(digest("MD5", bytes(0)))
                    "sha1" -> hex(digest("SHA-1", bytes(0)))
                    "sha256" -> hex(digest("SHA-256", bytes(0)))
                    "sha512" -> hex(digest("SHA-512", bytes(0)))
                    "hmacSha1" -> hex(hmac("HmacSHA1", bytes(0), bytes(1)))
                    "hmacSha256" -> hex(hmac("HmacSHA256", bytes(0), bytes(1)))
                    "aesEcbEncrypt" -> b64(cipher("AES/ECB/PKCS5Padding", bytes(1), null, bytes(0), true))
                    "aesEcbDecrypt" -> b64(cipher("AES/ECB/PKCS5Padding", bytes(1), null, bytes(0), false))
                    "aesCbcEncrypt" -> b64(cipher("AES/CBC/PKCS5Padding", bytes(1), bytes(2), bytes(0), true))
                    "aesCbcDecrypt" -> b64(cipher("AES/CBC/PKCS5Padding", bytes(1), bytes(2), bytes(0), false))
                    "desEcbEncrypt" -> b64(cipher("DES/ECB/PKCS5Padding", bytes(1), null, bytes(0), true))
                    "desEcbDecrypt" -> b64(cipher("DES/ECB/PKCS5Padding", bytes(1), null, bytes(0), false))
                    "des3EcbEncrypt" -> b64(cipher("DESede/ECB/PKCS5Padding", bytes(1), null, bytes(0), true))
                    "des3EcbDecrypt" -> b64(cipher("DESede/ECB/PKCS5Padding", bytes(1), null, bytes(0), false))
                    "rc4Encrypt" -> b64(rc4(bytes(1), bytes(0)))
                    "rc4Decrypt" -> b64(rc4(bytes(1), bytes(0)))
                    else -> ""
                }
            } catch (e: Exception) {
                ""
            }
        }

        /** 压缩 / 解压：入参出参都是 base64 */
        @JavascriptInterface
        fun zlib(op: String, dataB64: String): String {
            return try {
                val data = Base64.decode(dataB64, Base64.NO_WRAP)
                val out = when (op) {
                    "deflate" -> deflate(data, false)
                    "inflate" -> inflate(data, false)
                    "gzip" -> deflate(data, true)
                    "gunzip" -> inflate(data, true)
                    else -> return ""
                }
                b64(out)
            } catch (e: Exception) {
                ""
            }
        }

        /** JS 侧 Promise 完成回调 */
        @JavascriptInterface
        fun resolve(id: String, ok: Int, payload: String) {
            pending.remove(id)?.invoke(ok == 1, payload)
        }
    }

    // ───────── 加解密原语 ─────────

    private fun digest(algo: String, data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance(algo).digest(data)

    private fun hmac(algo: String, key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(algo).apply { init(SecretKeySpec(key, algo)) }.doFinal(data)

    private fun cipher(
        transformation: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        encrypt: Boolean
    ): ByteArray {
        val c = Cipher.getInstance(transformation)
        val algo = when {
            transformation.startsWith("AES") -> "AES"
            transformation.startsWith("DESede") -> "DESede"
            else -> "DES"
        }
        val secret = SecretKeySpec(key, algo)
        if (iv != null) c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
        else c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, secret)
        return c.doFinal(data)
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var i = 0; j = 0
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun deflate(data: ByteArray, gzip: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val stream = if (gzip) GZIPOutputStream(out) else java.util.zip.DeflaterOutputStream(out)
        stream.use { it.write(data) }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, gzip: Boolean): ByteArray {
        val input = if (gzip) {
            GZIPInputStream(java.io.ByteArrayInputStream(data))
        } else {
            java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(data))
        }
        return input.use { it.readBytes() }
    }

    private fun gunzip(data: ByteArray): ByteArray = try {
        inflate(data, true)
    } catch (e: Exception) {
        data
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        private val ALLOWED_ACTIONS = setOf("musicSearch", "musicUrl", "lyric", "pic", "album", "artist", "list")

        /**
         * 前奏：Buffer / lx / require / process 等宿主设施。
         * 脚本按 CommonJS 跑，`lx.request` 是回调式（与真实 LX 一致）。
         */
        private val PRELUDE: String = """
            (function(){
              var bridge = WhaleLxBridge;
              function utf8ToB64(s){
                var b = new TextEncoder().encode(s), bin = '';
                for(var i=0;i<b.length;i++) bin += String.fromCharCode(b[i]);
                return btoa(bin);
              }
              function b64ToBytes(b64){
                var bin = atob(b64), b = new Uint8Array(bin.length);
                for(var i=0;i<bin.length;i++) b[i] = bin.charCodeAt(i);
                return b;
              }
              function bytesToUtf8(b){ return new TextDecoder().decode(b); }
              function bytesToHex(b){
                var s=''; for(var i=0;i<b.length;i++) s += ('0'+b[i].toString(16)).slice(-2);
                return s;
              }
              function bytesToB64(b){
                var bin=''; for(var i=0;i<b.length;i++) bin += String.fromCharCode(b[i]);
                return btoa(bin);
              }
              function Buf(b){ this.b = (b instanceof Uint8Array) ? b : (b ? new Uint8Array(b) : new Uint8Array(0)); }
              Buf.prototype.toString = function(enc){
                enc = (enc||'utf8').toLowerCase();
                if(enc==='hex') return bytesToHex(this.b);
                if(enc==='base64') return bytesToB64(this.b);
                if(enc==='latin1'||enc==='binary'){ var s=''; for(var i=0;i<this.b.length;i++) s+=String.fromCharCode(this.b[i]); return s; }
                return bytesToUtf8(this.b);
              };
              Buf.prototype.slice = function(s,e){ return new Buf(this.b.slice(s||0, e)); };
              Object.defineProperty(Buf.prototype,'length',{get:function(){return this.b.length;}});
              var Buffer = window.__lxBuffer = {
                from: function(data, enc){
                  enc = (enc||'utf8').toLowerCase();
                  if(data instanceof Buf) return new Buf(data.b.slice(0));
                  if(data instanceof Uint8Array) return new Buf(data.slice(0));
                  if(enc==='base64') return new Buf(b64ToBytes(String(data)));
                  if(enc==='hex'){ var s=String(data), b=new Uint8Array(s.length>>1);
                    for(var i=0;i<b.length;i++) b[i]=parseInt(s.substr(i*2,2),16); return new Buf(b); }
                  if(enc==='latin1'||enc==='binary'){ var t=String(data), u=new Uint8Array(t.length);
                    for(var i=0;i<t.length;i++) u[i]=t.charCodeAt(i)&0xff; return new Buf(u); }
                  return new Buf(new TextEncoder().encode(String(data)));
                },
                concat: function(list){
                  var total=0, parts=[];
                  for(var i=0;i<list.length;i++){ var x=list[i]; var u=(x instanceof Buf)?x.b:(x instanceof Uint8Array?x:new Uint8Array(0)); parts.push(u); total+=u.length; }
                  var out=new Uint8Array(total), off=0;
                  for(var i=0;i<parts.length;i++){ out.set(parts[i], off); off+=parts[i].length; }
                  return new Buf(out);
                },
                isBuffer: function(x){ return x instanceof Buf; }
              };
              function toB64(x){
                if(x==null) return '';
                if(x instanceof Buf) return bytesToB64(x.b);
                if(x instanceof Uint8Array) return bytesToB64(x);
                if(typeof x==='string') return utf8ToB64(x);
                return utf8ToB64(String(x));
              }
              function fromB64(b64){ return new Buf(b64ToBytes(b64)); }
              function cryptoSync(op, args){ return bridge.crypto(op, JSON.stringify(args)); }

              // ── 事件状态（lx.on / lx.send 用） ──
              var state = window.__lxState = { sources: null, handlers: {}, info: null, alert: null };

              var lx = window.__lxLx = {
                version: '2.0.0',
                env: 'android',
                get currentScriptInfo(){ return window.__lxScriptInfo || {}; },
                EVENT_NAMES: { request:'request', inited:'inited', updateAlert:'updateAlert' },
                on: function(name, handler){
                  if (typeof handler === 'function') (state.handlers[name] = state.handlers[name] || []).push(handler);
                },
                send: function(name, data){
                  if (name === 'inited') state.sources = (data && data.sources) || state.sources;
                  else if (name === 'updateAlert') state.alert = data || null;
                },
                // 回调式请求（真实 LX 协议）；异步投递，返回取消函数
                request: function(url, options, callback){
                  if (typeof options === 'function'){ callback = options; options = {}; }
                  var opts = Object.assign({}, options || {}, { url: url });
                  var cancel = function(){};
                  setTimeout(function(){
                    var resp;
                    try { resp = JSON.parse(bridge.request(JSON.stringify(opts))); }
                    catch(e){ resp = { statusCode:0, headers:{}, body:'', error:String(e) }; }
                    if (typeof callback !== 'function') return;
                    if (resp.error && !resp.statusCode){
                      callback(new Error(resp.error));
                      return;
                    }
                    var body = '';
                    try { body = bytesToUtf8(b64ToBytes(resp.body || '')); } catch(e){ body = ''; }
                    try { body = JSON.parse(body); } catch(e){}
                    callback(null, { statusCode: resp.statusCode, headers: resp.headers || {}, body: body });
                  }, 0);
                  return cancel;
                },
                utils: {
                  buffer: {
                    from: Buffer.from,
                    bufToString: function(buf, format){ return Buffer.from(buf).toString(format || 'utf8'); }
                  },
                  crypto: {
                    md5: function(text){ return cryptoSync('md5', [toB64(String(text))]); },
                    sha1: function(text){ return cryptoSync('sha1', [toB64(String(text))]); },
                    sha256: function(text){ return cryptoSync('sha256', [toB64(String(text))]); },
                    randomBytes: function(size){
                      var n = Math.max(1, Number(size)||16), out='';
                      for(var i=0;i<n;i++) out += ('0'+Math.floor(Math.random()*256).toString(16)).slice(-2);
                      return out;
                    },
                    aesEncrypt: function(buffer, mode, key, iv){
                      var m = String(mode||'aes-128-ecb').toLowerCase();
                      var bits = (m.match(/aes-(\d+)/)||[])[1]; bits = bits?parseInt(bits):128;
                      var len = bits/8;
                      var kb = Buffer.from(key).b;
                      if (kb.length !== len){ var t=new Uint8Array(len); t.set(kb.subarray(0, Math.min(kb.length,len))); kb=t; }
                      var data = Buffer.from(buffer).b;
                      var op, args;
                      if (m.indexOf('cbc')>=0){
                        var ib = Buffer.from(iv||'').b;
                        if (ib.length !== 16){ var u=new Uint8Array(16); u.set(ib.subarray(0,Math.min(ib.length,16))); ib=u; }
                        op = 'aesCbcEncrypt';
                        args = [bytesToB64(data), bytesToB64(kb), bytesToB64(ib)];
                      } else {
                        op = 'aesEcbEncrypt';
                        args = [bytesToB64(data), bytesToB64(kb)];
                      }
                      var r = cryptoSync(op, args);
                      return r ? fromB64(r) : null;
                    },
                    aesDecrypt: function(buffer, mode, key, iv){
                      var m = String(mode||'aes-128-ecb').toLowerCase();
                      var bits = (m.match(/aes-(\d+)/)||[])[1]; bits = bits?parseInt(bits):128;
                      var len = bits/8;
                      var kb = Buffer.from(key).b;
                      if (kb.length !== len){ var t=new Uint8Array(len); t.set(kb.subarray(0, Math.min(kb.length,len))); kb=t; }
                      var data = Buffer.from(buffer).b;
                      var op, args;
                      if (m.indexOf('cbc')>=0){
                        var ib = Buffer.from(iv||'').b;
                        if (ib.length !== 16){ var u=new Uint8Array(16); u.set(ib.subarray(0,Math.min(ib.length,16))); ib=u; }
                        op = 'aesCbcDecrypt';
                        args = [bytesToB64(data), bytesToB64(kb), bytesToB64(ib)];
                      } else {
                        op = 'aesEcbDecrypt';
                        args = [bytesToB64(data), bytesToB64(kb)];
                      }
                      var r = cryptoSync(op, args);
                      return r ? fromB64(r) : null;
                    },
                    rsaEncrypt: function(){ throw new Error('本运行时不支持 RSA 加密'); }
                  },
                  zlib: {
                    inflate: function(buf){ return Promise.resolve(zlibSync('inflate', buf)); },
                    deflate: function(buf){ return Promise.resolve(zlibSync('deflate', buf)); },
                    gzip: function(buf){ return Promise.resolve(zlibSync('gzip', buf)); },
                    gunzip: function(buf){ return Promise.resolve(zlibSync('gunzip', buf)); },
                    inflateSync: function(buf){ return zlibSync('inflate', buf); },
                    deflateSync: function(buf){ return zlibSync('deflate', buf); },
                    gzipSync: function(buf){ return zlibSync('gzip', buf); },
                    gunzipSync: function(buf){ return zlibSync('gunzip', buf); }
                  }
                }
              };
              function zlibSync(op, buf){
                var r = bridge.zlib(op, toB64(buf));
                return r ? fromB64(r) : null;
              }
              window.__lxZlibSync = zlibSync;

              // ── require 垫片 ──
              window.__lxRequire = function(name){
                if (name === 'buffer') return { Buffer: Buffer };
                if (name === 'crypto') return {
                  createHash: function(alg){
                    var op = String(alg||'md5').toLowerCase().replace('-','');
                    if (['md5','sha1','sha256','sha512'].indexOf(op) < 0) throw new Error('不支持的摘要算法: '+alg);
                    var text = '';
                    return {
                      update: function(v){ text += String(v); return this; },
                      digest: function(enc){
                        var hex = cryptoSync(op, [toB64(text)]);
                        if (enc === 'buffer') return Buffer.from(hex, 'hex');
                        if (enc === 'base64') return Buffer.from(hex, 'hex').toString('base64');
                        return hex;
                      }
                    };
                  },
                  createHmac: function(alg, key){
                    var op = String(alg||'sha1').toLowerCase().indexOf('256')>=0 ? 'hmacSha256' : 'hmacSha1';
                    var text = '';
                    return {
                      update: function(v){ text += String(v); return this; },
                      digest: function(){
                        var hex = cryptoSync(op, [toB64(key), toB64(text)]);
                        return Buffer.from(hex, 'hex');
                      }
                    };
                  },
                  randomBytes: function(size){ return Buffer.from(lx.utils.crypto.randomBytes(size), 'hex'); }
                };
                if (name === 'zlib') return {
                  inflateSync: lx.utils.zlib.inflateSync,
                  deflateSync: lx.utils.zlib.deflateSync,
                  gzipSync: lx.utils.zlib.gzipSync,
                  gunzipSync: lx.utils.zlib.gunzipSync,
                  inflate: lx.utils.zlib.inflate,
                  deflate: lx.utils.zlib.deflate
                };
                throw new Error('require("' + name + '") 不受支持');
              };

              // ── process 假货（脚本拿来判断运行环境） ──
              window.__lxProcess = {
                version: 'v20.0.0', platform: 'android', arch: 'arm64',
                env: {}, nextTick: function(fn){ setTimeout(fn, 0); }
              };

              window.__lxStr = function(v){
                try { return JSON.stringify(v===undefined?null:v); }
                catch(e){ return JSON.stringify(String(v)); }
              };
            })();
        """.trimIndent()

        /**
         * CommonJS 执行 + 音源收集 + 调用入口。
         * 音源条目支持 module.exports.sources / lx.send('inited') / lx.on('request') 三种暴露方式。
         */
        private val FACTORY: String = """
            (function(){
              var bridge = WhaleLxBridge;
              var code = bridge.scriptText();
              var state = window.__lxState;
              var module = { exports: {} };
              var sandbox = Object.create(globalThis);
              sandbox.lx = window.__lxLx;
              sandbox.host = undefined;
              try {
                var factory = new Function('globalThis','lx','module','exports','require','Buffer','console','process', code + '\n//# sourceURL=lx-source.js');
                factory(sandbox, window.__lxLx, module, module.exports,
                         window.__lxRequire, window.__lxBuffer, console, window.__lxProcess);
              } catch(e) {
                return JSON.stringify({ error: '脚本执行失败: ' + String(e) });
              }
              var sources = (module.exports && module.exports.sources) || state.sources || {};
              if (!sources || typeof sources !== 'object') sources = {};
              var handlers = (state.handlers && state.handlers.request) || [];

              window.__lxRuntime = {
                sources: sources,
                call: function(src, action, args){
                  var entry = sources[src];
                  if (handlers.length) {
                    return (async function(){
                      var info = { musicInfo: args[0], type: args[1], args: args };
                      for (var i=0;i<handlers.length;i++){
                        var a = await handlers[i]({ source: src, action: action, info: info });
                        if (a != null) return a;
                      }
                      if (entry && typeof entry[action] === 'function') return await entry[action].apply(entry, args);
                      throw new Error('音源不支持 ' + action);
                    })();
                  }
                  if (!entry || typeof entry[action] !== 'function') {
                    return Promise.reject(new Error('音源不支持 ' + src + ' 的 ' + action));
                  }
                  return entry[action].apply(entry, args);
                }
              };

              var out = { sources: {}, hasSearch:false, hasLyric:false, hasPic:false, hasMusicUrl:false };
              Object.keys(sources).forEach(function(k){
                var e = sources[k] || {};
                out.sources[k] = { qualitys: e.qualitys || [] };
                if (typeof e.musicSearch === 'function') out.hasSearch = true;
                if (typeof e.lyric === 'function') out.hasLyric = true;
                if (typeof e.pic === 'function') out.hasPic = true;
                if (typeof e.musicUrl === 'function') out.hasMusicUrl = true;
              });
              if (handlers.length) out.hasMusicUrl = true;
              if (!Object.keys(out.sources).length && !handlers.length) {
                return JSON.stringify({ error: '脚本没有导出任何音源（既没有 sources 也没有注册 request 事件）' });
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}
