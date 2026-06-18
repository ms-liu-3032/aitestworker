package com.company.aitest.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KnowledgeChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final int MIN_CHUNK = 600;
    private static final int MAX_CHUNK = 900;
    private static final int TARGET_CHUNK = 750;
    private static final int MIN_OVERLAP = 80;
    private static final int MAX_OVERLAP = 120;
    private static final int TARGET_OVERLAP = 100;

    private final int minChunk;
    private final int maxChunk;
    private final int targetChunk;
    private final int minOverlap;
    private final int maxOverlap;
    private final int targetOverlap;

    public KnowledgeChunker() {
        this(MIN_CHUNK, MAX_CHUNK, TARGET_CHUNK, MIN_OVERLAP, MAX_OVERLAP, TARGET_OVERLAP);
    }

    public KnowledgeChunker(int minChunk, int maxChunk, int targetChunk, int minOverlap, int maxOverlap, int targetOverlap) {
        this.minChunk = minChunk;
        this.maxChunk = maxChunk;
        this.targetChunk = targetChunk;
        this.minOverlap = minOverlap;
        this.maxOverlap = maxOverlap;
        this.targetOverlap = targetOverlap;
    }

    public List<KnowledgeChunk> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= minChunk) {
            return List.of(new KnowledgeChunk(1, detectHeadingPath(text, 0), text, hash(text)));
        }

        List<KnowledgeChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkNo = 1;

        while (start < text.length()) {
            int end = findChunkEnd(text, start);
            String content = text.substring(start, end);
            chunks.add(new KnowledgeChunk(chunkNo, detectHeadingPath(text, start), content, hash(content)));
            chunkNo++;
            if (end >= text.length()) {
                break;
            }
            start = end - targetOverlap;
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }

    private int findChunkEnd(String text, int start) {
        int idealEnd = start + targetChunk;
        if (idealEnd >= text.length()) {
            return text.length();
        }
        int best = idealEnd;
        for (int p : new int[]{idealEnd, idealEnd + 1, idealEnd - 1, idealEnd + 50, idealEnd - 50}) {
            if (p > start + minChunk && p <= start + maxChunk && p <= text.length()) {
                int idx = findBreak(text, p);
                if (idx >= 0) {
                    return idx;
                }
            }
        }
        int fallback = Math.min(start + maxChunk, text.length());
        return Math.max(fallback, start + minChunk);
    }

    private int findBreak(String text, int around) {
        int searchStart = Math.max(around - 80, 0);
        int searchEnd = Math.min(around + 80, text.length());
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = searchStart; i < searchEnd; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '\r' || c == '.' || c == '!' || c == '?') {
                int dist = Math.abs(i - around);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i + 1;
                }
            }
        }
        return best;
    }

    String detectHeadingPath(String text, int position) {
        List<String> headings = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        while (m.find()) {
            if (m.start() > position) {
                break;
            }
            int level = m.group(1).length();
            String title = m.group(2).strip();
            while (headings.size() >= level) {
                headings.remove(headings.size() - 1);
            }
            headings.add(title);
        }
        return String.join(" > ", headings);
    }

    public static String hash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
