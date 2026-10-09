package com.suikan.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.LinearLayout;

/** Isolated in-app web login; the app never receives the account password. */
public final class LoginActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=Ui.column(this);root.setBackgroundColor(Color.WHITE);Ui.padding(root,12);
        root.addView(Ui.button(this,"完成网页登录，返回随看",true,v->{CookieManager.getInstance().flush();finish();}));Ui.gap(root,8);
        root.addView(Ui.text(this,"登录会话仅用于读取 B 站视频信息与字幕。请在下方官方网页完成登录。",12,Ui.MUTED,false));Ui.gap(root,8);
        web=new WebView(this);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                android.net.Uri uri=request.getUrl();return !"https".equals(uri.getScheme())||(request.isForMainFrame()&&!VideoLink.isBiliHost(uri.getHost()));
            }
        });root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(Ui.dp(this,12),Ui.dp(this,12)+insets.getSystemWindowInsetTop(),Ui.dp(this,12),Ui.dp(this,12)+insets.getSystemWindowInsetBottom());return insets;});
        web.loadUrl("https://passport.bilibili.com/login");
    }
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override public void onDestroy(){if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
