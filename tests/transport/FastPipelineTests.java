package com.suikan.app;

import org.json.*;
import java.util.*;
import java.util.regex.*;

public final class FastPipelineTests {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Model implements SummaryPipeline.Completer{
        int calls,sourceCalls,merges,contextMax,outputMax,failAt,networkAt;boolean network,preUpload,invalid,malformed;
        final Set<String> seen=new LinkedHashSet<>();String promptSeen="";JSONObject lastContext;
        public String complete(String stage,String prompt,JSONObject context)throws Exception{
            calls++;promptSeen=prompt;lastContext=context;if(calls==failAt)throw new IllegalArgumentException("fixture late failure");
            if(malformed)return "{broken";
            if(network||calls==networkAt)throw new Http.InterruptedGenerationException("fixture network",null,!preUpload);
            List<String> ids=new ArrayList<>();
            if(context.has("transcript")){sourceCalls++;String text=context.getString("transcript");Matcher m=Pattern.compile("(?m)^\\[(s[^]\\s]+)\\] ").matcher(text);while(m.find())ids.add(m.group(1));
                if(contextMax>0&&text.length()>contextMax)throw new Http.ContextLimitException("fixture context limit");
                if(outputMax>0&&ids.size()>outputMax)throw new Http.OutputLimitException(new JSONObject(),0);seen.addAll(ids);
            }else{merges++;String data=context.getJSONArray("parts").toString();Matcher m=Pattern.compile("\"s[0-9]+\"").matcher(data);while(m.find()){String id=m.group().replace("\"","");if(!ids.contains(id))ids.add(id);}}
            int count=prompt.contains("300至600")?3:6;JSONArray keys=new JSONArray();
            for(int i=0;i<count;i++){String id=ids.get(i==count-1?ids.size()-1:i%ids.size());keys.put(new JSONObject().put("title","主题"+i).put("detail","主要结论与条件").put("segment_ids",new JSONArray().put(invalid?"forged":id).put("forged")));}
            JSONArray chapters=new JSONArray();if(count==6)chapters.put(new JSONObject().put("title","章节").put("summary","简短章节").put("start",999999).put("segment_ids",new JSONArray().put(ids.get(0))));
            if(invalid)chapters=new JSONArray();
            return new JSONObject().put("overview","全视频概述").put("key_points",keys).put("chapters",chapters).put("steps",new JSONArray()).put("caveats",new JSONArray()).toString();
        }
    }
    static List<Transcript.Segment> small(int count){List<Transcript.Segment> rows=new ArrayList<>();for(int i=1;i<=count;i++)rows.add(new Transcript.Segment("s"+i,i+.125,i+.75,"字幕第"+i+"段"));return rows;}
    static JSONObject run(Model m,String depth,List<Transcript.Segment> rows,SummaryPipeline.Checkpoints cache)throws Exception{return new FastSummaryPipeline(m,new Http.Control(),(p,s)->{},cache,depth).run("测试视频",rows);}
    public static void main(String[] args)throws Exception{
        Model m=new Model();List<Transcript.Segment> rows=small(1077);JSONObject result=new SummaryPipeline(m,new Http.Control(),(p,s)->{},null,SummaryDepth.STANDARD).run("完整1077段",rows,8000);
        check(m.calls==1&&m.sourceCalls==1&&m.merges==0&&result.getInt("model_steps")==1,"1077-cue standard summary uses one generation");
        check(m.seen.size()==1077&&m.seen.contains("s1")&&m.seen.contains("s1077"),"single request includes all source and tail");
        check(m.lastContext.has("transcript")&&!m.lastContext.has("segments")&&!m.lastContext.toString().contains("\"start\""),"wire payload avoids per-cue JSON metadata");
        check(m.lastContext.toString().length()<32000,"1077-cue compact input fits one request");
        check(result.getString("summary_style").equals("standard-single-v3")&&result.getInt("chunk_count")==1,"new cache style and chunk count distinguish fast pipeline");
        check(!result.toString().contains("forged")&&!result.toString().contains("999999"),"forged evidence and invented times removed");
        check(result.getJSONObject("summary").getJSONArray("chapters").getJSONObject(0).getDouble("start")==1.125,"chapter time derived locally at full precision");
        check(result.getJSONArray("points").toString().contains("s1077"),"tail cited by saved evidence");
        Model concise=new Model();JSONObject brief=run(concise,SummaryDepth.CONCISE,rows,null);
        check(concise.calls==1&&concise.promptSeen.contains("300至600")&&m.promptSeen.contains("800至1500"),"both modes generate directly with different editorial prompts");
        check(brief.getJSONObject("summary").getJSONArray("key_points").length()==3&&result.getJSONObject("summary").getJSONArray("key_points").length()==6,"length choice changes real model output");
        check(!LocalStore.markdown(brief).contains("字幕第1077段"),"short Markdown remains short while app evidence remains available");
        boolean rejected=false;
        for(String depth:new String[]{SummaryDepth.CONCISE,SummaryDepth.STANDARD}){
            Model longVideo=new Model();List<Transcript.Segment> longRows=PipelineTests.source(140,240);
            JSONObject longResult=run(longVideo,depth,longRows,null);
            check(longVideo.calls==1&&longVideo.sourceCalls==1&&longVideo.merges==0&&longVideo.seen.size()==140,"long input uses one generation in "+depth);
            check(longVideo.lastContext.getString("transcript").equals(FastSummaryPipeline.compact(longRows))&&longResult.getInt("chunk_count")==1,"whole payload including tail is exact in "+depth);
            Model limited=new Model();limited.contextMax=900;rejected=false;
            try{run(limited,depth,PipelineTests.source(20,100),null);}catch(Http.ContextLimitException e){rejected=e.getMessage().contains("不会分批");}
            check(rejected&&limited.calls==1&&limited.lastContext.getString("transcript").contains("[s20]"),"input limit fails with whole source and no split in "+depth);
            Model truncated=new Model();truncated.outputMax=2;rejected=false;
            try{run(truncated,depth,small(8),null);}catch(Http.OutputLimitException e){rejected=true;}
            check(rejected&&truncated.calls==1,"output truncation fails without splitting in "+depth);
            for(boolean beforeUpload:new boolean[]{false,true}){
                Model reset=new Model();reset.network=true;reset.preUpload=beforeUpload;rejected=false;
                try{run(reset,depth,small(16),null);}catch(Http.InterruptedGenerationException e){rejected=true;}
                check(rejected&&reset.calls==1&&reset.merges==0,"network failure never creates partial calls in "+depth);
            }
            Model maximal=new Model();List<Transcript.Segment> maximum=PipelineTests.source(8000,28);
            run(maximal,depth,maximum,null);
            check(maximal.calls==1&&maximal.seen.size()==8000&&maximal.lastContext.getString("transcript").length()>300000,"maximum supported cues retain all text in one request in "+depth);
        }
        Model bad=new Model();bad.invalid=true;rejected=false;try{run(bad,SummaryDepth.STANDARD,small(3),null);}catch(IllegalArgumentException e){rejected=true;}check(rejected&&bad.calls==1,"invalid references cannot create a successful note");
        Model malformed=new Model();malformed.malformed=true;rejected=false;try{run(malformed,SummaryDepth.STANDARD,small(3),null);}catch(Exception e){rejected=true;}check(rejected&&malformed.calls==1,"invalid JSON is rejected without paid repair calls");
        Http.Control cancelled=new Http.Control();cancelled.cancel();Model stopped=new Model();rejected=false;try{new FastSummaryPipeline(stopped,cancelled,(p,s)->{},null,SummaryDepth.STANDARD).run("x",small(3));}catch(java.util.concurrent.CancellationException e){rejected=true;}check(rejected&&stopped.calls==0,"cancellation stops before request");
        Map<String,JSONObject> memory=new HashMap<>();SummaryPipeline.Checkpoints cache=new SummaryPipeline.Checkpoints(){String id(String k,String p,JSONObject c){return Json.hash(k+p+c);}public JSONObject load(String k,String p,JSONObject c){return memory.get(id(k,p,c));}public void save(String k,String p,JSONObject c,JSONObject r){memory.put(id(k,p,c),new JSONObject(r.toString()));}};
        Model failed=new Model();failed.failAt=1;List<Transcript.Segment> resumeRows=PipelineTests.source(140,240);rejected=false;
        try{run(failed,SummaryDepth.STANDARD,resumeRows,cache);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected&&memory.isEmpty(),"failed whole generation saves no partial checkpoint");
        Model resume=new Model();JSONObject resumed=run(resume,SummaryDepth.STANDARD,resumeRows,cache);
        check(resume.calls==1&&memory.size()==1&&resumed.getJSONArray("points").toString().contains("s140"),"only a complete whole-video result is cached");
        Model cached=new Model();JSONObject restored=run(cached,SummaryDepth.STANDARD,resumeRows,cache);
        check(cached.calls==0&&restored.getInt("chunk_count")==1,"complete whole-video checkpoint can be restored");
        Model otherDepth=new Model();run(otherDepth,SummaryDepth.CONCISE,resumeRows,cache);check(otherDepth.sourceCalls==1,"different depth cannot reuse the other mode's model checkpoints");
        check(!SummaryDepth.style(SummaryDepth.STANDARD).equals("standard-direct-v2")&&!SummaryDepth.style(SummaryDepth.CONCISE).equals("concise-direct-v2"),"old batched cache identities cannot match new modes");
        check(SummaryDepth.style(SummaryDepth.DETAILED).equals(SummaryPipeline.STYLE),"detailed cache identity unchanged");
        check(SummaryDraft.overview("{\"overview\":\"还在生成").equals("还在生成"),"unfinished JSON overview is previewable");
        check(SummaryDraft.overview("{\"overview\":\"首行\\n\\u5b57\\\"引号\"}").equals("首行\n字\"引号"),"draft decodes escaped newline, Unicode and quotes");
        check(SummaryDraft.overview("{\"overview\":\"结尾\\u5b").equals("结尾"),"partial Unicode escape waits for remaining bytes");
        check(SummaryDraft.overview("{\"reasoning_content\":\"private-thinking\"}").isEmpty(),"draft never exposes reasoning");
        Http.Control preview=new Http.Control();preview.previewEnabled=true;preview.beginModel();preview.outputText("{\"overview\":\"这是正在输出的概述");check(preview.preview.contains("正在输出"),"transport publishes bounded text preview");preview.beginAttempt(2);check(preview.preview.isEmpty(),"retry clears draft from failed attempt");
        check(Http.contextLimit(400,"context_length_exceeded")&&Http.contextLimit(413,"body too large")&&!Http.contextLimit(401,"context_length_exceeded")&&!Http.contextLimit(429,"context window")&&!Http.contextLimit(400,"max_tokens unsupported"),"input limits remain distinct from authentication and rate limits");
        boolean contextError=false;try{Http.complete("http://127.0.0.1:18765/v1","context-error","fixture","x",new JSONObject(),new Http.Control());}catch(Http.ContextLimitException e){contextError=true;}check(contextError,"actual HTTP context error has dedicated type");
        System.out.println("PASS "+checks+" fast summary checks");
    }
}
