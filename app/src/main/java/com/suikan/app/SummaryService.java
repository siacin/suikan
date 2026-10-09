package com.suikan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONObject;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SummaryService extends Service {
    static final String UPDATE="com.suikan.app.UPDATE",STOP="stop";
    static volatile boolean running=false;
    static volatile String message="",error="",noteId="",draft="";
    static volatile int percent=0;
    static volatile boolean modelWaiting=false;
    static volatile boolean extracting=false,subtitleReady=false;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(); private volatile Http.Control control;
    private volatile String stage="";
    private final android.os.Handler ticker=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){@Override public void run(){
        Http.Control task=control;
        if(running&&task!=null){
            modelWaiting=task.modelActive;message=stage+(modelWaiting?"\n"+task.diagnostic():"");
            draft=modelWaiting&&task.previewEnabled?task.preview:"";
            getSystemService(NotificationManager.class).notify(10,notification(message,percent));
            sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()).putExtra("status_only",true));ticker.postDelayed(this,3000);
        }
    }};
    static void probe(Context c){c.startForegroundService(new Intent(c,SummaryService.class).putExtra("probe",true));}
    static void begin(Context c,String input,String imported,boolean force) throws Exception {
        enqueue(c,input,imported,force,false);
    }
    static void extract(Context c,String input,String imported)throws Exception{enqueue(c,input,imported,false,true);}
    private static void enqueue(Context c,String input,String imported,boolean force,boolean subtitles)throws Exception{
        if(input.length()>10000)throw new IllegalArgumentException("分享文字过长，请只粘贴视频链接");
        if(imported.length()>Transcript.MAX_CHARS)throw new IllegalArgumentException("字幕超过 30 万字符，请拆分后导入");
        java.io.File tasks=new java.io.File(c.getCacheDir(),"summary-tasks");tasks.mkdirs();
        String filename=java.util.UUID.randomUUID().toString().replace("-","")+".json";
        java.io.File payload=new java.io.File(tasks,filename);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(payload)){
            out.write(new JSONObject().put("input",input).put("transcript",imported).put("depth",new SecureSettings(c).summaryDepth()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try{c.startForegroundService(new Intent(c,SummaryService.class).putExtra("payload_file",filename).putExtra("force",force).putExtra("subtitles",subtitles));}
        catch(Exception e){payload.delete();throw e;}
    }
    private Notification notification(String text,int progress) {
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("summary","视频总结进度",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,SummaryService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"summary").setSmallIcon(R.drawable.ic_launcher).setContentTitle(extracting?"随看 · 正在提取字幕":"随看 · 正在总结视频").setContentText(text)
                .setContentIntent(open).setOngoing(true).setProgress(100,progress,progress<1||modelWaiting).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    private void update(int p,String text) {
        draft="";
        percent=p;stage=text;modelWaiting=control!=null&&control.modelActive;message=text;getSystemService(NotificationManager.class).notify(10,notification(text,p));
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){if(control!=null)control.cancel();return START_NOT_STICKY;}
        final boolean probe=intent.getBooleanExtra("probe",false);
        final boolean subtitles=!probe&&intent.getBooleanExtra("subtitles",false);
        String filename=intent.getStringExtra("payload_file");
        if(!probe&&(filename==null||!filename.matches("[a-f0-9]{32}\\.json"))){stopSelf(startId);return START_NOT_STICKY;}
        final java.io.File payloadFile=probe?null:new java.io.File(new java.io.File(getCacheDir(),"summary-tasks"),filename);
        if(running){if(payloadFile!=null)payloadFile.delete();return START_NOT_STICKY;}
        running=true;extracting=subtitles;subtitleReady=false;error="";noteId="";draft="";percent=0;modelWaiting=false;stage=message=probe?"正在测试模型连接":"准备读取视频";control=new Http.Control();
        Notification n=notification(message,0);
        if(Build.VERSION.SDK_INT>=34)startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(10,n);
        boolean force=intent.getBooleanExtra("force",false);
        final Http.Control task=control;
        ticker.postDelayed(heartbeat,1000);
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
        executor.execute(()->{
            try {
                if(probe){
                    task.check();SecureSettings settings=new SecureSettings(this);long begin=System.nanoTime();
                    Http.complete(settings.endpoint(),settings.model(),settings.key(),"这是接口连接测试。只回复 OK，不需要分析或解释。",new JSONObject().put("test","connection"),task,0,settings.disableDeepSeekThinking(),settings.lightGlmThinking());
                    percent=100;message="模型连接测试通过，用时 "+java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-begin)+" 秒。已收到完整回答；长视频仍需保持连接稳定。";
                    return;
                }
                task.check();byte[] payload=java.nio.file.Files.readAllBytes(payloadFile.toPath());payloadFile.delete();
                JSONObject request=new JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8));
                if(subtitles){JSONObject source=VideoSource.read(request.optString("input"),request.optString("transcript"),task,this::update);task.check();new SubtitleStore(this).save(source);subtitleReady=true;percent=100;message="完整字幕已提取，可复制或保存文件";return;}
                JSONObject note=new SummaryEngine(this,task).run(request.optString("input"),request.optString("transcript"),force,request.optString("depth",SummaryDepth.STANDARD),this::update);
                noteId=note.optString("id");message="整段视频总结已保存";
            }catch(CancellationException e){message="已停止任务";}
            catch(Exception e){if(task.cancelled.get())message="已停止任务";else{error=Http.redact(e.getMessage(),safeKey());message=subtitles?"这次字幕提取未完成":"这次总结未完成";}}
            finally{if(payloadFile!=null)payloadFile.delete();running=false;modelWaiting=false;draft="";ticker.removeCallbacks(heartbeat);sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}
        });return START_NOT_STICKY;
    }
    private String safeKey(){try{return new SecureSettings(this).key();}catch(Exception e){return "";}}
    @Override public void onTimeout(int startId,int fgsType){if(control!=null)control.cancel();error="后台总结达到系统时限，已停止";running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){ticker.removeCallbacks(heartbeat);if(control!=null)control.cancel();executor.shutdownNow();running=false;modelWaiting=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
