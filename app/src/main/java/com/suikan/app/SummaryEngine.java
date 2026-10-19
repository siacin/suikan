package com.suikan.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Set;

final class SummaryEngine {
    private final SecureSettings settings; private final LocalStore store; private final Http.Control control;
    private final Context appContext;
    SummaryEngine(Context c, Http.Control control) {appContext=c.getApplicationContext(); settings=new SecureSettings(c); store=new LocalStore(c); this.control=control; }
    static JSONArray validRefs(JSONArray input, Set<String> valid) { return SummaryPipeline.validRefs(input,valid); }
    JSONObject run(String input, String imported, boolean force, BiliClient.Progress progress) throws Exception {
        return run(input,imported,force,settings.summaryDepth(),progress);
    }
    JSONObject run(String input,String imported,boolean force,String chosenDepth,BiliClient.Progress progress)throws Exception{
        String depth=SummaryDepth.normalize(chosenDepth);
        String endpoint=settings.endpoint(), model=settings.model();
        int outputTokens=settings.outputTokens(); boolean disableThinking=settings.disableDeepSeekThinking(), lightGlmThinking=settings.lightGlmThinking();
        if(endpoint.isEmpty()||model.isEmpty())throw new IllegalArgumentException("请先在设置中填写模型 API 地址、密钥和模型名称");
        String key=settings.key();JSONObject meta=VideoSource.read(input,imported,control,progress).put("summary_depth",depth);List<Transcript.Segment> segments=VideoSource.segments(meta);
        UsageAnalytics analytics=UsageAnalytics.get(appContext);String event=analytics.videoStarted(meta,depth);int outcome=UsageCounter.FAILED;boolean fromCache=false;
        try{
        if(!force)try{
            JSONObject cached=store.read(LocalStore.id(meta));
            if(SourceIdentity.reusable(cached,meta)&&cached.has("summary")&&SummaryDepth.style(depth).equals(cached.optString("summary_style"))&&model.equals(cached.optString("model"))&&Http.endpoint(endpoint).equals(cached.optString("model_endpoint"))){
                SourceIdentity.bind(cached,input,imported);
                cached.put("shared_title",meta.optString("shared_title")).put("share_title_checked",meta.optBoolean("share_title_checked"));
                control.check();progress.update(100,"已读取本机保存的"+SummaryDepth.label(depth)+"总结");outcome=UsageCounter.SUCCEEDED;fromCache=true;return cached;
            }
        }catch(Exception ignored){}
        JobCache checkpoints=new JobCache(appContext,new JSONObject().put("source",SourceIdentity.fingerprint(meta)).put("endpoint",Http.endpoint(endpoint)).put("model",model).put("output_tokens",outputTokens).put("deepseek",disableThinking).put("glm",lightGlmThinking).put("style",SummaryDepth.style(depth)),force);
        SummaryPipeline pipeline=new SummaryPipeline((stage,prompt,context)->Http.complete(endpoint,model,key,prompt,context,control,outputTokens,disableThinking,lightGlmThinking),control,(p,m)->progress.update(p,m),checkpoints,depth);
        JSONObject result=pipeline.run(meta.optString("title"),segments,SummaryPipeline.sourceBudget(model));
        for(String field:new String[]{"summary","points","chunk_count","section_count","summary_style"})meta.put(field,result.get(field));
        if(result.has("model_steps"))meta.put("model_steps",result.getInt("model_steps"));
        meta.put("model",model).put("model_endpoint",Http.endpoint(endpoint));
        control.check();store.save(meta);checkpoints.clear();progress.update(100,SummaryDepth.label(depth)+"视频笔记已保存");outcome=UsageCounter.SUCCEEDED;return meta;
        }finally{analytics.videoFinished(event,control.cancelled.get()?UsageCounter.CANCELLED:outcome,fromCache);}
    }
}
