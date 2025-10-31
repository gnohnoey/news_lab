package com.example.neutral_news.service.analzer;

import com.example.neutral_news.dto.KeywordCount;
import com.example.neutral_news.dto.NewsItem;
import jakarta.annotation.PostConstruct;
import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import kr.co.shineware.nlp.komoran.model.KomoranResult;
import kr.co.shineware.nlp.komoran.model.Token;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class KeywordExtractorService {

    private Komoran komoran;
    private Set<String> stopwords;
    private static final Pattern NUM_OR_SINGLE = Pattern.compile("^[0-9]+$|^.$");

    @PostConstruct
    void init() throws Exception {
        komoran = new Komoran(DEFAULT_MODEL.FULL);      // 큰 모델
        stopwords = loadStopwords("stopwords.txt");     // resources/stopwords.txt
    }

    private Set<String> loadStopwords(String path) throws Exception {
        var res = new ClassPathResource(path);
        try (var br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            return br.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .collect(Collectors.toSet());
        }
    }

    public List<KeywordCount> extractTopN(List<NewsItem> items, int topN) {
        if (items == null || items.isEmpty()) return List.of();
        topN = Math.max(1, Math.min(topN, 200)); // 안전 가드

        Map<String, Integer> freq = new HashMap<>();

        for (NewsItem item : items) {
            String text = normalize(item.title()) + " " + normalize(item.description());
            if (text.isBlank()) continue;

            KomoranResult r = komoran.analyze(text);
            for (Token t : r.getTokenList()) {
                String pos = t.getPos();
                if (pos.startsWith("NNP") || pos.startsWith("NNG")) {
                    String morph = postFilter(t.getMorph());
                    if (morph != null) freq.merge(morph, 1, Integer::sum);
                }
            }
        }

        return freq.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue()); // 빈도 desc
                    if (c != 0) return c;
                    c = Integer.compare(b.getKey().length(), a.getKey().length()); // 길이 desc
                    if (c != 0) return c;
                    return a.getKey().compareTo(b.getKey()); // 가나다
                })
                .limit(topN)
                .map(e -> new KeywordCount(e.getKey(), e.getValue()))
                .toList();
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")       // HTML 태그 제거
                .replaceAll("[\\p{Punct}]+", " ") // 구두점 제거
                .replaceAll("\\s+", " ")          // 공백 정리
                .trim();
    }

    private String postFilter(String w) {
        if (w == null) return null;
        String t = w.trim();
        if (t.isEmpty()) return null;
        if (NUM_OR_SINGLE.matcher(t).matches()) return null; // 숫자/1글자 제외
        if (stopwords.contains(t)) return null;              // 불용어 제외
        return t;
    }
}