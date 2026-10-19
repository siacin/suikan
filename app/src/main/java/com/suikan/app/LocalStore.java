package com.suikan.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocalStore {
    private static final Object LOCK = new Object();
    private final File root;
    LocalStore(Context c) { root = new File(c.getFilesDir(), "notes"); root.mkdirs(); }
    static String id(JSONObject video) { String depth=SummaryDepth.ofNote(video);return Json.hash(video.optString("bvid") + ":" + video.optInt("page", 1) + ":" + video.optString("transcript_hash")+(SummaryDepth.DETAILED.equals(depth)?"":":"+depth)).substring(0, 24); }
    JSONObject read(String id) throws Exception {
        if (!id.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("笔记编号无效");
        synchronized (LOCK) { return new JSONObject(new String(new AtomicFile(new File(root, id + ".json")).readFully(), StandardCharsets.UTF_8)); }
    }
    void save(JSONObject note) throws Exception {
        synchronized (LOCK) {
            String id = id(note); note.put("id", id).put("saved_at", System.currentTimeMillis());
            AtomicFile file = new AtomicFile(new File(root, id + ".json")); FileOutputStream out = null;
            try { out = file.startWrite(); out.write(note.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
            catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
        }
    }
    List<JSONObject> list() {
        synchronized (LOCK) {
            List<JSONObject> notes = new ArrayList<>(); File[] files = root.listFiles();
            if (files != null) for (File f : files) if (f.getName().matches("[a-f0-9]{24}\\.json")) {
                try { notes.add(read(f.getName().substring(0, 24))); } catch (Exception ignored) { }
            }
            Collections.sort(notes, (a, b) -> Long.compare(b.optLong("saved_at"), a.optLong("saved_at"))); return notes;
        }
    }
    void delete(String id) { synchronized (LOCK) { if (id.matches("[a-f0-9]{24}")) new AtomicFile(new File(root, id + ".json")).delete(); } }
    static String markdown(JSONObject note) {
        StringBuilder out = new StringBuilder("# ").append(note.optString("title", "视频笔记")).append("\n\n");
        if (!note.optString("url").isEmpty()) out.append("原视频：").append(note.optString("url")).append("\n\n");
        if (note.optBoolean("demo")) out.append("这是一份演示笔记。\n\n");
        out.append("内容来源：").append(note.optString("source_label", "视频字幕")).append("\n\n");
        if(!note.optBoolean("demo"))out.append("来源核对：").append(SourceIdentity.description(note)).append("\n\n");
        if(!note.optBoolean("demo"))out.append("总结详细程度：").append(SummaryDepth.label(SummaryDepth.ofNote(note))).append("\n\n");
        JSONObject summary = note.optJSONObject("summary"); if (summary == null) return out.toString();
        out.append(summary.optString("overview")).append("\n\n");
        for (String key : new String[]{"key_points", "chapters", "steps"}) {
            JSONArray rows = Json.array(summary, key); if (rows.length() == 0) continue;
            out.append("## ").append(key.equals("key_points") ? "核心要点" : key.equals("chapters") ? "章节" : "方法步骤").append("\n\n");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                out.append("### ").append(row.optString("title")).append("\n\n");
                if (row.has("start") && row.optDouble("start", -1) >= 0) out.append(Transcript.stamp(row.optDouble("start"))).append("\n\n");
                out.append(row.optString("detail", row.optString("summary"))).append("\n\n");
                JSONArray items = Json.array(row, "items"); for (int j = 0; j < items.length(); j++) out.append("- ").append(items.optString(j)).append('\n');
            }
        }
        if(SummaryDepth.DETAILED.equals(SummaryDepth.ofNote(note))){out.append("## 字幕依据\n\n"); JSONArray points = Json.array(note, "points");
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i); if (point == null) continue;
            out.append("### ").append(point.optString("title")).append("\n\n"); JSONArray evidence = Json.array(point, "evidence");
            for (int j = 0; j < evidence.length(); j++) { JSONObject e = evidence.optJSONObject(j); if (e != null) out.append("> ").append(Transcript.stamp(e.optDouble("start", -1))).append(" ").append(e.optString("text").replace("\n", "\n> ")).append("\n\n"); }
        }
        }else out.append("各要点的原字幕依据可在随看 APP 内查看。\n\n");
        JSONArray caveats = Json.array(summary, "caveats"); if (caveats.length() > 0) { out.append("## 材料局限\n\n"); for (int i = 0; i < caveats.length(); i++) out.append("- ").append(caveats.optString(i)).append('\n'); }
        return out.toString();
    }
}
