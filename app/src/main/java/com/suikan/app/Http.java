package com.suikan.app;

import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

final class Http {
    static final int MODEL_CONNECT_TIMEOUT_MS = 60000;
    static final int MODEL_IDLE_TIMEOUT_MS = 900000;
    static final long MODEL_CALL_TIMEOUT_MS = 3600000;
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = java.util.concurrent.Executors.newScheduledThreadPool(1, task -> {
        Thread thread = new Thread(task, "suikan-http-deadline"); thread.setDaemon(true); return thread;
    });
    static final int DEFAULT_OUTPUT_TOKENS = 0;
    static void validateOutputTokens(int value) {
        if (value < 0 || (value > 0 && value < 1024)) throw new IllegalArgumentException("输出上限请填写 0（服务商默认）或不少于 1024 的整数；具体上限由服务商决定");
    }
    static final class OutputLimitException extends IllegalArgumentException {
        OutputLimitException(JSONObject result, int requested) {
            super(limitMessage(result, requested));
        }
    }
    static final class ContextLimitException extends IllegalArgumentException {
        ContextLimitException(String message){super(message);}
    }
    static boolean contextLimit(int status,String body){
        if(status==413)return true;if(status!=400&&status!=422)return false;String value=body.toLowerCase(java.util.Locale.ROOT);
        return value.contains("context_length_exceeded")||value.contains("maximum context length")||value.contains("context window")||value.contains("input token limit")||value.contains("prompt is too long")||value.contains("input is too long")||(value.contains("上下文")&&(value.contains("超")||value.contains("过长")));
    }
    private static final class TransientException extends IllegalArgumentException {
        TransientException(String message, Throwable cause) { super(message,cause); }
        TransientException(String message) { super(message); }
    }
    private static boolean connectionTerminated(Throwable error){
        for(int i=0;error!=null&&i<8;i++,error=error.getCause())if(error instanceof java.net.SocketException||error instanceof java.io.EOFException)return true;
        return false;
    }
    static final class InterruptedGenerationException extends IllegalArgumentException {
        final boolean canSplit;
        InterruptedGenerationException(String message, Throwable cause, boolean canSplit) { super(message, cause); this.canSplit=canSplit; }
    }
    private static String limitMessage(JSONObject result, int requested) {
        StringBuilder message = new StringBuilder("模型输出被截断（");
        if(requested==0)message.append("未指定请求上限，采用服务商默认");else message.append("请求上限 ").append(requested).append(" tokens");
        JSONObject usage = result == null ? null : result.optJSONObject("usage");
        if (usage != null) {
            long used = usage.optLong("completion_tokens", usage.optLong("output_tokens", -1));
            if (used >= 0) message.append("，服务端返回用量 ").append(used);
            JSONObject details = usage.optJSONObject("completion_tokens_details");
            if (details == null) details = usage.optJSONObject("output_tokens_details");
            long reasoning = details == null ? -1 : details.optLong("reasoning_tokens", -1);
            if (reasoning >= 0) message.append("，其中思考 ").append(reasoning);
        }
        return message.append("）。可在设置中调高输出上限，或关闭 DeepSeek 深度思考后重试；服务商也可能另有限制。").toString();
    }
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36";
    static final class Control {
        final AtomicBoolean cancelled = new AtomicBoolean(); volatile HttpURLConnection connection;
        volatile boolean modelActive, requestSent;
        volatile boolean previewEnabled;
        volatile String preview="";
        private final StringBuilder previewBuffer=new StringBuilder();private long previewAt;private int previewSize;
        private volatile String phase="";
        private volatile long started, attemptStarted, lastData, received;
        private volatile int attempt, textChars, reasoningChars;
        void check() { if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("已停止总结"); }
        void cancel() {
            cancelled.set(true); HttpURLConnection c = connection;
            // Android disconnect may wait on a socket lock. Keep the Stop button responsive.
            if(c!=null){Thread t=new Thread(c::disconnect,"suikan-http-cancel");t.setDaemon(true);t.start();}
        }
        void beginModel(){started=System.nanoTime();modelActive=true;beginAttempt(1);}
        void beginAttempt(int value){attempt=value;attemptStarted=System.nanoTime();lastData=0;received=0;textChars=0;reasoningChars=0;requestSent=false;preview="";previewBuffer.setLength(0);previewAt=0;previewSize=0;phase="正在建立模型连接（DNS / TCP / TLS）";}
        void phase(String value){phase=value;}
        void received(int count){if(count>0){received+=count;lastData=System.nanoTime();}}
        void output(int text,int reasoning){textChars+=text;reasoningChars+=reasoning;}
        void outputText(String text){if(!previewEnabled||text.isEmpty()||previewBuffer.length()>=4096)return;previewBuffer.append(text,0,Math.min(text.length(),4096-previewBuffer.length()));long now=System.nanoTime();if(previewBuffer.length()-previewSize>=48||now-previewAt>=250000000L){preview=SummaryDraft.overview(previewBuffer.toString());previewAt=now;previewSize=previewBuffer.length();}}
        String diagnostic(){
            long now=System.nanoTime();StringBuilder out=new StringBuilder(phase);
            out.append(" · 第 ").append(attempt).append(" 次连接 · 本次 ").append(seconds(now-attemptStarted)).append(" 秒");
            if(received>0){out.append("\n已接收 ").append(received).append(" 字节，正文 ").append(textChars).append(" 字，思考 ").append(reasoningChars).append(" 字");out.append(" · 最近数据 ").append(seconds(now-lastData)).append(" 秒前");}
            else out.append("\n尚未收到响应正文");
            if(seconds(now-started)>=60)out.append(" · 此调用共 ").append(seconds(now-started)).append(" 秒");
            return out.toString();
        }
        private static long seconds(long nanos){return Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos));}
    }
    private static final class Response { final int status; final String body; Response(int s, String b) { status = s; body = b; } }
    static String endpoint(String input) throws Exception {
        String value = input.trim(); URI uri = new URI(value);
        String host = uri.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "10.0.2.2".equals(host);
        if (host == null || !("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("模型地址须为 HTTPS，不要把密钥放进地址；可填写 /v1 基础地址或完整接口");
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (path.isEmpty()) path = "/v1/chat/completions";
        else if (!path.endsWith("/chat/completions") && !path.endsWith("/responses")) path += "/chat/completions";
        return new URI(uri.getScheme(), null, host, uri.getPort(), path, null, null).toString();
    }
    private static Response request(String url, JSONObject payload, String apiKey, boolean bili, Control control) throws Exception {
        return request(url,payload,apiKey,bili,control,System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(bili?180000:MODEL_CALL_TIMEOUT_MS));
    }
    private static Response request(String url, JSONObject payload, String apiKey, boolean bili, Control control, long deadline) throws Exception {
        control.check(); URI uri = new URI(url);
        if (bili) {
            boolean cdn = "aisubtitle.hdslb.com".equals(uri.getHost()) && uri.getPath().startsWith("/bfs/");
            if (!"https".equals(uri.getScheme()) || (!VideoLink.isBiliHost(uri.getHost()) && !cdn))
                throw new IllegalArgumentException("字幕地址未通过校验");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); control.connection = c;
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(bili ? 30000 : MODEL_CONNECT_TIMEOUT_MS); c.setReadTimeout(bili ? 60000 : MODEL_IDLE_TIMEOUT_MS);
        c.setRequestProperty("Accept", bili ? "application/json" : "text/event-stream, application/json");
        if (bili) {
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (uri.getHost().equals("bilibili.com") || uri.getHost().endsWith(".bilibili.com")) {
                String cookies = CookieManager.getInstance().getCookie("https://api.bilibili.com/");
                if (cookies == null || cookies.isEmpty()) cookies = CookieManager.getInstance().getCookie("https://www.bilibili.com/");
                if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);
            }
        } else {
            c.setRequestProperty("User-Agent", "SuiKan/0.1.7");
            if (apiKey != null && !apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        AtomicBoolean expired = new AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> timer = bili ? null : WATCHDOG.schedule(() -> {
            expired.set(true); c.disconnect();
        }, Math.max(1,deadline-System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
        int responseStatus=0;
        try {
            if (payload != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { control.check(); out.write(bytes); }
                if(!bili){control.requestSent=true;control.phase("请求已发送，等待模型响应头");}
            }
            int status = c.getResponseCode();responseStatus=status;
            if(!bili)control.phase("已收到 HTTP "+status+"，等待响应正文");
            if (status >= 300 && status < 400) throw new IllegalArgumentException("接口发生重定向，请检查登录状态或填写最终模型接口地址");
            InputStream input = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (input == null) return new Response(status, "");
            if(!bili)input=new java.io.FilterInputStream(input){
                @Override public int read() throws IOException {int n=in.read();if(n>=0)control.received(1);return n;}
                @Override public int read(byte[] b,int off,int len) throws IOException {int n=in.read(b,off,len);control.received(n);return n;}
            };
            try (PushbackInputStream in = new PushbackInputStream(input, 512); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                String mime = c.getContentType();
                // Do not prefetch a fixed-size prefix before an explicitly streamed response.
                // Some Android HTTP streams wait for more bytes and hide a small first delta.
                if(!bili&&status<300&&mime!=null&&mime.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")){
                    control.phase("模型已连接，正在等待或接收流式输出");return new Response(status,stream(in,control,deadline).toString());
                }
                byte[] prefix = new byte[512]; int first = in.read(prefix);
                if (first > 0) in.unread(prefix, 0, first);
                String beginning = first > 0 ? new String(prefix, 0, first, StandardCharsets.UTF_8).trim() : "";
                if (!bili && status < 300 && (beginning.startsWith(":") || beginning.startsWith("data:") || beginning.startsWith("event:"))){
                    control.phase("模型已连接，正在等待或接收流式输出");
                    return new Response(status, stream(in, control, deadline).toString());
                }
                if(!bili)control.phase("正在接收模型 JSON 响应");
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    checkDeadline(control, deadline);
                    if (out.size() + n > 16 * 1024 * 1024) throw new IllegalArgumentException("接口响应过大，已停止读取"); out.write(buffer, 0, n);
                }
                return new Response(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            control.check();
            if (expired.get()) throw new IllegalArgumentException("单次模型调用超过 60 分钟，已停止；可换用响应更快的模型", e);
            if (e instanceof java.net.SocketTimeoutException) throw new IllegalArgumentException(bili ? "B 站请求等待超过 60 秒，请检查网络后重试" : "模型连接或读取超时（TCP 连接 60 秒，读取 / TLS 等待最多 15 分钟）。\n"+control.diagnostic(), e);
            String message=bili ? "获取 B 站数据时连接中断，请检查网络后重试" : "模型连接中断，未保存不完整回答。请检查网络或服务商状态后重试；延长客户端等待时间无法阻止服务端断开连接";
            // Chunked EOF may be a plain IOException (JDK) or ProtocolException (Android).
            // A broken successful response is retryable; certificate / HTTP rejection errors are not.
            if(!bili&&(responseStatus==0||(responseStatus>=200&&responseStatus<300))&&(connectionTerminated(e)||(responseStatus>=200&&control.requestSent)))throw new TransientException(message,e);
            throw new IllegalArgumentException(bili?message:message+"\n"+control.diagnostic(),e);
        } finally { if (timer != null) timer.cancel(false); c.disconnect(); if (control.connection == c) control.connection = null; }
    }
    private static void checkDeadline(Control control, long deadline) {
        control.check(); if (System.nanoTime() > deadline) throw new IllegalArgumentException("请求达到最长等待时间，已停止；模型单次调用最多等待 60 分钟");
    }
    /** Read events incrementally: keepalive comments cost no storage and never become JSON. */
    private static JSONObject stream(InputStream in, Control control, long deadline) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(), line = new StringBuilder();
        boolean finished = false, truncated = false; JSONObject usage = null, incomplete = null;
        int ch;
        while ((ch = reader.read()) != -1) {
            checkDeadline(control, deadline);
            if (ch != '\n') { if (line.length() >= 1024 * 1024) throw new IllegalArgumentException("模型单个流式事件过大"); line.append((char) ch); continue; }
            String value = line.toString().trim(); line.setLength(0);
            if (!value.startsWith("data:")) continue;
            String data = value.substring(5).trim(); if (data.isEmpty()) continue;
            if (data.equals("[DONE]")) { if (!finished) throw new TransientException("模型流式回答提前结束，未保存不完整回答"); break; }
            JSONObject event = new JSONObject(data);
            if (event.has("error")) throw new IllegalArgumentException("模型流式请求返回错误，未保存不完整回答");
            if (event.optJSONObject("usage") != null) usage = event.getJSONObject("usage");
            JSONObject response = event.optJSONObject("response");
            if (response != null && response.optJSONObject("usage") != null) usage = response.getJSONObject("usage");
            JSONArray choices = event.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice = choices.getJSONObject(0), delta = choice.optJSONObject("delta");
                if (delta != null) {
                    if(!content(delta.opt("refusal")).isEmpty())throw new IllegalArgumentException("服务商明确拒绝生成正文，请检查其返回状态");
                    String text=content(delta.opt("content"));int reasoning=content(delta.opt("reasoning_content")).length();
                    if(reasoning==0)reasoning=content(delta.opt("reasoning")).length();
                    output.append(text);control.output(text.length(),reasoning);control.outputText(text);
                    if(!text.isEmpty())control.phase("正在接收模型正文");else if(reasoning>0)control.phase("模型正在思考，尚未输出正文");
                }
                String reason = choice.optString("finish_reason");
                if (!reason.isEmpty() && !reason.equals("null")) {
                    if (!reason.equals("stop") && !reason.equals("length")) throw new IllegalArgumentException("模型未正常完成文本回答：" + reason);
                    finished = true; truncated = reason.equals("length");
                    control.phase("已收到结束原因，等待流式完成标记");
                }
            } else if (event.optString("type").equals("response.output_text.delta")) {
                String text=event.optString("delta");output.append(text);control.output(text.length(),0);control.outputText(text);control.phase("正在接收模型正文");
            }
            else if(event.optString("type").equals("response.reasoning_text.delta")||event.optString("type").equals("response.reasoning_summary_text.delta")) {
                control.output(0,event.optString("delta").length());control.phase("模型正在思考，尚未输出正文");
            }
            else if (event.optString("type").equals("response.completed")) {
                if (response == null) throw new IllegalArgumentException("模型完成事件缺少结果");
                if (output.length() == 0) output.append(finalText(response, 0)); finished = true;
            } else if (event.optString("type").equals("response.incomplete")) { incomplete = response; finished = true; }
            else if (event.optString("type").equals("response.failed")) throw new IllegalArgumentException("模型未完成输出");
            if (output.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("模型正文超过手机可处理的大小");
        }
        control.check();
        if (!finished || line.length() > 0) throw new TransientException("模型流式连接提前结束，未收到完成标记；未保存不完整回答，请重试");
        if (incomplete != null) return incomplete;
        if(!truncated&&output.toString().trim().isEmpty())throw new TransientException("服务商返回结束标记，但正文为空，未保存笔记");
        JSONObject result = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("content", output.toString())).put("finish_reason", truncated ? "length" : "stop")));
        if (usage != null) result.put("usage", usage); return result;
    }
    static JSONObject bili(String url, Control control) throws Exception {
        Response r = request(url, null, null, true, control);
        if (r.status != 200) throw new IllegalArgumentException(r.status == 412 || r.status == 429 ? "B 站暂时限制了请求，请稍后重试" : "B 站接口返回 HTTP " + r.status);
        return new JSONObject(r.body);
    }
    static String resolveShort(String initial, Control control) throws Exception {
        String url = VideoLink.checkedUrl(initial);
        for (int i = 0; i < 6; i++) {
            control.check(); HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); control.connection = c;
            c.setInstanceFollowRedirects(false); c.setRequestProperty("User-Agent", UA); c.setConnectTimeout(15000); c.setReadTimeout(15000);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = c.getHeaderField("Location"); if (next == null) throw new IllegalArgumentException("短链接缺少跳转地址");
                    url = VideoLink.checkedUrl(new URI(url).resolve(next).toString());
                } else if (code == 200) return url;
                else throw new IllegalArgumentException("短链接无法访问，HTTP " + code);
            } finally { c.disconnect(); if (control.connection == c) control.connection = null; }
        }
        throw new IllegalArgumentException("短链接跳转次数过多");
    }
    static String redact(String text, String key) {
        if (text == null) return "请求失败";
        if (key != null && !key.isEmpty()) text = text.replace(key, "[隐藏]");
        return text.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[隐藏]").replaceAll("(?i)Bearer\\s+\\S+", "Bearer [隐藏]");
    }
    private static String content(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder out = new StringBuilder(); JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) { JSONObject p = a.optJSONObject(i); if (p != null) out.append(p.optString("text")); } return out.toString();
        }
        return "";
    }
    private static String finalText(JSONObject result, int outputTokens) throws Exception {
        JSONArray choices = result.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.getJSONObject(0);
            if ("length".equals(choice.optString("finish_reason"))) throw new OutputLimitException(result, outputTokens);
            JSONObject message = choice.optJSONObject("message"); if (message != null) return content(message.opt("content"));
        }
        if ("incomplete".equals(result.optString("status"))) {
            JSONObject details = result.optJSONObject("incomplete_details");
            if (details != null && "max_output_tokens".equals(details.optString("reason"))) throw new OutputLimitException(result, outputTokens);
            throw new IllegalArgumentException("模型没有完成输出，请检查服务商返回状态");
        }
        if (!result.optString("output_text").isEmpty()) return result.optString("output_text");
        JSONArray output = Json.array(result, "output"); StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) { JSONObject item = output.optJSONObject(i); if (item != null && "message".equals(item.optString("type"))) text.append(content(item.opt("content"))); }
        return text.toString();
    }
    static String complete(String configured, String model, String key, String prompt, JSONObject context, Control control) throws Exception {
        return complete(configured, model, key, prompt, context, control, DEFAULT_OUTPUT_TOKENS, true);
    }
    static String complete(String configured, String model, String key, String prompt, JSONObject context, Control control, int outputTokens, boolean disableDeepSeekThinking) throws Exception {
        return complete(configured, model, key, prompt, context, control, outputTokens, disableDeepSeekThinking, true);
    }
    static String complete(String configured, String model, String key, String prompt, JSONObject context, Control control, int outputTokens, boolean disableDeepSeekThinking, boolean lightGlmThinking) throws Exception {
        validateOutputTokens(outputTokens);
        String url = endpoint(configured); boolean responses = new URI(url).getPath().endsWith("/responses");
        JSONObject body = new JSONObject().put("model", model).put("stream", true);
        if (responses) { body.put("instructions", prompt).put("input", context.toString()); if(outputTokens>0)body.put("max_output_tokens",outputTokens); }
        else body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", prompt))
                    .put(new JSONObject().put("role", "user").put("content", context.toString())));
        if(!responses&&outputTokens>0)body.put("max_tokens",outputTokens);
        // Source extraction normally does not require extended reasoning; detail remains in the final text.
        // Keep other providers' bodies unchanged; a relay may require the user to retain its defaults.
        if (disableDeepSeekThinking && model.toLowerCase(java.util.Locale.ROOT).startsWith("deepseek")) {
            if (responses) body.put("reasoning", new JSONObject().put("effort", "none"));
            else body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        if (lightGlmThinking && model.toLowerCase(java.util.Locale.ROOT).startsWith("glm-5.3")) {
            if (responses) body.put("reasoning", new JSONObject().put("effort", "low"));
            else body.put("thinking", new JSONObject().put("type", "enabled")).put("reasoning_effort", "low");
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(MODEL_CALL_TIMEOUT_MS);
        control.beginModel();
        try {
        Response r = modelRequest(url, body, key, control, deadline);
        // Only adapt a rejected request, never retry a partially received or timed-out generation.
        if (!responses && body.has("max_tokens") && r.status == 400 && r.body.contains("max_tokens") && r.body.contains("max_completion_tokens")) {
            body.remove("max_tokens"); body.put("max_completion_tokens", outputTokens); r = modelRequest(url, body, key, control, deadline);
        }
        if (r.status < 200 || r.status >= 300) {
            String message = ""; try { JSONObject error = new JSONObject(r.body).optJSONObject("error"); if (error != null) message = error.optString("message"); } catch (Exception ignored) { }
            message = redact(message, key); if (message.length() > 240) message = message.substring(0, 240);
            if(contextLimit(r.status,r.body))throw new ContextLimitException("接口输入超过允许长度（HTTP "+r.status+"）"+(message.isEmpty()?"":"："+message));
            throw new IllegalArgumentException("模型接口 HTTP " + r.status + (message.isEmpty() ? "，请检查地址、密钥和模型名称" : "：" + message));
        }
        String text;
        if (r.body.trim().startsWith("data:") || r.body.trim().startsWith("event:") || r.body.contains("\nevent:")) {
            StringBuilder output = new StringBuilder();
            boolean truncated = false; JSONObject usageResult = new JSONObject();
            for (String line : r.body.split("\n")) if (line.startsWith("data:")) {
                String data = line.substring(5).trim(); if (data.isEmpty() || data.equals("[DONE]")) continue;
                JSONObject event = new JSONObject(data); if (event.has("error")) throw new IllegalArgumentException("模型流式请求返回错误");
                if (event.optJSONObject("usage") != null) usageResult = event;
                if (event.optJSONObject("response") != null && event.getJSONObject("response").optJSONObject("usage") != null) usageResult = event.getJSONObject("response");
                JSONArray choices = event.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject choice = choices.optJSONObject(0); if (choice != null) {
                        if ("length".equals(choice.optString("finish_reason"))) truncated = true;
                        JSONObject delta = choice.optJSONObject("delta"); if (delta != null) output.append(content(delta.opt("content")));
                    }
                } else if ("response.output_text.delta".equals(event.optString("type"))) output.append(event.optString("delta"));
                else if ("response.completed".equals(event.optString("type")) && output.length() == 0) output.append(finalText(event.getJSONObject("response"), outputTokens));
                else if ("response.incomplete".equals(event.optString("type"))) {
                    JSONObject response = event.optJSONObject("response");
                    if (response == null) throw new IllegalArgumentException("模型未完成输出");
                    finalText(response, outputTokens);
                    throw new IllegalArgumentException("模型未完成输出");
                } else if ("response.failed".equals(event.optString("type"))) throw new IllegalArgumentException("模型未完成输出");
            }
            if (truncated) throw new OutputLimitException(usageResult, outputTokens);
            text = output.toString();
        } else text = finalText(new JSONObject(r.body), outputTokens);
        if (text.trim().isEmpty()) throw new IllegalArgumentException("模型没有返回可读取的正文，请检查接口兼容性");
        return text;
        } finally {control.modelActive=false;}
    }
    private static Response modelRequest(String url,JSONObject body,String key,Control control,long deadline) throws Exception {
        for(int attempt=0;attempt<2;attempt++){
            checkDeadline(control,deadline);
            control.beginAttempt(attempt+1);
            try{
                Response result=request(url,body,key,false,control,deadline);
                if(result.status>=200&&result.status<300){
                    JSONObject parsed=null;try{parsed=new JSONObject(result.body);}catch(org.json.JSONException ignored){}
                    if(parsed!=null){
                        JSONArray choices=parsed.optJSONArray("choices");
                        if(choices!=null&&choices.length()>0){JSONObject first=choices.optJSONObject(0),message=first==null?null:first.optJSONObject("message");
                            if(first!=null&&"stop".equals(first.optString("finish_reason"))&&message!=null&&content(message.opt("content")).trim().isEmpty()&&content(message.opt("refusal")).isEmpty())throw new TransientException("服务商返回结束标记，但正文为空，未保存笔记");
                        }else if("completed".equals(parsed.optString("status"))&&finalText(parsed,0).trim().isEmpty())throw new TransientException("服务商返回结束标记，但正文为空，未保存笔记");
                    }
                }
                return result;
            }
            catch(TransientException e){
                control.check();if(attempt==1)throw new InterruptedGenerationException(e.getMessage()+"（自动重连一次仍未完成）\n"+control.diagnostic(),e,control.requestSent);
                control.phase("模型响应未完成，2 秒后自动重试一次");
                for(int i=0;i<20;i++){checkDeadline(control,deadline);Thread.sleep(100);}
            }
        }
        throw new IllegalStateException("模型请求未完成");
    }
}
