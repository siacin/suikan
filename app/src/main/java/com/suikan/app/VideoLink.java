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
            value = um.group().replaceAll("[，。；、）】》]+$", "");
            if (value.startsWith("http://")) value = "https://" + value.substring(7);
            checkedUrl(value);
            URI uri = new URI(value);
            if ("b23.tv".equalsIgnoreCase(uri.getHost())) {
                value = checkedUrl(resolver.resolve(value)); uri = new URI(value);
            }
            Matcher bm = BV.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!bm.find()) throw new IllegalArgumentException("链接中没有找到 BV 号，请分享普通投稿视频");
            int page = 1;
            if (uri.getQuery() != null) for (String pair : uri.getQuery().split("&")) {
                if (pair.startsWith("p=")) {
                    try { page = Integer.parseInt(pair.substring(2)); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("分 P 编号无效"); }
                }
            }
            if (page < 1 || page > 10000) throw new IllegalArgumentException("分 P 编号无效");
            return new VideoLink(bm.group(1), page);
        }
        Matcher bm = BV.matcher(value);
        if (!bm.find()) throw new IllegalArgumentException("请粘贴 B 站链接、分享文字或 BV 号");
        return new VideoLink(bm.group(1), 1);
    }
}
