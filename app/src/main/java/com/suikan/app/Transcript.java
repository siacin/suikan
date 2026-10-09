package com.suikan.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Transcript {
    public static final int MAX_CHARS = 300000, MAX_SEGMENTS = 8000;
    public static final class Segment {
        public final String id, text;
        public final double start, end;
        public Segment(String id, double start, double end, String text) {
            this.id = id; this.start = Double.isFinite(start) && start >= 0 ? start : -1;
            this.end = Double.isFinite(end) && end > this.start && this.start >= 0 ? end : -1;
            this.text = text.trim();
        }
    }
    private static final Pattern TIME = Pattern.compile("(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?");
    private static final Pattern INLINE = Pattern.compile("^\\[?((?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?)\\]?\\s+(.+)$");
    public static double seconds(String text) {
        Matcher m = TIME.matcher(text.trim());
        if (!m.matches()) return -1;
        int minutes = Integer.parseInt(m.group(2)), seconds = Integer.parseInt(m.group(3));
        if (minutes > 59 || seconds > 59) return -1;
        String f = m.group(4);
        return Integer.parseInt(m.group(1) == null ? "0" : m.group(1)) * 3600 + minutes * 60 + seconds
                + (f == null ? 0 : Double.parseDouble("0." + f));
    }
    public static String stamp(double time) {
        if (time < 0 || !Double.isFinite(time)) return "无时间戳";
        int t = (int) time;
        return t >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", t / 60, t % 60);
    }
    private static void add(List<Segment> rows, double start, double end, String text) {
        text = text.trim(); if (text.isEmpty()) return;
        for (int i = 0; i < text.length(); i += 1800) {
            if (rows.size() >= MAX_SEGMENTS) throw new IllegalArgumentException("字幕超过 8000 段，请拆分后导入");
            rows.add(new Segment("s" + (rows.size() + 1), start, end, text.substring(i, Math.min(i + 1800, text.length()))));
        }
    }
    public static List<Segment> parse(String source) {
        if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("字幕内容为空");
        if (source.length() > MAX_CHARS) throw new IllegalArgumentException("字幕超过 30 万字符，请拆分后导入");
        String[] lines = source.replace("\uFEFF", "").replace("\r", "").split("\n", -1);
        List<Segment> rows = new ArrayList<>();
        boolean timed = source.contains("-->");
        if (timed) {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim(); if (!line.contains("-->")) continue;
                String[] times = line.split("-->", 2);
                double start = seconds(times[0]), end = seconds(times[1].trim().split("\\s+")[0]);
                StringBuilder body = new StringBuilder();
                while (++i < lines.length && !lines[i].trim().isEmpty()) {
                    if (body.length() > 0) body.append('\n'); body.append(lines[i].trim());
                }
                add(rows, start, end, body.toString());
            }
        } else {
            for (String line : lines) {
                Matcher m = INLINE.matcher(line.trim());
                if (m.matches()) add(rows, seconds(m.group(1)), -1, m.group(2));
                else add(rows, -1, -1, line);
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("没有可读取的字幕文字");
        return rows;
    }
    public static List<List<Segment>> chunks(List<Segment> segments, int budget) {
        List<List<Segment>> chunks = new ArrayList<>(); List<Segment> current = new ArrayList<>(); int count = 0;
        for (Segment s : segments) {
            int cost = s.text.length() + 60;
            if (!current.isEmpty() && count + cost > budget) { chunks.add(current); current = new ArrayList<>(); count = 0; }
            current.add(s); count += cost;
        }
        if (!current.isEmpty()) chunks.add(current); return chunks;
    }
}
