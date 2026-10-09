package com.suikan.app;

import java.util.List;

/** Plain Java checks, executable without an emulator or paid API requests. */
public final class CoreTests {
    private static int checks=0;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(String input)throws Exception{boolean failed=false;try{VideoLink.parse(input,u->u);}catch(Exception e){failed=true;}check(failed,"reject "+input);}
    public static void main(String[] args)throws Exception{
        VideoLink direct=VideoLink.parse("这是分享文字 https://www.bilibili.com/video/BV1fX4y1Q7Ux?p=2。",u->u);
        check(direct.bvid.equals("BV1fX4y1Q7Ux")&&direct.page==2,"direct share + part");
        check(VideoLink.parse("BV1fX4y1Q7Ux",u->u).page==1,"bare id");
        check(VideoLink.parse("https://b23.tv/test",u->"https://www.bilibili.com/video/BV1fX4y1Q7Ux?p=3").page==3,"short link resolved part");
        rejects("https://evil.example/video/BV1fX4y1Q7Ux");rejects("https://bilibili.com.evil.example/video/BV1fX4y1Q7Ux");
        rejects("https://evil.example@www.bilibili.com/video/BV1fX4y1Q7Ux");rejects("https://www.bilibili.com/video/BV1fX4y1Q7Ux?p=0");rejects("https://www.bilibili.com/video/BV1fX4y1Q7Ux?p=cat");
        boolean escaped=false;try{VideoLink.parse("https://b23.tv/test",u->"https://evil.example/video/BV1fX4y1Q7Ux");}catch(Exception e){escaped=true;}check(escaped,"short redirect remains scoped");
        List<Transcript.Segment> srt=Transcript.parse("\uFEFF1\n00:00:01,125 --> 00:00:03,500\n第一行\n第二行\n\n2\n00:00:05,050 --> 00:00:06,250\n结尾\n");
        check(srt.size()==2,"SRT count");check(Math.abs(srt.get(0).start-1.125)<.0001&&Math.abs(srt.get(0).end-3.5)<.0001,"fractional timing");check(srt.get(0).text.equals("第一行\n第二行"),"multiline source preserved");
        check(Transcript.parse("WEBVTT\n\n00:00:02.500 --> 00:00:04.500 align:start\n内容\n").get(0).start==2.5,"VTT settings");
        List<Transcript.Segment> plain=Transcript.parse("没有时间戳的第一段\n下一段");check(plain.get(0).start==-1&&plain.size()==2,"no invented times");
        check(Transcript.parse("[01:02] 第一段\n[01:03.125] 下一段").get(1).start==63.125,"inline timestamps");
        check(Transcript.seconds("00:99:01")==-1,"invalid minutes");check(Transcript.stamp(3661).equals("1:01:01"),"hours");
        StringBuilder longSource=new StringBuilder();for(int i=0;i<10000;i++)longSource.append("内容");
        List<Transcript.Segment> longSegments=Transcript.parse(longSource.toString());List<List<Transcript.Segment>> chunks=Transcript.chunks(longSegments,10000);int chars=0;
        for(List<Transcript.Segment> chunk:chunks)for(Transcript.Segment s:chunk)chars+=s.text.length();check(chars==longSource.length()&&chunks.size()>1,"all long transcript content retained");
        boolean tooLong=false;try{Transcript.parse(new String(new char[Transcript.MAX_CHARS+1]).replace('\0','字'));}catch(IllegalArgumentException e){tooLong=true;}check(tooLong,"oversized transcript explicit failure");
        check(new Transcript.Segment("s1",Double.NaN,10,"a").start==-1,"invalid native times");
        System.out.println("PASS "+checks+" core checks");
    }
}
