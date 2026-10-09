package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Shared acquisition path. It never reads model credentials or calls a model. */
final class VideoSource {
    static JSONObject read(String input,String imported,Http.Control control,BiliClient.Progress progress)throws Exception{
        control.check();List<Transcript.Segment> segments;JSONObject meta;
        if(!imported.trim().isEmpty()){
            progress.update(5,"正在读取导入的完整文稿");segments=Transcript.parse(imported);
            meta=new JSONObject().put("title","导入视频文稿").put("page",1).put("source_label","用户导入文稿");
            if(!input.trim().isEmpty()){
                VideoLink link=VideoLink.parse(input,url->Http.resolveShort(url,control));
                try{meta=new BiliClient(control).metadata(link).put("source_label","用户导入文稿");}
                catch(java.util.concurrent.CancellationException e){throw e;}
                catch(Exception e){meta.put("bvid",link.bvid).put("url",link.url).put("page",link.page).put("title",link.bvid+" · 导入文稿");}
            }
        }else{BiliClient.Video video=new BiliClient(control).fetch(input,progress);meta=video.meta;segments=video.segments;}
        JSONArray all=new JSONArray();for(Transcript.Segment s:segments){control.check();all.put(Json.segment(s));}
        return meta.put("segments",all).put("transcript_hash",Json.hash(all.toString()));
    }
    static List<Transcript.Segment> segments(JSONObject source)throws Exception{
        java.util.ArrayList<Transcript.Segment> rows=new java.util.ArrayList<>();JSONArray all=Json.array(source,"segments");
        for(int i=0;i<all.length();i++){JSONObject s=all.getJSONObject(i);rows.add(new Transcript.Segment(s.getString("segment_id"),s.optDouble("start",-1),s.optDouble("end",-1),s.getString("text")));}return rows;
    }
}
