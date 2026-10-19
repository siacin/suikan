package com.suikan.app;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure link parsing; redirects are resolved by a constrained network adapter. */
public final class VideoLink {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BV = Pattern.compile("\\b(BV[0-9A-Za-z]{10})\\b", Pattern.CASE_INSENSITIVE);
    public interface Resolver { String resolve(String url) throws Exception; }
    public final String bvid, url;
    public final int page;
    public String sharedTitle = "";
    long expectedAid,expectedCid;
    private VideoLink(String bvid, int page) {
        this.bvid = "BV" + bvid.substring(2); this.page = page;
        this.url = "https://www.bilibili.com/video/" + this.bvid + (page > 1 ? "?p=" + page : "");
    }
    public static boolean isBiliHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("bilibili.com") || host.endsWith(".bilibili.com") || host.equals("b23.tv");
    }
    public static String checkedUrl(String value) throws Exception {
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !isBiliHost(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("请使用 HTTPS 的 B 站视频链接");
        return value;
    }
    public static VideoLink parse(String input, Resolver resolver) throws Exception {
        String value = input == null ? "" : input.trim();
        Matcher um = URL.matcher(value);
        if (um.find()) {
            String prefix = value.substring(0, um.start()).trim();
            String surroundingText = prefix + " " + value.substring(um.end());
            Matcher otherUrl = URL.matcher(value.substring(um.end()));
            if (otherUrl.find()) throw new IllegalArgumentException("分享文字包含多个链接，无法确定目标视频；请只分享一个视频链接");
            value = um.group().replaceAll("[，。；、）】》]+$", "");
            if (value.startsWith("http://")) value = "https://" + value.substring(7);
            checkedUrl(value);
            URI uri = new URI(value);
            java.util.Map<String,String> hints=parameters(uri);
            if ("b23.tv".equalsIgnoreCase(uri.getHost())) {
                value = checkedUrl(resolver.resolve(value)); uri = new URI(value);
                for(java.util.Map.Entry<String,String> hint:parameters(uri).entrySet()){
                    if(hints.containsKey(hint.getKey())&&!hints.get(hint.getKey()).equals(hint.getValue()))throw new SourceIdentity.Mismatch("短链接跳转前后的视频或分 P 参数冲突");
                    hints.put(hint.getKey(),hint.getValue());
                }
            }
            Matcher bm = Pattern.compile("^/video/(BV[0-9A-Za-z]{10})/?$", Pattern.CASE_INSENSITIVE).matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!bm.matches()) throw new IllegalArgumentException("链接中没有找到普通投稿视频，请重新分享 /video/BV 视频链接");
            int page = 1;
            if(hints.containsKey("p"))try{page=Integer.parseInt(hints.get("p"));}catch(NumberFormatException e){throw new IllegalArgumentException("分 P 编号无效");}
            if (page < 1 || page > 10000) throw new IllegalArgumentException("分 P 编号无效");
            VideoLink parsed = new VideoLink(bm.group(1), page);
            if(hints.containsKey("bvid")&&!parsed.bvid.equals(hints.get("bvid")))throw new SourceIdentity.Mismatch("链接中的 BV 参数与路径不一致");
            parsed.expectedAid=positive(hints.get("aid"));parsed.expectedCid=positive(hints.get("cid"));
            Matcher explicitId = BV.matcher(surroundingText);
            while(explicitId.find())if(!parsed.bvid.equals("BV" + explicitId.group(1).substring(2)))throw new SourceIdentity.Mismatch("分享文字中的 BV 号与链接指向不同视频");
            // Brackets and site suffixes may belong to the real title. Preserve the
            // caption until metadata can distinguish literal text from share formatting.
            if(!prefix.matches("(?i)(?:BV[0-9A-Za-z]{10}|(?:视频)?链接[:：]?)"))parsed.sharedTitle=prefix;
            return parsed;
        }
        Matcher bm = BV.matcher(value);
        if (!bm.find()) throw new IllegalArgumentException("请粘贴 B 站链接、分享文字或 BV 号");
        String id=bm.group(1);while(bm.find())if(!id.equals(bm.group(1)))throw new IllegalArgumentException("包含多个 BV 号，无法确定目标视频");
        return new VideoLink(id, 1);
    }
    private static java.util.Map<String,String> parameters(URI uri)throws Exception{
        java.util.Map<String,String> result=new java.util.HashMap<>();
        if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){
            int split=pair.indexOf('=');String key=java.net.URLDecoder.decode(split<0?pair:pair.substring(0,split),"UTF-8");
            if(!key.equals("p")&&!key.equals("aid")&&!key.equals("cid")&&!key.equals("bvid"))continue;
            String value=java.net.URLDecoder.decode(split<0?"":pair.substring(split+1),"UTF-8");
            if(result.containsKey(key))throw new SourceIdentity.Mismatch("链接包含重复的视频或分 P 参数");result.put(key,value);
        }
        return result;
    }
    private static long positive(String value){
        if(value==null)return 0;try{long n=Long.parseLong(value);if(n>0)return n;}catch(NumberFormatException ignored){}
        throw new SourceIdentity.Mismatch("链接的视频编号参数无效");
    }
}
