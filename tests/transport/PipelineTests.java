package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs the production detailed pipeline with deterministic model responses, no paid calls. */
public final class PipelineTests {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static String repeat(String text,int times){StringBuilder b=new StringBuilder();for(int i=0;i<times;i++)b.append(text);return b.toString();}
    static List<Transcript.Segment> source(int count,int chars){
        List<Transcript.Segment> result=new ArrayList<>();
        for(int i=0;i<count;i++)result.add(new Transcript.Segment("s"+(i+1),10+i,11+i,repeat("知识",chars/2)+"·案例-"+i+(i==count-1?"·末尾不可遗漏":"")));
        return result;
    }
    static final class Model implements SummaryPipeline.Completer {
        int fixedPoints,extractThreshold,synthThreshold,extractCalls,synthCalls,overviewCalls,failExtractAt,failSynthAt;
        boolean badEvidence,badSummary,alwaysExtractLimit,overviewLimit,timeout,network,connected=true,alwaysNetwork,overviewNetwork;
        @Override public String complete(String stage,String prompt,JSONObject context) throws Exception{
            if(context.has("segments")){
                extractCalls++;if(timeout)throw new SocketTimeoutException("fixture timeout");
                if(failExtractAt==extractCalls)throw new IllegalArgumentException("fixture late failure");
                JSONArray segments=context.getJSONArray("segments");
                if(network&&(alwaysNetwork||segments.length()>2))throw new Http.InterruptedGenerationException("fixture reset",null,connected);
                if(alwaysExtractLimit||(extractThreshold>0&&segments.length()>extractThreshold))throw new Http.OutputLimitException(new JSONObject(),0);
                JSONArray points=new JSONArray();int count=fixedPoints>0?fixedPoints:segments.length();
                for(int i=0;i<count;i++){
                    JSONObject s=segments.getJSONObject(i%segments.length()),last=segments.getJSONObject(segments.length()-1);
                    JSONArray refs=new JSONArray().put(badEvidence?"forged":s.getString("segment_id")).put("forged");
                    if(fixedPoints==1&&!badEvidence)refs.put(last.getString("segment_id"));
                    points.put(new JSONObject().put("title","主题-"+i).put("detail",s.getString("text")+repeat("视频给出的论据、案例与适用条件。",24)+"·详细条目-"+i).put("evidence_segment_ids",refs));
                }
                return new JSONObject().put("points",points).toString();
            }
            if(context.has("points")){
                synthCalls++;JSONArray points=context.getJSONArray("points");
                if(failSynthAt==synthCalls)throw new IllegalArgumentException("fixture late failure");
                if(network&&(alwaysNetwork||points.length()>2))throw new Http.InterruptedGenerationException("fixture reset",null,connected);
                if(synthThreshold>0&&points.length()>synthThreshold)throw new Http.OutputLimitException(new JSONObject(),0);
                JSONArray keys=new JSONArray(),chapters=new JSONArray(),steps=new JSONArray();
                for(int i=0;i<points.length();i++){
                    JSONObject p=points.getJSONObject(i);JSONArray refs=new JSONArray().put(badSummary?"forged":p.getString("id")).put("forged");
                    keys.put(new JSONObject().put("title",p.getString("title")).put("detail",p.getString("detail")).put("point_ids",refs));
                    chapters.put(new JSONObject().put("title","详解-"+p.getString("id")).put("summary",p.getString("detail")).put("point_ids",refs).put("start",999999));
                    JSONArray items=new JSONArray();for(int j=0;j<8;j++)items.put("操作步骤-"+j+"，保留前提与解释。");
                    steps.put(new JSONObject().put("title","方法-"+p.getString("id")).put("items",items).put("point_ids",refs));
                }
                return new JSONObject().put("overview","本段总览："+points.getJSONObject(0).getString("id")+"-"+points.getJSONObject(points.length()-1).getString("id"))
                    .put("key_points",keys).put("chapters",chapters).put("steps",steps).put("caveats",new JSONArray()).toString();
            }
            overviewCalls++;if(overviewLimit)throw new Http.OutputLimitException(new JSONObject(),0);
            if(overviewNetwork)throw new Http.InterruptedGenerationException("fixture reset",null,false);
            return new JSONObject().put("overview","整体脉络："+context.getJSONArray("section_overviews").toString()).toString();
        }
    }
    static JSONObject run(Model m,List<Transcript.Segment> input)throws Exception{
        return new SummaryPipeline(m,new Http.Control(),(p,s)->{}).run("完整视频",input);
    }
    public static void main(String[] args)throws Exception{
        Model verbose=new Model();verbose.fixedPoints=41;JSONObject full=run(verbose,source(2,100));
        JSONObject summary=full.getJSONObject("summary");
        check(full.getJSONArray("points").length()==41,"no old 40-point cutoff");
        check(summary.getJSONArray("key_points").length()==41,"no top eight cutoff");
        check(summary.getJSONArray("chapters").length()==41,"no twelve chapter cutoff");
        check(summary.getJSONArray("steps").length()==41&&summary.getJSONArray("steps").getJSONObject(40).getJSONArray("items").length()==8,"method and step counts retained");
        check(summary.toString().length()>5000&&summary.getJSONArray("key_points").getJSONObject(0).getString("detail").length()>180,"full detailed text retained");
        check(summary.getJSONArray("chapters").getJSONObject(0).getDouble("start")==10,"chapter position comes from source");
        check(!full.getJSONArray("points").toString().contains("forged")&&!summary.toString().contains("forged"),"invalid references filtered");
        full.put("title","详细笔记").put("source_label","fixture");
        String markdown=LocalStore.markdown(full);
        check(markdown.contains("详细条目-40")&&markdown.contains("操作步骤-7")&&markdown.length()>5000,"full detail reaches Markdown export");
        check(SummaryPipeline.STYLE.equals(full.getString("summary_style")),"detailed cache is distinguishable from old concise results");
        check(!SummaryPipeline.EXTRACT.contains("最多")&&!SummaryPipeline.SYNTHESIZE.contains("不超过")&&!SummaryPipeline.SYNTHESIZE.contains("5000"),"prompts contain no artificial detail ceiling");

        Model extractSplit=new Model();extractSplit.extractThreshold=2;JSONObject split=run(extractSplit,source(4,100));
        check(extractSplit.extractCalls==3&&split.getJSONArray("points").length()==4,"truncated extraction splits and keeps both halves");
        Set<String> cited=new HashSet<>();JSONArray ps=split.getJSONArray("points");
        for(int i=0;i<ps.length();i++){JSONArray refs=ps.getJSONObject(i).getJSONArray("evidence_segment_ids");for(int j=0;j<refs.length();j++)cited.add(refs.getString(j));}
        check(cited.contains("s1")&&cited.contains("s4")&&cited.size()==4,"first and last source survive extraction recovery");

        Model synthSplit=new Model();synthSplit.synthThreshold=2;JSONObject detailed=run(synthSplit,source(6,100));
        check(synthSplit.synthCalls==7&&detailed.getInt("section_count")==4,"truncated synthesis splits progressively");
        check(detailed.getJSONObject("summary").getJSONArray("key_points").length()==6&&detailed.getJSONObject("summary").toString().contains("末尾不可遗漏"),"all detailed sections survive synthesis recovery");
        Model overviewSplit=new Model();overviewSplit.synthThreshold=2;overviewSplit.overviewLimit=true;JSONObject combined=run(overviewSplit,source(6,100));
        check(combined.getJSONObject("summary").getString("overview").contains("v1")&&combined.getJSONObject("summary").getString("overview").contains("v6"),"overview limit falls back to complete ordered section overviews");
        check(combined.getJSONObject("summary").getJSONArray("caveats").toString().contains("分段详解保留")&&combined.getJSONObject("summary").getJSONArray("chapters").length()==6,"overview fallback reports limitation without dropping detail");

        Model longVideo=new Model();longVideo.fixedPoints=1;JSONObject longNote=run(longVideo,source(6000,20));
        check(longNote.getInt("chunk_count")>40&&longVideo.extractCalls==longNote.getInt("chunk_count"),"long video has no old 40-chunk cutoff");

        Model invalid=new Model();invalid.badEvidence=true;boolean failed=false;try{run(invalid,source(2,100));}catch(IllegalArgumentException e){failed=true;}
        check(failed&&invalid.synthCalls==0,"unsupported evidence prevents success");
        Model invalidSummary=new Model();invalidSummary.badSummary=true;failed=false;try{run(invalidSummary,source(2,100));}catch(IllegalArgumentException e){failed=true;}
        check(failed,"unsupported summary references prevent success");
        Model smallest=new Model();smallest.alwaysExtractLimit=true;failed=false;try{run(smallest,source(1,100));}catch(IllegalArgumentException e){failed=e.getMessage().contains("服务商默认");}
        check(failed&&smallest.extractCalls==1,"unsplittable request fails explicitly without looping");
        Model timed=new Model();timed.timeout=true;failed=false;try{run(timed,source(4,100));}catch(IllegalArgumentException e){failed=e.getCause() instanceof SocketTimeoutException&&e.getMessage().contains("分析字幕");}
        check(failed&&timed.extractCalls==1,"timeouts never cause automatic paid retries");
        Model cancelled=new Model();Http.Control stop=new Http.Control();stop.cancel();failed=false;
        try{new SummaryPipeline(cancelled,stop,(p,s)->{}).run("x",source(2,100));}catch(java.util.concurrent.CancellationException e){failed=true;}
        check(failed&&cancelled.extractCalls==0,"cancellation still stops before request");
        Model reset=new Model();reset.network=true;JSONObject resetNote=run(reset,source(4,100));
        check(reset.extractCalls==3&&reset.synthCalls==3&&resetNote.getJSONArray("points").length()==4&&resetNote.getInt("section_count")==2,"interrupted generation splits without dropping either half");
        Model unreachable=new Model();unreachable.network=true;unreachable.connected=false;failed=false;
        try{run(unreachable,source(4,100));}catch(IllegalArgumentException e){failed=e.getMessage().contains("fixture reset");}
        check(failed&&unreachable.extractCalls==1,"pre-upload connection failure never creates smaller paid calls");
        Model persistent=new Model();persistent.network=true;persistent.alwaysNetwork=true;failed=false;
        try{run(persistent,source(16,100));}catch(IllegalArgumentException e){failed=true;}
        check(failed&&persistent.extractCalls==3,"persistent resets stop after two split levels");
        Model globalBudget=new Model();globalBudget.network=true;failed=false;
        try{run(globalBudget,source(8,100));}catch(IllegalArgumentException e){failed=true;}
        check(failed&&globalBudget.synthCalls==1,"task-wide split budget bounds repeated failures");
        Model overviewReset=new Model();overviewReset.synthThreshold=2;overviewReset.overviewNetwork=true;JSONObject kept=run(overviewReset,source(6,100));
        check(kept.getJSONObject("summary").getJSONArray("chapters").length()==6&&kept.getJSONObject("summary").getJSONArray("caveats").toString().contains("连接中断"),"overview connection failure preserves complete ordered detail with caveat");
        final java.util.Map<String,JSONObject> saved=new java.util.HashMap<>();
        SummaryPipeline.Checkpoints checkpoints=new SummaryPipeline.Checkpoints(){
            public JSONObject load(String kind,String prompt,JSONObject context){return saved.get(Json.hash(kind+prompt+context));}
            public void save(String kind,String prompt,JSONObject context,JSONObject value){saved.put(Json.hash(kind+prompt+context),value);}
        };
        Model resumed=new Model();resumed.failExtractAt=2;List<Transcript.Segment> resumeSource=source(5,1000);failed=false;
        try{new SummaryPipeline(resumed,new Http.Control(),(p,m)->{},checkpoints).run("resume",resumeSource,2000);}catch(IllegalArgumentException e){failed=true;}
        check(failed&&saved.size()==1,"only a complete validated extraction is checkpointed before later failure");
        JSONObject resumedNote=new SummaryPipeline(resumed,new Http.Control(),(p,m)->{},checkpoints).run("resume",resumeSource,2000);
        check(resumed.extractCalls==6&&resumedNote.getJSONArray("points").length()==5,"retry skips completed extraction and restores valid global point IDs");
        saved.clear();Model resumedDetails=new Model();resumedDetails.failSynthAt=2;failed=false;
        try{new SummaryPipeline(resumedDetails,new Http.Control(),(p,m)->{},checkpoints).run("resume details",resumeSource,2000);}catch(IllegalArgumentException e){failed=true;}
        check(failed&&saved.size()==6,"completed extraction and first detail survive later synthesis failure");
        JSONObject finalDetails=new SummaryPipeline(resumedDetails,new Http.Control(),(p,m)->{},checkpoints).run("resume details",resumeSource,2000);
        check(resumedDetails.extractCalls==5&&resumedDetails.synthCalls==6&&finalDetails.getJSONObject("summary").getJSONArray("chapters").length()==5,"retry skips all completed model work and retains all chapters");
        System.out.println("PASS "+checks+" detailed pipeline checks");
    }
}
