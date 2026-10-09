package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

final class BiliClient {
    interface Progress { void update(int percent, String message); }
    static final class Video {
        final JSONObject meta; final List<Transcript.Segment> segments;
        Video(JSONObject m, List<Transcript.Segment> s) { meta = m; segments = s; }
    }
    private final Http.Control control;
    BiliClient(Http.Control c) { control = c; }
    JSONObject metadata(VideoLink link) throws Exception {
        JSONObject result = Http.bili("https://api.bilibili.com/x/web-interface/view?bvid=" + URLEncoder.encode(link.bvid, "UTF-8"), control);
        if (result.optInt("code", -1) != 0 || result.optJSONObject("data") == null) throw new IllegalArgumentException("B 站未返回视频信息，视频可能不可见或需要登录");
        JSONObject info = result.getJSONObject("data"); JSONArray pages = Json.array(info, "pages");
        if (pages.length() > 0 && link.page > pages.length()) throw new IllegalArgumentException("该视频共 " + pages.length() + " P，请检查链接中的 p 编号");
        JSONObject part = pages.length() > 0 ? pages.getJSONObject(link.page - 1) : new JSONObject();
        JSONObject owner = info.optJSONObject("owner");
        String title = info.optString("title", link.bvid); if (pages.length() > 1) title += " · P" + link.page + " " + part.optString("part");
        return new JSONObject().put("bvid", link.bvid).put("url", link.url).put("page", link.page).put("page_count", pages.length())
                .put("title", title).put("owner", owner == null ? "" : owner.optString("name"))
                .put("aid", info.optLong("aid")).put("cid", part.optLong("cid", info.optLong("cid")))
                .put("duration", part.optDouble("duration", info.optDouble("duration", 0)));
    }
    Video fetch(String input, Progress progress) throws Exception {
        progress.update(3, "正在解析视频链接"); VideoLink link = VideoLink.parse(input, url -> Http.resolveShort(url, control));
        progress.update(8, "正在读取视频信息"); JSONObject meta = metadata(link);
        progress.update(14, "正在查询完整字幕");
        String query = "?bvid=" + link.bvid + "&aid=" + meta.optLong("aid") + "&cid=" + meta.optLong("cid");
        JSONArray tracks = new JSONArray(); Exception firstFailure = null;
        try { tracks = tracks(Http.bili("https://api.bilibili.com/x/player/wbi/v2" + query, control)); }
        catch (java.util.concurrent.CancellationException e) { throw e; } catch (Exception e) { firstFailure = e; }
        if (tracks.length() == 0) {
            try { tracks = tracks(Http.bili("https://api.bilibili.com/x/player/v2" + query, control)); }
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
        progress.update(20, "正在下载字幕"); JSONArray body = Json.array(Http.bili(url, control), "body");
        List<Transcript.Segment> segments = new ArrayList<>(); int chars = 0;
        for (int i = 0; i < body.length(); i++) {
            JSONObject row = body.optJSONObject(i); if (row == null || row.optString("content").trim().isEmpty()) continue;
            String text = row.getString("content").trim(); chars += text.length();
            if (chars > Transcript.MAX_CHARS || segments.size() >= Transcript.MAX_SEGMENTS) throw new IllegalArgumentException("视频字幕过长，请按分 P 或章节拆分");
            segments.add(new Transcript.Segment("s" + (segments.size() + 1), row.optDouble("from", -1), row.optDouble("to", -1), text));
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("字幕文件中没有文字，请稍后重试或导入文稿");
        meta.put("source_label", "B站字幕 · " + preferred.optString("lan_doc", preferred.optString("lan")));
        return new Video(meta, segments);
    }
    private JSONArray tracks(JSONObject result) {
        if (result.optInt("code", -1) != 0) return new JSONArray();
        JSONObject data = result.optJSONObject("data"), sub = data == null ? null : data.optJSONObject("subtitle");
        return sub == null ? new JSONArray() : Json.array(sub, "subtitles");
    }
}
