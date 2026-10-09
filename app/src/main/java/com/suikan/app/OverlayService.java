package com.suikan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

public final class OverlayService extends Service {
    static volatile boolean visible=false;
    private WindowManager wm;private View bubble,panel;private WindowManager.LayoutParams bubbleParams;
    private TextView progressText;
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(intent.getBooleanExtra("status_only",false)){if(progressText!=null)progressText.setText(SummaryService.message);return;}
        if(panel!=null){hidePanel();showPanel();}
    }};
    @Override public void onCreate(){super.onCreate();wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        IntentFilter filter=new IntentFilter(SummaryService.UPDATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updates,filter);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"close".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("overlay","悬浮按钮",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close=PendingIntent.getService(this,4,new Intent(this,OverlayService.class).setAction("close"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"overlay").setSmallIcon(R.drawable.ic_launcher).setContentTitle("随看悬浮按钮已开启").setContentText("点按钮查看已分享视频的笔记").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"关闭悬浮按钮",close).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(11,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(11,note);
        if(bubble==null)showBubble();visible=true;sendBroadcast(new Intent(SummaryService.UPDATE).setPackage(getPackageName()).putExtra("overlay_state",true));return START_NOT_STICKY;
    }
    private void showBubble(){
        TextView label=Ui.text(this,"随",20,Color.WHITE,true);label.setGravity(Gravity.CENTER);label.setBackground(Ui.background(this,Ui.ACCENT,28,false));bubble=label;
        bubbleParams=new WindowManager.LayoutParams(Ui.dp(this,54),Ui.dp(this,54),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        bubbleParams.gravity=Gravity.TOP|Gravity.LEFT;bubbleParams.x=getResources().getDisplayMetrics().widthPixels-Ui.dp(this,64);bubbleParams.y=Ui.dp(this,220);wm.addView(bubble,bubbleParams);
        bubble.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy;boolean moved;
            public boolean onTouch(View v,MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();ox=bubbleParams.x;oy=bubbleParams.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>Ui.dp(OverlayService.this,8))moved=true;
                    bubbleParams.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(OverlayService.this,54),ox+(int)dx));bubbleParams.y=Math.max(Ui.dp(OverlayService.this,24),Math.min(getResources().getDisplayMetrics().heightPixels-Ui.dp(OverlayService.this,80),oy+(int)dy));wm.updateViewLayout(bubble,bubbleParams);return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(!moved){if(panel==null)showPanel();else hidePanel();}return true;}return false;}
        });
    }
    private void showPanel(){
        LinearLayout root=Ui.column(this);root.setBackground(Ui.background(this,Ui.BG,20,true));Ui.padding(root,14);
        LinearLayout head=Ui.row(this);Ui.weighted(head,Ui.text(this,"随看 · 视频笔记",17,Ui.INK,true));head.addView(Ui.button(this,"收起",false,v->hidePanel()));root.addView(head);Ui.gap(root,10);
        ScrollView scroll=new ScrollView(this);LinearLayout content=Ui.column(this);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        String input=new SecureSettings(this).lastInput();
        if(SummaryService.running){progressText=Ui.text(this,SummaryService.message,14,Ui.ACCENT,true);content.addView(progressText);Ui.gap(content,10);content.addView(Ui.button(this,"停止本次总结",false,v->startService(new Intent(this,SummaryService.class).setAction(SummaryService.STOP))));}
        else{
            content.addView(Ui.text(this,input.isEmpty()?"先从 B 站分享视频给「随看」。":"已分享的链接：\n"+(input.length()>120?input.substring(0,120)+"…":input),12,Ui.MUTED,false));Ui.gap(content,10);
            if(!input.isEmpty())content.addView(Ui.button(this,"总结已分享的视频",true,v->{try{SummaryService.begin(this,input,"",false);}catch(Exception e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}));
            if(!SummaryService.error.isEmpty()){Ui.gap(content,10);content.addView(Ui.text(this,SummaryService.error,12,Ui.ACCENT,false));}
        }
        Ui.gap(content,14);List<JSONObject> notes=new LocalStore(this).list();if(!notes.isEmpty()){content.addView(Ui.text(this,"最近保存的笔记",12,Ui.MUTED,true));Ui.gap(content,8);ResultViews.render(this,content,notes.get(0),true,true);}
        else content.addView(Ui.text(this,"总结完成后，笔记会显示在这里。",14,Ui.MUTED,false));
        Ui.gap(content,10);content.addView(Ui.text(this,"换视频后请重新分享链接。",11,Ui.MUTED,false));
        root.addView(Ui.button(this,"打开随看",false,v->{hidePanel();startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}));
        int width=Math.min(Ui.dp(this,360),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24));int height=Math.min(Ui.dp(this,580),getResources().getDisplayMetrics().heightPixels-Ui.dp(this,100));
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);params.gravity=Gravity.CENTER;panel=root;wm.addView(panel,params);
    }
    private void hidePanel(){progressText=null;if(panel!=null){try{wm.removeView(panel);}catch(Exception ignored){}panel=null;}}
    @Override public void onDestroy(){hidePanel();if(bubble!=null)try{wm.removeView(bubble);}catch(Exception ignored){}visible=false;unregisterReceiver(updates);sendBroadcast(new Intent(SummaryService.UPDATE).setPackage(getPackageName()).putExtra("overlay_state",true));stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
