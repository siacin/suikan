package com.suikan.app;

import org.json.JSONArray;
import org.json.JSONObject;

final class Json {
    static JSONArray array(JSONObject o, String key) { JSONArray a = o.optJSONArray(key); return a == null ? new JSONArray() : a; }
    static String text(JSONObject o, String key) { return o.optString(key, "").trim(); }
    static JSONObject parseObject(String value) throws Exception {
        value = value.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n'); if (newline >= 0) value = value.substring(newline + 1);
            if (value.endsWith("```")) value = value.substring(0, value.length() - 3).trim();
        }
        return new JSONObject(value);
    }
    static JSONObject parseModelObject(String value)throws Exception{
        try{return parseObject(value);}
        catch(org.json.JSONException e){throw new IllegalArgumentException("模型传输已完成，但总结未通过 JSON 格式校验，未保存笔记。请重试或更换支持 JSON 输出的模型 / 接口。",e);}
    }
    static String hash(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static JSONObject segment(Transcript.Segment s) throws Exception {
        return new JSONObject().put("segment_id", s.id).put("text", s.text)
                .put("start", s.start >= 0 ? s.start : JSONObject.NULL).put("end", s.end >= 0 ? s.end : JSONObject.NULL);
    }
}
