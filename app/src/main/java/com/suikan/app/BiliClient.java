package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

final class BiliClient {
    interface Progress { void update(int percent, String message); }
    interface Transport { JSONObject get(String url) throws Exception; String resolve(String url) throws Exception; }
    static final class Video {
        final JSONObject meta; final List<Transcript.Segment> segments;
        Video(JSONObject m, List<Transcript.Segment> s) { meta = m; segments = s; }
    }
    private final Http.Control control;
    private final Transport transport;
    BiliClient(Http.Control c) { this(c,new Transport(){
        public JSONObject get(String url)throws Exception{return Http.bili(url,c);}
        public String resolve(String url)throws Exception{return Http.resolveShort(url,c);}
    }); }
    BiliClient(Http.Control c,Transport t){control=c;transport=t;}
    JSONObject metadata(VideoLink link) throws Exception {
        control.check();JSONObject result = transport.get("https://api.bilibili.com/x/web-interface/view?bvid=" + URLEncoder.encode(link.bvid, "UTF-8"));
        if (result.optInt("code", -1) != 0 || result.optJSONObject("data") == null) throw new IllegalArgumentException("B 站未返回视频信息，视频可能不可见或需要登录");
        JSONObject info = result.getJSONObject("data"); JSONArray pages = Json.array(info, "pages");
        if(!link.bvid.equals(info.optString("bvid"))||info.optLong("aid")<=0)throw new SourceIdentity.Mismatch("视频信息接口返回了不同的视频标识");
        if(info.optString("title").trim().isEmpty())throw new SourceIdentity.Mismatch("视频信息缺少标题");
        if(pages.length()==0)throw new SourceIdentity.Mismatch("视频信息缺少可核对的分 P 列表");
        if (pages.length() > 0 && link.page > pages.length()) throw new IllegalArgumentException("该视频共 " + pages.length() + " P，请检查链接中的 p 编号");
        JSONObject part = pages.getJSONObject(link.page - 1);
        if(part.optInt("page")!=link.page||part.optLong("cid")<=0)throw new SourceIdentity.Mismatch("分 P 编号或 CID 无效");
        if((link.expectedAid>0&&link.expectedAid!=info.optLong("aid"))||(link.expectedCid>0&&link.expectedCid!=part.optLong("cid")))throw new SourceIdentity.Mismatch("链接中的 AV/CID 与目标分 P 不一致");
        java.util.HashSet<Long> cids=new java.util.HashSet<>();
        for(int i=0;i<pages.length();i++){JSONObject p=pages.optJSONObject(i);if(p==null||p.optInt("page")!=i+1||p.optLong("cid")<=0||!cids.add(p.optLong("cid")))throw new SourceIdentity.Mismatch("分 P 列表不完整或含重复 CID");}
        if(!link.sharedTitle.isEmpty())SourceIdentity.equalSharedTitle(link.sharedTitle,info.optString("title"));
        JSONObject owner = info.optJSONObject("owner");
        String title = info.optString("title", link.bvid); if (pages.length() > 1) title += " · P" + link.page + " " + part.optString("part");
        return new JSONObject().put("bvid", link.bvid).put("url", link.url).put("page", link.page).put("page_count", pages.length())
                .put("title", title).put("owner", owner == null ? "" : owner.optString("name"))
                .put("aid", info.optLong("aid")).put("cid", part.getLong("cid"))
                .put("video_title",info.getString("title")).put("shared_title",link.sharedTitle).put("share_title_checked",!link.sharedTitle.isEmpty())
                .put("duration", part.optDouble("duration", info.optDouble("duration", 0)));
    }
    Video fetch(String input, Progress progress) throws Exception {
        progress.update(3, "正在解析并核对视频链接"); VideoLink link = VideoLink.parse(input, url -> transport.resolve(url));
        progress.update(8, "正在读取视频信息"); JSONObject meta = metadata(link);
        progress.update(14, "正在查询完整字幕");
        String query = "?bvid=" + link.bvid + "&aid=" + meta.optLong("aid") + "&cid=" + meta.optLong("cid");
        JSONArray tracks = new JSONArray(); Exception firstFailure = null;
        try { tracks = tracks(transport.get("https://api.bilibili.com/x/player/wbi/v2" + query),meta); }
        catch(SourceIdentity.Mismatch e){throw e;}
        catch (java.util.concurrent.CancellationException e) { throw e; } catch (Exception e) { firstFailure = e; }
        if (tracks.length() == 0) {
            try { tracks = tracks(transport.get("https://api.bilibili.com/x/player/v2" + query),meta); }
            catch(SourceIdentity.Mismatch e){throw e;}
            catch (java.util.concurrent.CancellationException e) { throw e; } catch (Exception e) { if (firstFailure != null) throw firstFailure; }
        }
        JSONObject preferred = null; int best = -1;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i); if (t == null || t.optString("subtitle_url").isEmpty()) continue;
            String language = t.optString("lan").toLowerCase(java.util.Locale.ROOT);
            int rank = language.startsWith("zh") ? 4 : language.startsWith("ai-zh") ? 3 : 1;
            if (rank > best) { preferred = t; best = rank; }
        }
        if (preferred == null) throw new IllegalArgumentException("未找到可下载的完整字幕。请在设置中登录 B 站后重试；仍无字幕时，可导入完整 TXT / SRT 文稿。");
        String url = preferred.getString("subtitle_url"); if (url.startsWith("//")) url = "https:" + url;
        validateSubtitleUrl(url,meta);
        String trackId=preferred.optString("id_str",preferred.optString("id"));
        if(!trackId.matches("[0-9]+")||trackId.matches("0+"))throw new SourceIdentity.Mismatch("字幕轨道缺少可核对的编号");
        progress.update(20, "视频标识已核对，正在下载字幕"); JSONObject subtitle=transport.get(url);JSONArray body = Json.array(subtitle, "body");
        checkOptionalIds(subtitle,meta);
        List<Transcript.Segment> segments = new ArrayList<>(); int chars = 0;
        for (int i = 0; i < body.length(); i++) {
            JSONObject row = body.optJSONObject(i); if (row == null || row.optString("content").trim().isEmpty()) continue;
            String text = row.getString("content").trim(); chars += text.length();
            double start=row.optDouble("from",-1),end=row.optDouble("to",-1),duration=meta.optDouble("duration",0);
            if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start||duration<=0||end>duration+Math.max(2,Math.min(10,duration*.01)))throw new SourceIdentity.Mismatch("字幕时间轴超出目标分 P 或无效");
            if (chars > Transcript.MAX_CHARS || segments.size() >= Transcript.MAX_SEGMENTS) throw new IllegalArgumentException("视频字幕过长，请按分 P 或章节拆分");
            segments.add(new Transcript.Segment("s" + (segments.size() + 1), row.optDouble("from", -1), row.optDouble("to", -1), text));
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("字幕文件中没有文字，请稍后重试或导入文稿");
        progress.update(22,"正在复核视频标题与字幕来源");control.check();SourceIdentity.sameSnapshot(meta,metadata(link));
        meta.put("source_kind","bili").put("source_check_version",SourceIdentity.VERSION).put("subtitle_id",trackId).put("subtitle_url_hash",Json.hash(url));
        meta.put("source_label", "B站字幕 · " + preferred.optString("lan_doc", preferred.optString("lan")));
        return new Video(meta, segments);
    }
    private JSONArray tracks(JSONObject result,JSONObject meta) {
        if (result.optInt("code", -1) != 0) return new JSONArray();
        JSONObject data = result.optJSONObject("data");
        if(data==null||!meta.optString("bvid").equals(data.optString("bvid"))||meta.optLong("aid")!=data.optLong("aid")||meta.optLong("cid")!=data.optLong("cid"))throw new SourceIdentity.Mismatch("字幕接口返回的视频或 CID 与分享链接不一致");
        if(data.has("page_no")&&data.optInt("page_no")!=meta.optInt("page"))throw new SourceIdentity.Mismatch("字幕接口返回了不同的分 P 编号");
        if(data.has("title"))SourceIdentity.equalTitle(meta.optString("video_title"),data.optString("title"));
        JSONObject sub = data.optJSONObject("subtitle");
        return sub == null ? new JSONArray() : Json.array(sub, "subtitles");
    }
    private static void checkOptionalIds(JSONObject value,JSONObject meta){
        if((value.has("bvid")&&!meta.optString("bvid").equals(value.optString("bvid")))||(value.has("aid")&&meta.optLong("aid")!=value.optLong("aid"))||(value.has("cid")&&meta.optLong("cid")!=value.optLong("cid")))throw new SourceIdentity.Mismatch("字幕文件的视频标识不匹配");
    }
    private static void validateSubtitleUrl(String url,JSONObject meta)throws Exception{
        java.net.URI uri=new java.net.URI(url);String host=uri.getHost();
        boolean cdn="aisubtitle.hdslb.com".equals(host)&&uri.getPath()!=null&&uri.getPath().startsWith("/bfs/");
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||(!cdn&&!VideoLink.isBiliHost(host)))throw new SourceIdentity.Mismatch("字幕地址不属于允许的 B 站来源");
        if(uri.getQuery()!=null)for(String pair:uri.getQuery().split("&")){
            int split=pair.indexOf('=');if(split<0)continue;String key=java.net.URLDecoder.decode(pair.substring(0,split),"UTF-8"),value=java.net.URLDecoder.decode(pair.substring(split+1),"UTF-8");
            if((key.equals("bvid")&&!value.equals(meta.optString("bvid")))||(key.equals("aid")&&!value.equals(Long.toString(meta.optLong("aid"))))||(key.equals("cid")&&!value.equals(Long.toString(meta.optLong("cid")))))throw new SourceIdentity.Mismatch("字幕地址指向其他视频");
        }
    }
}
