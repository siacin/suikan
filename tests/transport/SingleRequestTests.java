package com.suikan.app;

import org.json.*;
import java.util.*;
import java.net.URL;
import java.io.*;

/** Checks production HTTP payloads against a local model, including complete-source retries. */
public final class SingleRequestTests {
    static int checks;
    static final String BASE="http://127.0.0.1:18765";
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static JSONObject get(String path)throws Exception{
        try(InputStream in=new URL(BASE+path).openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    static JSONObject run(String model,String depth,List<Transcript.Segment> rows,boolean responses)throws Exception{
        Http.Control control=new Http.Control();
        return new SummaryPipeline((stage,prompt,context)->Http.complete(BASE+(responses?"/v1/responses":"/v1"),model,"fixture-key",prompt,context,control),control,(p,s)->{},null,depth).run("长视频",rows,512);
    }
    static void payload(String model,List<Transcript.Segment> rows,int expected)throws Exception{
        JSONArray requests=get("/payloads").getJSONArray(model);String text=FastSummaryPipeline.compact(rows);
        check(requests.length()==expected&&get("/counts").getInt(model)==expected,"bounded actual POST count: "+model);
        for(int i=0;i<requests.length();i++){
            JSONObject request=requests.getJSONObject(i);
            check(request.getString("hash").equals(Json.hash(text))&&request.getInt("cues")==rows.size()&&request.getInt("chars")==text.length()&&!request.getBoolean("parts"),"exact full subtitle payload: "+model+" attempt "+i);
            check(request.getString("first").equals(rows.get(0).id)&&request.getString("last").equals(rows.get(rows.size()-1).id),"first and final cue sent together: "+model);
            check(request.getBoolean("stream")== (i==0),"initial stream then ordinary JSON full retry: "+model);
        }
    }
    public static void main(String[] args)throws Exception{
        List<Transcript.Segment> rows=PipelineTests.source(140,240);
        check(FastSummaryPipeline.compact(rows).length()>32000,"input exceeds previous batching threshold");
        for(String depth:new String[]{SummaryDepth.CONCISE,SummaryDepth.STANDARD}){
            for(boolean responses:new boolean[]{false,true}){
                String model="single-ok-"+depth+(responses?"-responses":"-chat");JSONObject note=run(model,depth,rows,responses);
                check(note.getInt("chunk_count")==1&&note.getInt("model_steps")==1&&note.getJSONArray("points").toString().contains("s140"),"one whole-video result and tail evidence: "+model);
                payload(model,rows,1);
            }
            String retry="single-retry-"+depth;JSONObject note=run(retry,depth,rows,false);
            check(note.getInt("chunk_count")==1&&note.getInt("model_steps")==1,"retry remains one whole generation");payload(retry,rows,2);
            for(String kind:new String[]{"input","output","drop"}){
                String model="single-"+kind+"-"+depth;boolean rejected=false;
                try{run(model,depth,rows,false);}
                catch(Http.ContextLimitException e){rejected=kind.equals("input")&&e.getMessage().contains("不会分批");}
                catch(Http.OutputLimitException e){rejected=kind.equals("output");}
                catch(Http.InterruptedGenerationException e){rejected=kind.equals("drop");}
                check(rejected,"failure rejects incomplete result: "+model);payload(model,rows,kind.equals("drop")?2:1);
            }
        }
        System.out.println("PASS "+checks+" whole-transcript HTTP checks");
    }
}
