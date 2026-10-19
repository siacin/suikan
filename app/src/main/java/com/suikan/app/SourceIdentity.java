package com.suikan.app;

import org.json.JSONObject;
import java.text.Normalizer;

/** Deterministic source checks only. No model or semantic guessing is involved. */
final class SourceIdentity {
    static final int VERSION = 1;
    static final class Mismatch extends IllegalArgumentException {
        Mismatch(String detail) { super("视频来源校验未通过：" + detail + "。已停止读取，请从 B 站重新分享该视频后重试。"); }
    }
    static String inputKey(String input) { return Json.hash(input == null ? "" : input.trim()); }
    static String importedKey(String imported) { return imported == null || imported.trim().isEmpty() ? "" : Json.hash(imported); }
    static JSONObject bind(JSONObject source, String input, String imported) throws Exception {
        return source.put("input_hash", inputKey(input)).put("imported_hash", importedKey(imported));
    }
    static boolean matches(JSONObject source, String input, String imported) {
        return source != null && inputKey(input).equals(source.optString("input_hash"))
                && importedKey(imported).equals(source.optString("imported_hash"));
    }
    static String title(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", "");
    }
    static void equalTitle(String expected, String actual) {
        if (title(expected).isEmpty() || !title(expected).equals(title(actual))) throw new Mismatch("标题与视频信息不一致");
    }
    /** Allow only known share formatting, while keeping API-to-API title checks strict. */
    static void equalSharedTitle(String expected, String actual) {
        String caption=expected==null?"":expected.trim(), target=title(actual);
        if(!target.isEmpty()&&(shareCaptionMatches(caption,target)||shareCaptionMatches(withoutSiteSuffix(caption),target)))return;
        throw new Mismatch("分享标题与视频信息不一致（分享："+displayTitle(expected)+"；视频："+displayTitle(actual)+"）");
    }
    private static boolean shareCaptionMatches(String caption,String target){
        if(title(caption).equals(target))return true;
        if(caption.startsWith("【")&&caption.endsWith("】")){
            String inner=caption.substring(1,caption.length()-1).trim();
            return title(inner).equals(target)||title(withoutSiteSuffix(inner)).equals(target);
        }
        return false;
    }
    private static String withoutSiteSuffix(String caption){return caption.replaceFirst("(?:[-_｜|]哔哩哔哩(?:_bilibili)?)$", "").trim();}
    private static String displayTitle(String value){String text=value==null?"":value.replaceAll("[\\s\\p{Z}]+"," ").trim();return text.length()>160?text.substring(0,160)+"…":text;}
    static void sameSnapshot(JSONObject before, JSONObject after) {
        if (!before.optString("bvid").equals(after.optString("bvid")) || before.optLong("aid") != after.optLong("aid")
                || before.optLong("cid") != after.optLong("cid") || before.optInt("page") != after.optInt("page")
                || before.optInt("page_count") != after.optInt("page_count")) throw new Mismatch("下载前后的视频或分 P 信息发生变化");
        equalTitle(before.optString("title"), after.optString("title"));
    }
    static String fingerprint(JSONObject source) {
        return Json.hash(source.optString("bvid") + ":" + source.optLong("aid") + ":" + source.optLong("cid")
                + ":" + source.optInt("page", 1) + ":" + source.optString("title") + ":" + source.optString("transcript_hash")
                + ":" + source.optString("source_kind") + ":" + VERSION);
    }
    static boolean reusable(JSONObject cached, JSONObject fresh) {
        return cached != null && VERSION == cached.optInt("source_check_version")
                && fingerprint(cached).equals(fingerprint(fresh));
    }
    static String description(JSONObject source) {
        if (source.optInt("source_check_version") != VERSION) return "旧版记录，未进行来源校验；可重新提取核对。";
        if (!"bili".equals(source.optString("source_kind"))) return "用户导入文稿，未验证与视频的一致性。";
        return "视频标识与分 P 已核对 · " + source.optString("bvid") + " · P" + source.optInt("page", 1)
                + (source.optBoolean("share_title_checked") ? "\n分享标题已核对" : "\n分享文字未提供可核对的明确标题");
    }
}
