package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class FeatureTests {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject source(String text)throws Exception{JSONArray rows=new JSONArray();for(Transcript.Segment s:Transcript.parse(text))rows.put(Json.segment(s));return new JSONObject().put("segments",rows);}
    public static void main(String[] args)throws Exception{
        JSONObject timed=source("1\n00:00:01,125 --> 00:00:03,500\n第一行\n第二行\n\n2\n01:00:05,050 --> 01:00:06,250\n末尾字幕\n");
        check(SubtitleFiles.text(timed,false).equals("第一行\n第二行\n末尾字幕\n"),"copy preserves all lines and tail");
        check(SubtitleFiles.text(timed,true).contains("[1:00:05] 末尾字幕"),"timed TXT retains real times");
        check(SubtitleFiles.canSrt(timed),"complete timeline enables SRT");String exported=SubtitleFiles.srt(timed);
        check(exported.contains("00:00:01,125 --> 00:00:03,500")&&exported.contains("01:00:05,050 --> 01:00:06,250"),"SRT preserves milliseconds and hours");
        check(Transcript.parse(exported).size()==2&&Transcript.parse(exported).get(1).text.equals("末尾字幕"),"SRT roundtrip includes final cue");
        JSONObject plain=source("首行\n尾行");check(!SubtitleFiles.canSrt(plain)&&!SubtitleFiles.text(plain,true).contains("无时间戳"),"plain text never invents times");
        boolean rejected=false;try{SubtitleFiles.srt(plain);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"SRT with missing times fails explicitly");
        check(!SubtitleFiles.canSrt(source("[00:01] 只有起点")),"start-only text remains TXT");
        JSONObject full=new JSONObject();JSONArray rows=new JSONArray();for(int i=0;i<1077;i++)rows.put(Json.segment(new Transcript.Segment("s"+i,i+.125,i+.75,"字幕第"+i+"段")));full.put("segments",rows);
        check(SubtitleFiles.text(full,false).split("\n").length==1077&&SubtitleFiles.srt(full).contains("字幕第1076段"),"1077 cues survive copy and save");
        check(SummaryDepth.normalize("unknown").equals(SummaryDepth.STANDARD),"unknown selection defaults to standard");
        JSONObject identity=new JSONObject().put("bvid","BV1fX4y1Q7Ux").put("page",1).put("transcript_hash","fixture");String legacy=LocalStore.id(identity);
        identity.put("summary_depth",SummaryDepth.DETAILED);check(LocalStore.id(identity).equals(legacy),"old detailed note identity preserved");
        identity.put("summary_depth",SummaryDepth.STANDARD);String standardId=LocalStore.id(identity);identity.put("summary_depth",SummaryDepth.CONCISE);check(!legacy.equals(standardId)&&!standardId.equals(LocalStore.id(identity)),"all three depth caches isolated");
        System.out.println("PASS "+checks+" subtitle and depth checks");
    }
}
