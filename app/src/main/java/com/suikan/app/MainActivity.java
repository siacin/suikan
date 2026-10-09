package com.suikan.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends Activity {
    private LinearLayout root,body; private EditText linkField; private SecureSettings settings; private LocalStore store;
    private JSONObject currentNote,currentSubtitle; private String input="",imported="",tab="home"; private boolean receiverRegistered=false;
    private TextView statusTitle,statusDraft;private ProgressBar statusProgress;
    private boolean subtitleExpanded=true;private int subtitleScrollY=0;
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(intent.getBooleanExtra("status_only",false)){
            if(tab.equals("home")&&statusTitle!=null){statusTitle.setText(SummaryService.message);if(statusProgress!=null){statusProgress.setIndeterminate(SummaryService.modelWaiting);statusProgress.setProgress(SummaryService.percent);}}
            if(tab.equals("home")&&statusDraft!=null){statusDraft.setText(SummaryService.draft.isEmpty()?"":"生成中的概述 · 草稿，完成后校验保存\n\n"+SummaryService.draft);statusDraft.setVisibility(SummaryService.draft.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);}
            return;
        }
        if(!intent.getBooleanExtra("overlay_state",false)&&!SummaryService.running&&!SummaryService.noteId.isEmpty())try{currentNote=store.read(SummaryService.noteId);imported="";}catch(Exception ignored){}
        if(!SummaryService.running&&SummaryService.subtitleReady){JSONObject latest=new SubtitleStore(MainActivity.this).read();if(latest!=null&&(currentSubtitle==null||!latest.optString("transcript_hash").equals(currentSubtitle.optString("transcript_hash")))){subtitleExpanded=true;subtitleScrollY=0;}currentSubtitle=latest;}
        if(tab.equals("home")){rememberInput();render();}
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new SecureSettings(this);store=new LocalStore(this);input=settings.lastInput();currentSubtitle=new SubtitleStore(this).read();
        List<JSONObject> notes=store.list();if(!notes.isEmpty())currentNote=notes.get(0);
        if(state!=null){input=state.getString("input",input);tab=state.getString("tab","home");subtitleExpanded=state.getBoolean("subtitle_expanded",true);subtitleScrollY=state.getInt("subtitle_scroll_y",0);String id=state.getString("note_id","");if(!id.isEmpty())try{currentNote=store.read(id);}catch(Exception ignored){}}
        if(state!=null&&state.getBoolean("has_imported"))try{imported=readPrivate("draft-transcript.txt");}catch(Exception ignored){}
        IntentFilter filter=new IntentFilter(SummaryService.UPDATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updates,filter);receiverRegistered=true;
        if(state==null)handle(getIntent());render();
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);render();}
    private void handle(Intent intent){
        if(Intent.ACTION_SEND.equals(intent.getAction())&&"text/plain".equals(intent.getType())){
            String value=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(value!=null&&!value.trim().isEmpty()){input=value.trim();settings.lastInput(input);imported="";currentNote=null;currentSubtitle=null;subtitleExpanded=true;subtitleScrollY=0;tab="home";}
        }
        String id=intent.getStringExtra("note_id");if(id!=null)try{currentNote=store.read(id);tab="home";}catch(Exception ignored){}
    }
    @Override public void onResume(){super.onResume();if(root!=null&&tab.equals("settings"))render();}
    @Override public void onSaveInstanceState(Bundle state){rememberInput();state.putString("input",input);state.putString("tab",tab);state.putBoolean("subtitle_expanded",subtitleExpanded);state.putInt("subtitle_scroll_y",subtitleScrollY);try{if(!imported.isEmpty()){writePrivate("draft-transcript.txt",imported);state.putBoolean("has_imported",true);}}catch(Exception e){toast("文稿暂存失败："+e.getMessage());}if(currentNote!=null&&!currentNote.optBoolean("demo"))state.putString("note_id",currentNote.optString("id"));super.onSaveInstanceState(state);}
    @Override public void onDestroy(){if(receiverRegistered)unregisterReceiver(updates);super.onDestroy();}
    private void rememberInput(){if(linkField!=null&&tab.equals("home"))input=linkField.getText().toString();}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void render(){
        linkField=null;statusTitle=null;statusDraft=null;statusProgress=null;root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.padding(root,20);
        LinearLayout header=Ui.row(this);TextView logo=Ui.text(this,"▶",20,Color.WHITE,true);logo.setGravity(android.view.Gravity.CENTER);logo.setBackground(Ui.background(this,Ui.ACCENT,14,false));header.addView(logo,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));
        LinearLayout titles=Ui.column(this);titles.setPadding(Ui.dp(this,12),0,0,0);titles.addView(Ui.text(this,"随看",26,Ui.INK,true));titles.addView(Ui.text(this,"把看过的视频，变成可回看的笔记",11,Ui.MUTED,false));header.addView(titles);root.addView(header);Ui.gap(root,20);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=Ui.column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(tab.equals("history"))history();else if(tab.equals("settings"))settingsPage();else home();
        Ui.gap(root,10);LinearLayout nav=Ui.row(this);
        for(String name:new String[]{"home","history","settings"}){String label=name.equals("home")?"视频总结":name.equals("history")?"历史笔记":"设置";Ui.weighted(nav,Ui.button(this,label,tab.equals(name),v->{rememberInput();tab=name;render();}));}root.addView(nav);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(Ui.dp(this,20),Ui.dp(this,12)+insets.getSystemWindowInsetTop(),Ui.dp(this,20),Ui.dp(this,8)+insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();
    }
    private void home(){
        LinearLayout card=Ui.card(this);card.addView(Ui.text(this,"正在看的视频",18,Ui.INK,true));Ui.gap(card,7);card.addView(Ui.text(this,"从 B 站分享给「随看」，或粘贴视频链接。",13,Ui.MUTED,false));Ui.gap(card,14);
        linkField=Ui.input(this,"B 站视频链接 / b23.tv / BV 号",input);linkField.setMinLines(2);linkField.setMaxLines(4);linkField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);linkField.setEnabled(!SummaryService.running);card.addView(linkField);Ui.gap(card,10);
        LinearLayout tools=Ui.row(this);Ui.weighted(tools,Ui.button(this,"粘贴链接",false,v->{
            if(SummaryService.running){toast("请等待当前总结完成");return;}ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm.hasPrimaryClip()&&cm.getPrimaryClip()!=null&&cm.getPrimaryClip().getItemCount()>0){input=cm.getPrimaryClip().getItemAt(0).coerceToText(this).toString();linkField.setText(input);imported="";settings.lastInput(input);}else toast("剪贴板没有文字");
        }));Ui.weighted(tools,Ui.button(this,"导入字幕",false,v->importMenu()));card.addView(tools);Ui.gap(card,12);
        if(!imported.isEmpty()){card.addView(Ui.text(this,"已载入文稿 · "+imported.length()+" 字符",12,Ui.ACCENT,true));Ui.gap(card,6);card.addView(Ui.button(this,"清除导入文稿，改为读取视频字幕",false,v->{if(SummaryService.running)return;rememberInput();imported="";render();}));Ui.gap(card,10);}
        android.widget.Button extract=Ui.button(this,"只提取字幕",false,v->extractOnly());extract.setEnabled(!SummaryService.running);card.addView(extract);Ui.gap(card,14);
        card.addView(Ui.text(this,"总结详细程度",13,Ui.INK,true));android.widget.RadioGroup choices=new android.widget.RadioGroup(this);choices.setOrientation(LinearLayout.HORIZONTAL);
        String[] depths={SummaryDepth.CONCISE,SummaryDepth.STANDARD,SummaryDepth.DETAILED};for(int i=0;i<depths.length;i++){android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(100+i);option.setText(SummaryDepth.label(depths[i]));option.setTextColor(Ui.INK);option.setTextSize(14);option.setEnabled(!SummaryService.running);choices.addView(option,new LinearLayout.LayoutParams(0,-2,1));if(depths[i].equals(settings.summaryDepth()))choices.check(100+i);}
        choices.setOnCheckedChangeListener((g,id)->{if(id>=100&&id<=102)settings.summaryDepth(depths[id-100]);});card.addView(choices);Ui.gap(card,5);
        card.addView(Ui.text(this,"简洁 / 标准：优先一次直接总结，长文稿才分批\n详细：逐段整理、调用更多。选择会用于下一次总结。",11,Ui.MUTED,false));Ui.gap(card,12);
        android.widget.Button start=Ui.button(this,SummaryService.running?"任务进行中…":"总结整段视频",true,v->begin(false));start.setEnabled(!SummaryService.running);card.addView(start);Ui.gap(card,10);
        card.addView(Ui.text(this,"分 P 链接总结指定一 P；切换视频后请重新分享。",11,Ui.MUTED,false));body.addView(card);
        if(SummaryService.running||!SummaryService.error.isEmpty()||(!SummaryService.message.isEmpty()&&SummaryService.noteId.isEmpty())){
            LinearLayout status=Ui.card(this);statusTitle=Ui.text(this,SummaryService.message,15,Ui.INK,true);status.addView(statusTitle);Ui.gap(status,10);
            if(SummaryService.running){statusDraft=Ui.text(this,SummaryService.draft.isEmpty()?"":"生成中的概述 · 草稿，完成后校验保存\n\n"+SummaryService.draft,14);statusDraft.setVisibility(SummaryService.draft.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);status.addView(statusDraft);}
            if(SummaryService.running){statusProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);statusProgress.setIndeterminate(SummaryService.modelWaiting);statusProgress.setProgress(SummaryService.percent);status.addView(statusProgress);Ui.gap(status,10);status.addView(Ui.button(this,"停止本次任务",false,v->startService(new Intent(this,SummaryService.class).setAction(SummaryService.STOP))));}
            else if(!SummaryService.error.isEmpty()){status.addView(Ui.text(this,SummaryService.error,13,Ui.ACCENT,false));Ui.gap(status,10);status.addView(Ui.button(this,SummaryService.extracting?"前往设置查看 B 站登录":"前往设置测试模型连接",false,v->{rememberInput();tab="settings";render();}));}body.addView(status);
        }
        if(currentSubtitle!=null)subtitleCard();
        if(currentNote!=null){ResultViews.render(this,body,currentNote,false,false);
            LinearLayout actions=Ui.row(this);Ui.weighted(actions,Ui.button(this,"复制笔记",false,v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("视频笔记",LocalStore.markdown(currentNote)));toast("已复制笔记");}));Ui.weighted(actions,Ui.button(this,"导出 Markdown",false,v->export()));body.addView(actions);Ui.gap(body,14);
        }else{
            LinearLayout empty=Ui.card(this);empty.addView(Ui.text(this,"一份有依据的视频笔记",18,Ui.INK,true));Ui.gap(empty,9);
            empty.addView(Ui.text(this,"读主旨、看要点，点开字幕依据回看原文。笔记会保存在手机里。",14,Ui.MUTED,false));Ui.gap(empty,14);
            empty.addView(Ui.button(this,"查看示例笔记",false,v->demo()));body.addView(empty);
        }
        body.addView(Ui.button(this,OverlayService.visible?"关闭悬浮按钮":"开启悬浮按钮",false,v->toggleOverlay()));Ui.gap(body,12);
        if(settings.endpoint().isEmpty())body.addView(Ui.text(this,"只提取字幕无需模型 API；总结前请在「设置」配置模型。",12,Ui.MUTED,false));
    }
    private void extractOnly(){rememberInput();if(SummaryService.running){toast("请先等待或停止当前任务");return;}if(input.trim().isEmpty()&&imported.isEmpty()){toast("请先分享视频链接或导入字幕");return;}settings.lastInput(input);try{SummaryService.extract(this,input,imported);toast("正在提取字幕，无需调用模型");}catch(Exception e){toast("无法启动提取："+e.getMessage());}}
    private void subtitleCard(){
        LinearLayout card=Ui.card(this);LinearLayout heading=Ui.row(this);heading.addView(Ui.text(this,"已提取的完整字幕",17,Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1));card.addView(heading);Ui.gap(card,8);card.addView(Ui.text(this,currentSubtitle.optString("title"),15,Ui.INK,true));
        String full=SubtitleFiles.text(currentSubtitle,false);Ui.gap(card,6);card.addView(Ui.text(this,Json.array(currentSubtitle,"segments").length()+" 段 · "+full.length()+" 字符 · "+currentSubtitle.optString("source_label"),12,Ui.MUTED,false));Ui.gap(card,10);
        ScrollView transcript=new ScrollView(this){@Override public boolean dispatchTouchEvent(android.view.MotionEvent event){int action=event.getActionMasked();android.view.ViewParent parent=getParent();if(parent!=null&&action==android.view.MotionEvent.ACTION_DOWN)parent.requestDisallowInterceptTouchEvent(true);boolean handled=super.dispatchTouchEvent(event);if(parent!=null&&(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL))parent.requestDisallowInterceptTouchEvent(false);return handled;}};
        transcript.setContentDescription("完整字幕滚动区域");transcript.setVerticalScrollBarEnabled(true);transcript.setScrollbarFadingEnabled(false);transcript.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_INSET);transcript.setBackground(Ui.background(this,Ui.BG,12,true));Ui.padding(transcript,12);transcript.addView(Ui.text(this,full,13));
        card.addView(transcript,new LinearLayout.LayoutParams(-1,Ui.dp(this,240)));transcript.setVisibility(subtitleExpanded?android.view.View.VISIBLE:android.view.View.GONE);
        final int[] restoreScroll={subtitleScrollY};transcript.setOnScrollChangeListener((v,x,y,oldX,oldY)->subtitleScrollY=y);transcript.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->{if(v.getVisibility()==android.view.View.VISIBLE&&b>t&&restoreScroll[0]>=0){int position=restoreScroll[0];restoreScroll[0]=-1;transcript.scrollTo(0,position);}});
        android.widget.Button toggle=Ui.button(this,subtitleExpanded?"收起":"展开",false,v->{int position=subtitleScrollY;subtitleExpanded=!subtitleExpanded;if(subtitleExpanded)restoreScroll[0]=position;transcript.setVisibility(subtitleExpanded?android.view.View.VISIBLE:android.view.View.GONE);((android.widget.Button)v).setText(subtitleExpanded?"收起":"展开");v.setContentDescription(subtitleExpanded?"收起字幕全文":"展开字幕全文");});toggle.setContentDescription(subtitleExpanded?"收起字幕全文":"展开字幕全文");heading.addView(toggle);Ui.gap(card,12);
        LinearLayout actions=Ui.row(this);Ui.weighted(actions,Ui.button(this,"复制字幕",false,v->new AlertDialog.Builder(this).setTitle("复制完整字幕").setItems(new String[]{"纯文字","带时间戳"},(d,i)->copySubtitles(i==1)).show()));
        Ui.weighted(actions,Ui.button(this,"另存为文件",false,v->saveSubtitles()));card.addView(actions);Ui.gap(card,8);card.addView(Ui.text(this,"UTF-8 编码，保存位置由你选择。没有完整起止时间的文稿可保存 TXT。",11,Ui.MUTED,false));body.addView(card);
    }
    private void copySubtitles(boolean timed){if(currentSubtitle==null)return;try{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("完整字幕",SubtitleFiles.text(currentSubtitle,timed)));toast("已复制完整字幕");}catch(Exception e){toast("复制失败，可另存为文件："+e.getMessage());}}
    private void saveSubtitles(){if(currentSubtitle==null)return;JSONObject source=currentSubtitle;boolean srt=SubtitleFiles.canSrt(source);new AlertDialog.Builder(this).setTitle("保存完整字幕").setItems(srt?new String[]{"TXT · 纯文字","TXT · 带时间戳","SRT · 原始时间轴"}:new String[]{"TXT · 纯文字","TXT · 带已有时间戳"},(d,i)->{try{saveDocument(i==2?SubtitleFiles.srt(source):SubtitleFiles.text(source,i==1),i==2?"application/x-subrip":"text/plain",source.optString("title","视频字幕")+(i==1?"-时间戳":"")+(i==2?".srt":".txt"));}catch(Exception e){toast("无法保存："+e.getMessage());}}).show();}
    private void begin(boolean force){
        rememberInput();if(SummaryService.running){toast("已有视频正在总结");return;}
        if(input.trim().isEmpty()&&imported.isEmpty()){toast("请先分享视频链接或导入完整字幕");return;}
        if(settings.endpoint().isEmpty()||settings.model().isEmpty()){toast("请先配置模型 API");tab="settings";render();return;}
        settings.lastInput(input);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
        try{SummaryService.begin(this,input,imported,force);toast("已开始，可返回 B 站继续观看");}catch(Exception e){toast("无法启动总结："+e.getMessage());}
    }
    private void history(){
        body.addView(Ui.text(this,"历史笔记",24,Ui.INK,true));Ui.gap(body,8);body.addView(Ui.text(this,"保存在这台手机，可回看、导出或删除。",13,Ui.MUTED,false));Ui.gap(body,18);
        List<JSONObject> notes=store.list();if(notes.isEmpty()){body.addView(Ui.text(this,"还没有笔记。总结一段视频后，它会出现在这里。",15,Ui.MUTED,false));return;}
        java.text.SimpleDateFormat dates=new java.text.SimpleDateFormat("MM月dd日 HH:mm",java.util.Locale.CHINA);
        for(JSONObject note:notes){LinearLayout card=Ui.card(this);card.addView(Ui.text(this,note.optString("title"),17,Ui.INK,true));Ui.gap(card,8);card.addView(Ui.text(this,dates.format(new java.util.Date(note.optLong("saved_at")))+" · "+note.optString("source_label"),12,Ui.MUTED,false));
            card.addView(Ui.text(this,SummaryDepth.label(SummaryDepth.ofNote(note))+"总结",12,Ui.ACCENT,true));JSONObject summary=note.optJSONObject("summary");String preview=summary==null?"":summary.optString("overview");if(preview.length()>100)preview=preview.substring(0,100)+"…";Ui.gap(card,10);card.addView(Ui.text(this,preview,14));Ui.gap(card,12);
            LinearLayout actions=Ui.row(this);Ui.weighted(actions,Ui.button(this,"打开笔记",true,v->{currentNote=note;input=note.optString("url");settings.lastInput(input);imported="";tab="home";render();}));
            Ui.weighted(actions,Ui.button(this,"删除",false,v->new AlertDialog.Builder(this).setTitle("删除这份本机笔记？").setMessage(note.optString("title")).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{store.delete(note.optString("id"));if(currentNote!=null&&note.optString("id").equals(currentNote.optString("id")))currentNote=null;render();}).show()));card.addView(actions);body.addView(card);
        }
    }
    private void settingsPage(){
        body.addView(Ui.text(this,"连接你的模型",24,Ui.INK,true));Ui.gap(body,8);body.addView(Ui.text(this,"手机直接调用你配置的 API，无需电脑运行服务。",13,Ui.MUTED,false));Ui.gap(body,18);
        LinearLayout card=Ui.card(this);card.addView(Ui.text(this,"模型 API",18,Ui.INK,true));Ui.gap(card,14);
        card.addView(Ui.text(this,"接口地址",12,Ui.MUTED,true));Ui.gap(card,6);EditText endpoint=Ui.input(this,"https://你的服务商/v1",settings.endpoint());endpoint.setSingleLine(true);endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);card.addView(endpoint);Ui.gap(card,14);
        card.addView(Ui.text(this,"模型名称",12,Ui.MUTED,true));Ui.gap(card,6);EditText model=Ui.input(this,"服务商提供的模型 ID",settings.model());model.setSingleLine(true);card.addView(model);Ui.gap(card,14);
        card.addView(Ui.text(this,"API 密钥",12,Ui.MUTED,true));Ui.gap(card,6);String savedKey="";try{savedKey=settings.key();}catch(Exception e){toast("密钥无法解密，请重新填写");}
        EditText key=Ui.input(this,"你的 API Key",savedKey);key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);card.addView(key);Ui.gap(card,16);
        card.addView(Ui.text(this,"单次输出上限（tokens）",12,Ui.MUTED,true));Ui.gap(card,6);
        EditText outputTokens=Ui.input(this,"0：使用服务商默认",Integer.toString(settings.outputTokens()));outputTokens.setSingleLine(true);outputTokens.setInputType(InputType.TYPE_CLASS_NUMBER);card.addView(outputTokens);Ui.gap(card,8);
        card.addView(Ui.text(this,"填 0 时 APP 不指定单次上限，使用服务商默认。也可填服务商支持的额度；模型仍有硬上限。详细笔记逐段生成，截断时拆小处理，可能增加调用次数。",12,Ui.MUTED,false));Ui.gap(card,12);
        android.widget.CheckBox disableThinking=new android.widget.CheckBox(this);disableThinking.setText("DeepSeek 关闭深度思考");disableThinking.setTextColor(Ui.INK);disableThinking.setTextSize(14);disableThinking.setChecked(settings.disableDeepSeekThinking());card.addView(disableThinking);
        card.addView(Ui.text(this,"仅对 DeepSeek 模型生效。默认关闭以减少等待；若中转服务不支持此参数，可取消勾选。",12,Ui.MUTED,false));Ui.gap(card,16);
        android.widget.CheckBox lightGlm=new android.widget.CheckBox(this);lightGlm.setText("GLM 5.3 使用轻量思考");lightGlm.setTextColor(Ui.INK);lightGlm.setTextSize(14);lightGlm.setChecked(settings.lightGlmThinking());card.addView(lightGlm);
        card.addView(Ui.text(this,"GLM 5.3 默认可能深度思考很久；轻量思考减少等待，详细正文继续完整生成。若服务商不支持，可取消勾选。",12,Ui.MUTED,false));Ui.gap(card,16);
        card.addView(Ui.button(this,"保存模型设置",true,v->{try{int limit;try{limit=Integer.parseInt(outputTokens.getText().toString().trim());}catch(NumberFormatException e){throw new IllegalArgumentException("输出上限请填写 0 或不少于 1024 的整数");}settings.save(endpoint.getText().toString(),model.getText().toString(),key.getText().toString(),limit,disableThinking.isChecked(),lightGlm.isChecked());toast("模型设置已保存");}catch(Exception e){toast(e.getMessage());}}));Ui.gap(card,12);
        card.addView(Ui.button(this,"保存并测试模型连接",false,v->{
            if(SummaryService.running){toast("请先等待或停止当前任务");return;}
            try{int limit=Integer.parseInt(outputTokens.getText().toString().trim());settings.save(endpoint.getText().toString(),model.getText().toString(),key.getText().toString(),limit,disableThinking.isChecked(),lightGlm.isChecked());SummaryService.probe(this);tab="home";render();toast("已开始连接测试，可查看当前连接状态");}catch(Exception e){toast(Http.redact(e.getMessage(),key.getText().toString()));}
        }));Ui.gap(card,8);
        card.addView(Ui.text(this,"先用短请求确认手机能收到完整回答，再总结视频。连接测试也按服务商计费。",12,Ui.MUTED,false));Ui.gap(card,12);
        card.addView(Ui.text(this,"模型流式接收，显示连接阶段、等待时长和已接收字数。TCP 连接等待 60 秒，读取 / TLS 等待最多 15 分钟，单次调用最长 60 分钟；服务端可能提前断开。",12,Ui.MUTED,false));Ui.gap(card,8);
        card.addView(Ui.text(this,"支持 Chat Completions / Responses 兼容接口。密钥加密保存在手机；总结时字幕会发送给所选服务商，调用费用按服务商计费。",12,Ui.MUTED,false));body.addView(card);
        LinearLayout login=Ui.card(this);login.addView(Ui.text(this,"B 站网页登录",18,Ui.INK,true));Ui.gap(login,8);
        String cookie=CookieManager.getInstance().getCookie("https://www.bilibili.com/");boolean logged=cookie!=null&&cookie.contains("SESSDATA=");
        login.addView(Ui.text(this,logged?"检测到助手内的网页登录会话":"获取 AI 字幕可能需要在助手内登录",13,Ui.MUTED,false));Ui.gap(login,12);
        login.addView(Ui.button(this,logged?"查看 / 更新登录":"打开官方网页登录",false,v->startActivity(new Intent(this,LoginActivity.class))));Ui.gap(login,8);
        if(logged)login.addView(Ui.button(this,"退出助手内的登录",false,v->CookieManager.getInstance().removeAllCookies(success->{CookieManager.getInstance().flush();render();toast("已退出助手内的登录");})));
        body.addView(login);
        LinearLayout about=Ui.card(this);about.addView(Ui.text(this,"关于随看",16,Ui.INK,true));Ui.gap(about,8);
        about.addView(Ui.text(this,"版本 0.1.7\n作者 Cin",13,Ui.MUTED,false));body.addView(about);
    }
    private void importMenu(){if(SummaryService.running){toast("请先等待或停止当前总结");return;}
        new AlertDialog.Builder(this).setTitle("导入完整字幕 / 文稿").setItems(new String[]{"选择 TXT / SRT / VTT 文件","粘贴文稿"},(d,i)->{if(i==0){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,21);}else pasteTranscript();}).show();
    }
    private void pasteTranscript(){EditText field=Ui.input(this,"粘贴完整字幕，可带时间戳",imported);field.setMinLines(8);field.setMaxLines(14);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("粘贴完整文稿").setView(field).setNegativeButton("取消",null).setPositiveButton("载入",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{Transcript.parse(field.getText().toString());rememberInput();imported=field.getText().toString();d.dismiss();render();}catch(Exception e){toast(e.getMessage());}}));d.show();
    }
    private void toggleOverlay(){rememberInput();settings.lastInput(input);
        if(OverlayService.visible){stopService(new Intent(this,OverlayService.class));toast("悬浮按钮已关闭");render();return;}
        if(!Settings.canDrawOverlays(this)){new AlertDialog.Builder(this).setTitle("开启悬浮按钮").setMessage("请允许「随看」显示在其他应用上层，然后返回这里再次点击开启。").setNegativeButton("取消",null).setPositiveButton("前往设置",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))).show();return;}
        try{startForegroundService(new Intent(this,OverlayService.class));toast("悬浮按钮已开启");}catch(Exception e){toast("无法显示悬浮按钮："+e.getMessage());}
    }
    private void demo(){try(InputStream in=getAssets().open("demo.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);currentNote=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));rememberInput();render();}catch(Exception e){toast("示例载入失败");}}
    private String readPrivate(String name)throws Exception{return new String(new android.util.AtomicFile(new java.io.File(getCacheDir(),name)).readFully(),StandardCharsets.UTF_8);}
    private void writePrivate(String name,String text)throws Exception{android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(getCacheDir(),name));java.io.FileOutputStream out=null;try{out=file.startWrite();out.write(text.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
    private void saveDocument(String text,String mime,String name)throws Exception{writePrivate("pending-export.txt",text);name=name.replaceAll("[\\\\/:*?\"<>|]","_");int dot=name.lastIndexOf('.');String extension=dot>=0?name.substring(dot):"";String base=dot>=0?name.substring(0,dot):name;if(base.length()>65)base=base.substring(0,65);startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,base+extension),22);}
    private void export(){if(currentNote==null)return;try{saveDocument(LocalStore.markdown(currentNote),"text/markdown",currentNote.optString("title","视频笔记")+"-"+SummaryDepth.label(SummaryDepth.ofNote(currentNote))+".md");}catch(Exception e){toast("无法导出："+e.getMessage());}}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==21)try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1500000)throw new IllegalArgumentException("文件过大，请导入 30 万字符以内的文稿");out.write(buffer,0,n);}String text=new String(out.toByteArray(),StandardCharsets.UTF_8);Transcript.parse(text);rememberInput();imported=text;tab="home";render();toast("文稿已载入");
        }catch(Exception e){toast("导入失败："+e.getMessage());}
        else if(request==22)try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IllegalArgumentException("无法打开所选文件");out.write(readPrivate("pending-export.txt").getBytes(StandardCharsets.UTF_8));toast("完整文件已保存");}catch(Exception e){toast("保存失败："+e.getMessage());}
    }
}
