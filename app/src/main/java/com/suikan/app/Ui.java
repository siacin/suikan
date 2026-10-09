package com.suikan.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(248,247,250), INK = Color.rgb(33,35,49), MUTED = Color.rgb(115,116,133), ACCENT = Color.rgb(217,68,112), LINE = Color.rgb(231,230,237);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(Context c, int color, int radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); if (border) d.setStroke(dp(c,1), LINE); return d;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static void padding(View v, int n) { int d = dp(v.getContext(),n); v.setPadding(d,d,d,d); }
    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(c,4),1); if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    static TextView text(Context c, String value, int size) { return text(c,value,size,INK,false); }
    static Button button(Context c, String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(c); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(background(c,primary ? ACCENT : Color.WHITE,14,!primary)); b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48));
        b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8)); b.setOnClickListener(listener); return b;
    }
    static EditText input(Context c, String hint, String value) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(value); e.setTextSize(15); e.setTextColor(INK); e.setHintTextColor(MUTED);
        e.setBackground(background(c,BG,12,true)); padding(e,14); e.setMinHeight(dp(c,54)); return e;
    }
    static LinearLayout card(Context c) {
        LinearLayout v = column(c); padding(v,18); v.setBackground(background(c,Color.WHITE,20,true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(c,14); v.setLayoutParams(p); return v;
    }
    static void gap(LinearLayout v,int height) { View gap = new View(v.getContext()); v.addView(gap,new LinearLayout.LayoutParams(1,dp(v.getContext(),height))); }
    static void weighted(LinearLayout row, View v) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(row.getContext(),3),0,dp(row.getContext(),3),0); row.addView(v,p); }
}
