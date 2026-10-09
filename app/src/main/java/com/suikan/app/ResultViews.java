package com.suikan.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.json.JSONObject;

final class ResultViews {
    static void render(Context context, LinearLayout root, JSONObject note, boolean compact, boolean overlay) {
        JSONObject summary = note.optJSONObject("summary"); if (summary == null) return;
        LinearLayout intro = Ui.card(context);
        boolean detailed=SummaryDepth.DETAILED.equals(SummaryDepth.ofNote(note));
        intro.addView(Ui.text(context,note.optBoolean("demo") ? "示例笔记" : SummaryDepth.label(SummaryDepth.ofNote(note))+"视频笔记",12,Ui.ACCENT,true)); Ui.gap(intro,8);
        intro.addView(Ui.text(context,note.optString("title"),compact ? 19 : 23,Ui.INK,true)); Ui.gap(intro,8);
        intro.addView(Ui.text(context,note.optString("owner") + (note.optString("owner").isEmpty() ? "" : " · ") + note.optString("source_label"),12,Ui.MUTED,false));
        Ui.gap(intro,14); intro.addView(Ui.text(context,summary.optString("overview"),15)); root.addView(intro);
        for (String section : new String[]{"key_points","chapters","steps"}) {
            JSONArray rows = Json.array(summary,section); if (rows.length()==0) continue;
            root.addView(Ui.text(context,section.equals("key_points") ? (detailed ? "知识与观点" : "核心要点") : section.equals("chapters") ? (detailed ? "章节详解" : "章节导航") : "方法与步骤",17,Ui.INK,true)); Ui.gap(root,10);
            for (int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i); if(row==null)continue;
                LinearLayout card=Ui.card(context);
                String title=row.optString("title");
                if(section.equals("chapters")) title=Transcript.stamp(row.optDouble("start",-1))+"  ·  "+title;
                card.addView(Ui.text(context,title,15,Ui.INK,true)); Ui.gap(card,7);
                String body=row.optString("detail",row.optString("summary"));
                JSONArray items=Json.array(row,"items"); for(int j=0;j<items.length();j++)body+=(body.isEmpty()?"":"\n")+(j+1)+". "+items.optString(j);
                card.addView(Ui.text(context,body,14)); Ui.gap(card,12);
                card.addView(Ui.button(context,"查看字幕依据",false,v->evidence(context,note,Json.array(row,"point_ids"),overlay)));
                root.addView(card);
            }
        }
        JSONArray caveats=Json.array(summary,"caveats"); if(caveats.length()>0) {
            LinearLayout card=Ui.card(context); card.addView(Ui.text(context,"材料局限",14,Ui.MUTED,true));
            for(int i=0;i<caveats.length();i++){Ui.gap(card,7);card.addView(Ui.text(context,"• "+caveats.optString(i),13,Ui.MUTED,false));}root.addView(card);
        }
    }
    static void evidence(Context c,JSONObject note,JSONArray refs,boolean overlay) {
        LinearLayout body=Ui.column(c);Ui.padding(body,20);JSONArray points=Json.array(note,"points"); java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<points.length();i++) {
            JSONObject p=points.optJSONObject(i);if(p==null)continue;boolean selected=false;
            for(int j=0;j<refs.length();j++)if(refs.optString(j).equals(p.optString("id")))selected=true;
            if(!selected)continue;body.addView(Ui.text(c,p.optString("title"),16,Ui.INK,true));Ui.gap(body,8);
            JSONArray source=Json.array(p,"evidence");
            for(int j=0;j<source.length();j++) {
                JSONObject e=source.optJSONObject(j);if(e==null||!seen.add(e.optString("segment_id")))continue;
                body.addView(Ui.text(c,Transcript.stamp(e.optDouble("start",-1))+"  ·  "+e.optString("text"),14));Ui.gap(body,10);
                if(e.optDouble("start",-1)>=0&&!note.optString("url").isEmpty()){
                    body.addView(Ui.button(c,"回看原视频片段",false,v->play(c,note,e.optDouble("start",-1))));Ui.gap(body,12);
                }
            }
        }
        ScrollView scroll=new ScrollView(c);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle("原文依据").setView(scroll).setPositiveButton("关闭",null).create();
        if(overlay&&dialog.getWindow()!=null)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
    }
    static void play(Context c,JSONObject note,double time) {
        String url=note.optString("url");if(url.isEmpty())return;
        Uri uri=Uri.parse(url).buildUpon().appendQueryParameter("t",String.valueOf(Math.max(0,(int)time))).build();
        try { c.startActivity(new Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch(Exception e){android.widget.Toast.makeText(c,"没有可打开该链接的应用",android.widget.Toast.LENGTH_SHORT).show();}
    }
}
