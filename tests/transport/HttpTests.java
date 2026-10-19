package com.suikan.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;

/** Exercises the production HTTP implementation against the local fixture; no real API keys. */
public final class HttpTests {
    static int checks;
    static final String BASE = "http://127.0.0.1:18765/v1";
    static void check(boolean ok, String label) {
        checks++; if (!ok) throw new AssertionError(label);
    }
    static JSONObject call(String endpoint, String model, int limit, boolean disable) throws Exception {
        return Json.parseObject(Http.complete(endpoint, model, "fixture-key", "Return JSON", new JSONObject(), new Http.Control(), limit, disable));
    }
    static Http.OutputLimitException truncated(String model, int limit) throws Exception {
        try { call(model.contains("responses") ? BASE + "/responses" : BASE, model, limit, false); }
        catch (Http.OutputLimitException e) { return e; }
        throw new AssertionError("Truncated output was accepted: " + model);
    }
    static int count(String model) throws Exception {
        try (InputStream in = new URL("http://127.0.0.1:18765/counts").openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
            return new JSONObject(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).optInt(model);
        }
    }
    public static void main(String[] args) throws Exception {
        JSONObject defaults = Json.parseObject(Http.complete(BASE,"deepseek-v4.1-flash","fixture-key","Return JSON",new JSONObject(),new Http.Control())).getJSONObject("received");
        check(!defaults.has("max_tokens")&&!defaults.has("max_completion_tokens"),"default sends no app output ceiling");
        check(defaults.getJSONObject("thinking").getString("type").equals("disabled"),"DeepSeek thinking off by default");
        check(defaults.getBoolean("stream"),"model requests streaming output");
        check(defaults.getJSONObject("response_format").getString("type").equals("json_object"),"JSON response constraint accompanies JSON prompts");
        check(!call(BASE,"echo-format-unsupported",0,false).getJSONObject("received").has("response_format")&&count("echo-format-unsupported")==2,"only explicit unsupported JSON mode falls back without it");
        check(Http.MODEL_IDLE_TIMEOUT_MS==900000&&Http.MODEL_CALL_TIMEOUT_MS==3600000&&Http.MODEL_CONNECT_TIMEOUT_MS==60000,"long model timeouts");
        check(SummaryPipeline.sourceBudget("glm-5.3-flash")==8000&&SummaryPipeline.sourceBudget("deepseek-v4.1-flash")==8000&&SummaryPipeline.sourceBudget("other")==8000,"mobile requests use smaller budgets even for large-context models");
        JSONObject custom=call(BASE,"deepseek-v4.1-flash",8192,true).getJSONObject("received");
        check(custom.getInt("max_tokens")==8192,"custom limit reaches server");
        check(!call(BASE,"deepseek-v4.1-flash",8192,false).getJSONObject("received").has("thinking"),"user can retain relay thinking default");
        check(!call(BASE,"echo",8192,true).getJSONObject("received").has("thinking"),"other models receive no DeepSeek parameter");
        JSONObject glm=call(BASE,"glm-5.3-flash",0,true).getJSONObject("received");check(glm.getString("reasoning_effort").equals("low")&&glm.getJSONObject("thinking").getString("type").equals("enabled"),"GLM 5.3 uses supported light reasoning");
        check(!Json.parseObject(Http.complete(BASE,"glm-5.3-flash","fixture-key","x",new JSONObject(),new Http.Control(),0,true,false)).getJSONObject("received").has("reasoning_effort"),"GLM option retains relay defaults");
        JSONObject responses=call(BASE+"/responses","deepseek-v4.1-flash",8192,true).getJSONObject("received");
        check(responses.getInt("max_output_tokens")==8192,"Responses custom limit");
        check(responses.getJSONObject("reasoning").getString("effort").equals("none"),"Responses DeepSeek thinking off");
        check(!call(BASE+"/responses","deepseek-v4.1-flash",8192,false).getJSONObject("received").has("reasoning"),"Responses retains relay defaults when requested");
        JSONObject fallback=call(BASE,"echo-fallback",8192,false).getJSONObject("received");
        check(fallback.getInt("max_completion_tokens")==8192&&!fallback.has("max_tokens"),"parameter adaptation retains chosen limit");
        check(call(BASE,"echo",393216,false).getJSONObject("received").getInt("max_tokens")==393216,"official model maximum can be requested");
        check(!call(BASE+"/responses","deepseek-v4.1-flash",0,true).getJSONObject("received").has("max_output_tokens"),"Responses default sends no app output ceiling");
        for(int limit:new int[]{1023,-1}){boolean rejected=false;try{call(BASE,"echo",limit,false);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid limit rejected before request");}
        int before=count("truncated");Http.OutputLimitException json=truncated("truncated",8192);
        check(json.getMessage().contains("8192")&&json.getMessage().contains("6000")&&json.getMessage().contains("5900"),"truncation reports request, returned usage and reasoning");
        check(!json.getMessage().contains("fixture-private-reasoning")&&!json.getMessage().contains("fixture-key"),"diagnostic excludes content and credentials");
        check(count("truncated")==before+1,"truncation does not trigger paid retry");
        check(truncated("truncated",0).getMessage().contains("未指定请求上限"),"provider-default diagnostic is accurate");
        Http.OutputLimitException sse=truncated("truncated-sse",16000);
        check(sse.getMessage().contains("16000")&&sse.getMessage().contains("5900"),"SSE reads final usage after length marker");
        Http.OutputLimitException res=truncated("truncated-responses",32000);
        check(res.getMessage().contains("32000")&&res.getMessage().contains("7900"),"Responses incomplete diagnostics");
        check(truncated("truncated-responses-sse",16000).getMessage().contains("7900"),"Responses SSE incomplete diagnostics");
        check(call(BASE,"sse-test",16000,false).optBoolean("ok"),"normal Chat SSE");
        check(call(BASE+"/responses","responses-test",16000,false).optBoolean("ok"),"normal Responses JSON");
        check(call(BASE+"/responses","responses-sse",16000,false).optBoolean("ok"),"normal Responses SSE");
        check(call(BASE,"keepalive-sse",0,false).optBoolean("ok"),"leading relay keepalive decoded");
        for(String model:new String[]{"compat-bom","compat-cr","compat-crlf","compat-multiline","compat-terminal-reset","compat-terminal-open","compat-sniff-fragments"}){
            long start=System.nanoTime();
            check(call(BASE,model,0,false).optBoolean("ok")&&count(model)==1,"legal SSE and semantic completion: "+model);
            check(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<5,"completed answer does not wait for TCP close: "+model);
        }
        for(String model:new String[]{"compat-fallback","compat-malformed","compat-premature","compat-unterminated"}){
            Http.Control fallbackControl=new Http.Control();JSONObject full=new JSONObject().put("transcript","[s1] 开始\n[s2] 结尾");
            check(Json.parseObject(Http.complete(BASE,model,"fixture-key","x",full,fallbackControl)).optBoolean("ok")&&count(model)==2,"one ordinary JSON fallback: "+model);
            try(InputStream in=new URL("http://127.0.0.1:18765/payloads").openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                org.json.JSONArray sent=new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray(model);
                check(sent.getJSONObject(0).getBoolean("stream")&&!sent.getJSONObject(1).getBoolean("stream")&&sent.getJSONObject(0).getString("hash").equals(sent.getJSONObject(1).getString("hash")),"fallback changes only transport, preserves complete input: "+model);
            }
            check(!fallbackControl.recoveredFrom.isEmpty()&&!fallbackControl.recoveredFrom.contains("fixture-key")&&fallbackControl.diagnostic().contains("普通 JSON"),"fallback diagnostics retained safely");
        }
        boolean unmarked=false;try{call(BASE,"compat-always-broken",0,false);}catch(IllegalArgumentException e){unmarked=e.getMessage().contains("正常结束标记");}
        check(unmarked&&count("compat-always-broken")==2,"valid JSON without semantic completion cannot become a note");
        boolean broken=false;try{call(BASE,"broken-sse",0,false);}catch(IllegalArgumentException e){broken=e.getMessage().contains("完成标记");}check(broken,"premature EOF never accepted as complete JSON");
        check(count("broken-sse")==2,"broken streams retry only once");
        check(call(BASE,"reset-once",0,false).optBoolean("ok")&&count("reset-once")==2,"one connection reset recovers with one retry");
        check(call(BASE,"eof-once",0,false).optBoolean("ok")&&count("eof-once")==2,"premature EOF recovers with one complete retry");
        check(call(BASE,"chunked-eof-once",0,false).optBoolean("ok")&&count("chunked-eof-once")==2,"plain IOException on broken chunked response recovers");
        Http.Control chunked=new Http.Control();boolean chunkedRejected=false;try{Http.complete(BASE,"chunked-eof-always","fixture-key","x",new JSONObject(),chunked);}catch(Http.InterruptedGenerationException e){chunkedRejected=e.canSplit&&e.getMessage().contains("正文 11 字");}
        check(chunkedRejected&&count("chunked-eof-always")==2,"persistent broken chunked response exposes safe metrics and remains incomplete");
        check(call(BASE,"empty-sse-once",0,false).optBoolean("ok")&&count("empty-sse-once")==2,"ended empty SSE answer retries once without accepting an empty note");
        check(call(BASE,"empty-json-once",0,false).optBoolean("ok")&&count("empty-json-once")==2,"empty JSON answer with null refusal retries once");
        boolean emptyRejected=false;try{call(BASE,"empty-always",0,false);}catch(Http.InterruptedGenerationException e){emptyRejected=e.getMessage().contains("正文为空");}
        check(emptyRejected&&count("empty-always")==2,"persistent empty answer fails after bounded retry");
        boolean tlsRejected=false;Http.Control tls=new Http.Control();try{Http.complete("https://127.0.0.1:18770/v1","fixture","fixture-key","x",new JSONObject(),tls);}catch(Http.InterruptedGenerationException e){tlsRejected=!e.canSplit&&e.getMessage().contains("DNS / TCP / TLS")&&e.getMessage().contains("尚未收到");}
        check(tlsRejected&&count("tls-close")==2,"TLS peer EOF retries once and reports pre-upload state without splitting");
        Http.Control cancelled=new Http.Control();cancelled.cancel();boolean stopped=false;try{Http.complete(BASE,"echo","fixture-key","x",new JSONObject(),cancelled);}catch(java.util.concurrent.CancellationException e){stopped=true;}check(stopped,"pre-cancelled request stops");
        boolean query=false;try{Http.endpoint("https://api.example/v1?key=secret");}catch(IllegalArgumentException e){query=true;}check(query,"credential query still rejected");
        java.util.List<Transcript.Segment> source=new java.util.ArrayList<>();StringBuilder longText=new StringBuilder();for(int i=0;i<900;i++)longText.append("知识");
        for(int i=0;i<7;i++)source.add(new Transcript.Segment("s"+(i+1),10+i,11+i,longText.toString()+i));
        Http.Control pipelineControl=new Http.Control();
        JSONObject detailed=new SummaryPipeline((stage,prompt,context)->Http.complete(BASE,"fixture","fixture-key",prompt,context,pipelineControl,0,false),pipelineControl,(p,m)->{}).run("整段视频",source);
        check(detailed.getInt("section_count")==2&&detailed.getJSONArray("points").length()==4,"HTTP and detailed pipeline integrate across all input chunks");
        check(detailed.getJSONObject("summary").getString("overview").equals("完整测试总结"),"HTTP overview uses all section summaries");
        check(detailed.getJSONObject("summary").getJSONArray("chapters").getJSONObject(0).getDouble("start")==10&&!detailed.toString().contains("forged"),"integrated pipeline retains real chapter time and filters fake evidence");
        Http.Control telemetry=new Http.Control();java.util.concurrent.atomic.AtomicReference<Throwable> problem=new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker=new Thread(()->{try{Http.complete(BASE,"telemetry-sse","fixture-key","x",new JSONObject(),telemetry);}catch(Throwable e){problem.set(e);}});worker.start();
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!telemetry.requestSent&&System.nanoTime()<until)Thread.sleep(20);
        check(telemetry.modelActive&&telemetry.diagnostic().contains("等待模型响应头"),"connection stage reported before response headers");
        until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!telemetry.diagnostic().contains("思考 16 字")&&worker.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
        String live=telemetry.diagnostic();check(live.contains("正文 0 字")&&live.contains("思考 16 字")&&live.contains("最近数据"),"keepalive and reasoning distinguished from final answer");
        check(!live.contains("private-thinking")&&!live.contains("fixture-key"),"live diagnostics never expose reasoning or credentials");
        worker.join(12000);check(!worker.isAlive()&&problem.get()==null&&!telemetry.modelActive&&telemetry.diagnostic().contains("正文 11 字"),"stream metrics finish with complete answer");
        boolean denied=false;int authBefore=count("auth-error");try{call(BASE,"auth-error",0,false);}catch(IllegalArgumentException e){denied=e.getMessage().contains("HTTP 401");}
        check(denied&&count("auth-error")==authBefore+1,"authentication failure does not retry");
        java.util.List<Transcript.Segment> resetSource=new java.util.ArrayList<>();for(int i=0;i<4;i++)resetSource.add(new Transcript.Segment("s"+(i+1),i,i+1,"完整知识点"+i));
        Http.Control resetControl=new Http.Control();int resetBefore=count("split-reset");
        JSONObject recovered=new SummaryPipeline((stage,prompt,context)->Http.complete(BASE,"split-reset","fixture-key",prompt,context,resetControl),resetControl,(p,m)->{}).run("reset video",resetSource);
        check(recovered.getJSONArray("points").length()==4&&recovered.getInt("section_count")==2&&count("split-reset")==resetBefore+9,"network resets after upload recover by splitting extraction and synthesis");
        check(recovered.toString().contains("完整知识点0")&&recovered.toString().contains("完整知识点3"),"recovery retains both source ends and complete details");
        System.out.println("PASS "+checks+" transport checks");
    }
}
