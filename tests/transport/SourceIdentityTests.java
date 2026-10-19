package com.suikan.app;

import org.json.*;

/** Runs the production acquisition path against deterministic API responses; never calls a model. */
public final class SourceIdentityTests {
    static int checks;
    static final String BV="BV1fX4y1Q7Ux", OTHER="BV1tBHm6MEHi", LINK="https://www.bilibili.com/video/"+BV;
    interface Edit { void apply(Fixture f)throws Exception; }
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final class Fixture implements BiliClient.Transport {
        JSONObject view,player,body;String redirected=LINK,url="https://aisubtitle.hdslb.com/bfs/subtitle/test.json";
        int views,downloads,fallbacks;boolean mutateTitle,mutateCid,firstUnavailable;String requested;
        Fixture()throws Exception{
            JSONArray pages=new JSONArray().put(new JSONObject().put("page",1).put("cid",101).put("duration",60).put("part","第一 P"))
                    .put(new JSONObject().put("page",2).put("cid",202).put("duration",90).put("part","第二 P"));
            view=new JSONObject().put("code",0).put("data",new JSONObject().put("bvid",BV).put("aid",170001).put("cid",101).put("title","测试视频标题").put("pages",pages));
            player=new JSONObject().put("code",0).put("data",new JSONObject().put("aid",170001).put("bvid",BV).put("cid",101).put("page_no",1)
                    .put("subtitle",new JSONObject().put("subtitles",new JSONArray().put(new JSONObject().put("id_str","12345678901234567890").put("lan","zh-CN").put("subtitle_url",url)))));
            body=new JSONObject().put("body",new JSONArray().put(new JSONObject().put("from",0.1).put("to",4).put("content","目标视频的字幕原文")));
        }
        JSONObject track()throws Exception{return player.getJSONObject("data").getJSONObject("subtitle").getJSONArray("subtitles").getJSONObject(0);}
        public String resolve(String initial){return redirected;}
        public JSONObject get(String address)throws Exception{
            if(address.contains("/x/web-interface/view")){
                views++;JSONObject copy=new JSONObject(view.toString());
                if(views>1&&mutateTitle)copy.getJSONObject("data").put("title","另一个标题");
                if(views>1&&mutateCid)copy.getJSONObject("data").getJSONArray("pages").getJSONObject(0).put("cid",999);
                return copy;
            }
            if(address.contains("/x/player/")){
                requested=address;if(address.contains("/x/player/v2"))fallbacks++;
                if(firstUnavailable&&address.contains("/wbi/"))return new JSONObject().put("code",-400);
                return new JSONObject(player.toString());
            }
            downloads++;return new JSONObject(body.toString());
        }
        BiliClient.Video fetch(String input)throws Exception{return new BiliClient(new Http.Control(),this).fetch(input,(p,m)->{});}
    }
    static void mismatch(String name,Edit edit,boolean beforeDownload)throws Exception{
        Fixture f=new Fixture();edit.apply(f);boolean rejected=false;
        try{f.fetch(LINK);}catch(SourceIdentity.Mismatch e){rejected=e.getMessage().contains("已停止读取");}
        check(rejected,name);if(beforeDownload)check(f.downloads==0,name+" stops before subtitle download");
        check(f.fallbacks==0,name+" cannot be hidden by another endpoint");
    }
    static void badLink(String value,VideoLink.Resolver resolver)throws Exception{
        boolean failed=false;try{VideoLink.parse(value,resolver);}catch(IllegalArgumentException e){failed=true;}
        check(failed,"ambiguous or mismatching link rejected");
    }
    static void sharedTitle(String actual,String caption,boolean accepted)throws Exception{
        Fixture fixture=new Fixture();fixture.view.getJSONObject("data").put("title",actual);
        BiliClient.Video video=null;SourceIdentity.Mismatch error=null;
        try{video=fixture.fetch(caption+" "+LINK);}catch(SourceIdentity.Mismatch e){error=e;}
        check(accepted?video!=null:error!=null,"share title comparison: "+caption+" -> "+actual);
        if(accepted){
            check(video.meta.getString("shared_title").equals(caption)&&video.meta.getBoolean("share_title_checked"),"literal share caption retained and verified");
            check(fixture.views==2&&fixture.downloads==1&&video.meta.getLong("cid")==101,"accepted title still follows full source checks");
        }else{
            check(fixture.downloads==0&&fixture.fallbacks==0,"wrong title stops before download, no fallback");
            check(error.getMessage().contains("分享：")&&error.getMessage().contains("；视频："),"mismatch identifies both titles");
        }
    }
    public static void main(String[] args)throws Exception{
        // 2026-10-14: these titles literally contain the brackets used by share captions.
        check(VideoLink.parse("【什么是2.0】 https://b23.tv/C9D59Af",u->LINK).sharedTitle.equals("【什么是2.0】"),"literal brackets never removed during parsing");
        sharedTitle("【什么是2.0】","【什么是2.0】",true);
        sharedTitle("【牛末不牛初的应对】","【牛末不牛初的应对】",true);
        sharedTitle("【什么是2.0】","【【什么是2.0】】",true);
        sharedTitle("什么是2.0","【什么是2.0】",true);
        sharedTitle("什么是2.0","什么是2.0",true);
        sharedTitle("【什么是2.0】","【什么是2.0】-哔哩哔哩",true);
        sharedTitle("【什么是2.0】","【【什么是2.0】-哔哩哔哩】",true);
        sharedTitle("测试视频标题","【测试视频标题-哔哩哔哩】",true);
        sharedTitle("测试视频标题","测试视频标题_哔哩哔哩_bilibili",true);
        sharedTitle("标题-哔哩哔哩","标题-哔哩哔哩",true);
        sharedTitle("【什么是2.0】","【什么是２．０】",true);
        sharedTitle("【什么是2.0】","【 什么是2.0 】",true);
        sharedTitle("【什么是2.0】","【什么是20】",false);
        sharedTitle("【牛末不牛初的应对】","【牛末不牛初的应对续篇】",false);
        sharedTitle("【什么是2.0】","【牛末不牛初的应对】",false);
        sharedTitle("什么是2.0","【【什么是2.0】】",false);
        sharedTitle("什么是2.0","【什么是2.0",false);
        sharedTitle("什么是2.0","什么是2.0 - 任意后缀",false);
        boolean strictTitle=false;try{SourceIdentity.equalTitle("【什么是2.0】","什么是2.0");}catch(SourceIdentity.Mismatch e){strictTitle=true;}
        check(strictTitle,"API-to-API title comparison remains strict about literal brackets");
        Fixture good=new Fixture();BiliClient.Video first=good.fetch("【测试视频标题-哔哩哔哩】 "+LINK);
        check(first.meta.getString("bvid").equals(BV)&&first.meta.getLong("cid")==101,"link and first part identity retained");
        check(good.views==2&&good.downloads==1,"download followed by metadata and title recheck");
        check(first.meta.getBoolean("share_title_checked")&&first.segments.get(0).text.equals("目标视频的字幕原文"),"shared title checked; body kept verbatim");
        check(first.meta.getString("subtitle_id").equals("12345678901234567890"),"large subtitle identity is never rounded");
        check(new Fixture().fetch("测试视频标题 "+LINK).meta.getBoolean("share_title_checked"),"plain leading share title is checked");
        Fixture plainWrong=new Fixture();boolean wrongPlain=false;try{plainWrong.fetch("其他视频标题 "+LINK);}catch(SourceIdentity.Mismatch e){wrongPlain=true;}check(wrongPlain&&plainWrong.downloads==0,"plain wrong title blocks download");
        Fixture second=new Fixture();second.player.getJSONObject("data").put("cid",202).put("page_no",2);
        BiliClient.Video p2=second.fetch(LINK+"?p=2");check(p2.meta.getLong("cid")==202&&second.requested.contains("cid=202")&&p2.meta.getString("title").contains("第二 P"),"P2 never falls back to P1");
        Fixture fallback=new Fixture();fallback.firstUnavailable=true;fallback.fetch(LINK);check(fallback.fallbacks==1,"ordinary unavailable first endpoint uses checked fallback");
        mismatch("metadata wrong BV",f->f.view.getJSONObject("data").put("bvid",OTHER),true);
        mismatch("metadata missing BV",f->f.view.getJSONObject("data").remove("bvid"),true);
        mismatch("metadata invalid aid",f->f.view.getJSONObject("data").put("aid",0),true);
        mismatch("metadata missing title",f->f.view.getJSONObject("data").remove("title"),true);
        mismatch("no parts",f->f.view.getJSONObject("data").put("pages",new JSONArray()),true);
        mismatch("part has no cid",f->f.view.getJSONObject("data").getJSONArray("pages").getJSONObject(0).remove("cid"),true);
        mismatch("duplicate part cid",f->f.view.getJSONObject("data").getJSONArray("pages").getJSONObject(1).put("cid",101),true);
        mismatch("wrong page number",f->f.view.getJSONObject("data").getJSONArray("pages").getJSONObject(0).put("page",2),true);
        for(String key:new String[]{"bvid","aid","cid","page_no"})mismatch("player wrong "+key,f->f.player.getJSONObject("data").put(key,key.equals("bvid")?OTHER:999),true);
        for(String key:new String[]{"bvid","aid","cid"})mismatch("player missing "+key,f->f.player.getJSONObject("data").remove(key),true);
        mismatch("player title differs",f->f.player.getJSONObject("data").put("title","其他视频标题"),true);
        mismatch("track has no identity",f->f.track().remove("id_str"),true);
        mismatch("foreign subtitle host",f->f.track().put("subtitle_url","https://unrelated.example/subtitle.json"),true);
        mismatch("subtitle URL has wrong cid",f->f.track().put("subtitle_url",f.url+"?cid=999"),true);
        mismatch("subtitle body has wrong cid",f->f.body.put("cid",999),false);
        mismatch("subtitle exceeds target duration",f->f.body.getJSONArray("body").getJSONObject(0).put("to",200),false);
        mismatch("invalid subtitle interval",f->f.body.getJSONArray("body").getJSONObject(0).put("from",5),false);
        mismatch("title changed during download",f->f.mutateTitle=true,false);
        mismatch("part changed during download",f->f.mutateCid=true,false);
        Fixture wrongTitle=new Fixture();boolean rejected=false;try{wrongTitle.fetch("【其他视频标题】 "+LINK);}catch(SourceIdentity.Mismatch e){rejected=true;}
        check(rejected&&wrongTitle.downloads==0,"wrong share title blocks fetching before model or download");
        badLink(LINK+" "+"https://b23.tv/other",u->LINK);
        badLink(BV+" "+OTHER,u->LINK);
        badLink("https://b23.tv/test?unused=1 "+OTHER,u->LINK);
        badLink(OTHER+" https://b23.tv/test",u->LINK);
        badLink(LINK+"?p=1&p=2",u->LINK);
        badLink(LINK+"?bvid="+OTHER,u->LINK);
        badLink("https://b23.tv/test?p=2",u->LINK+"?p=3");
        check(VideoLink.parse("https://b23.tv/test?p=2",u->LINK).page==2,"short link retains original part when redirect omits it");
        check(new Fixture().fetch(LINK+"?aid=170001&cid=101").meta.getLong("cid")==101,"matching URL identifiers accepted");
        boolean wrongHint=false;try{new Fixture().fetch(LINK+"?cid=202");}catch(SourceIdentity.Mismatch e){wrongHint=true;}check(wrongHint,"cid query cannot silently select a different part");
        badLink("https://www.bilibili.com/read/"+BV,u->LINK);
        JSONObject imported=VideoSource.read(LINK,"毫不相关的导入文稿",new Http.Control(),(p,m)->{});
        check(imported.optString("title").contains("未核验")&&!imported.has("bvid")&&!imported.has("url"),"import never adopts target title or claims video identity");
        check(SourceIdentity.description(imported).contains("未验证"),"import source is visibly unverified");
        check(SourceIdentity.matches(imported,LINK,"毫不相关的导入文稿"),"result matches its original selection");
        check(!SourceIdentity.matches(imported,"https://b23.tv/new","毫不相关的导入文稿"),"late old result cannot match a new link");
        check(!SourceIdentity.matches(imported,LINK,"另一份文稿"),"different import cannot reuse result");
        JSONObject legacy=new JSONObject(imported.toString());legacy.remove("input_hash");check(!SourceIdentity.matches(legacy,LINK,"毫不相关的导入文稿"),"unbound legacy subtitle cannot be restored as current video");
        JSONObject cached=new JSONObject(first.meta.toString()).put("transcript_hash","source");JSONObject fresh=new JSONObject(cached.toString());
        check(SourceIdentity.reusable(cached,fresh),"verified identical source may reuse summary");
        cached.remove("source_check_version");check(!SourceIdentity.reusable(cached,fresh),"old unverified summary cannot bypass source checks");
        cached=new JSONObject(fresh.toString()).put("cid",999);check(!SourceIdentity.reusable(cached,fresh),"same transcript with wrong cid cannot reuse summary");
        check(SourceIdentity.description(new JSONObject()).contains("旧版"),"legacy notes are labeled honestly");
        if(args.length==2){
            JSONObject recorded=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8));
            JSONObject subtitles=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),java.nio.charset.StandardCharsets.UTF_8));
            Fixture replay=new Fixture();replay.view.put("data",recorded);replay.body=subtitles;
            replay.player.getJSONObject("data").put("bvid",recorded.getString("bvid")).put("aid",recorded.getLong("aid")).put("cid",recorded.getJSONArray("pages").getJSONObject(0).getLong("cid"));
            BiliClient.Video video=replay.fetch("https://www.bilibili.com/video/"+recorded.getString("bvid"));JSONArray rows=subtitles.getJSONArray("body");
            check(video.meta.getString("title").equals(recorded.getString("title")),"recorded video title preserved");
            check(video.segments.size()==rows.length(),"recorded full transcript retains every cue");
            for(int i=0;i<rows.length();i++)if(!video.segments.get(i).text.equals(rows.getJSONObject(i).getString("content").trim()))throw new AssertionError("Source text changed at cue "+i);
            check(true,"all recorded subtitle text copied verbatim without model generation");
            check(video.segments.get(video.segments.size()-1).end==rows.getJSONObject(rows.length()-1).getDouble("to"),"recorded tail time preserved");
        }
        System.out.println("PASS "+checks+" source identity checks");
    }
}
