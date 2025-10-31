package com.example.neutral_news.controller.trend;

import com.example.neutral_news.dto.KeywordCount;
import com.example.neutral_news.dto.NewsItem;
import com.example.neutral_news.dto.TrendResponse;
import com.example.neutral_news.service.analzer.KeywordExtractorService;
import com.example.neutral_news.service.analzer.TrendService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;

@RestController
@RequestMapping("/api/trends")
public class TrendAutoController {

    @Value("${naver.client-id}")     private String clientId;
    @Value("${naver.client-secret}") private String clientSecret;
    @Value("${naver.news-url}")      private String newsUrl;

    private final ObjectMapper om = new ObjectMapper();
    private final KeywordExtractorService extractor;
    private final TrendService trendService;

    public TrendAutoController(KeywordExtractorService extractor, TrendService trendService) {
        this.extractor = extractor;
        this.trendService = trendService;
    }

    /**
     * 예) GET /api/trends/auto?query=트럼프&months=3&topN=30&display=100&sort=date&maxRequests=15
     * 1) 뉴스 수집(최신→과거, months 이전이면 중단)
     * 2) 형태소 기반 TopN 키워드 추출
     * 3) 최근 N주(기본 13주) 트렌드 계산
     */
    @GetMapping("/auto")
    public ResponseEntity<TrendResponse> auto(
            @RequestParam String query,
            @RequestParam(defaultValue = "3")   int months,
            @RequestParam(defaultValue = "30")  int topN,
            @RequestParam(defaultValue = "100") int display,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "15")  int maxRequests
    ) throws Exception {
        display     = Math.max(1, Math.min(display, 100));
        months      = Math.max(1, Math.min(months, 12));
        maxRequests = Math.max(1, Math.min(maxRequests, 50));
        if (!sort.equals("sim") && !sort.equals("date")) sort = "date";

        // 1) 수집
        List<NewsItem> collected = collectRecentByMonths(query, months, display, sort, maxRequests);

        // 2) TopN 키워드
        List<KeywordCount> top = extractor.extractTopN(collected, topN);

        // 3) 최근 13주(≈3개월) 트렌드
        int weeks = months * 4 + 1; // 대략치(3개월≈13주)
        if (weeks < 8) weeks = 8;   // 최소 8주
        if (weeks > 26) weeks = 26; // 최대 반년치
        TrendResponse resp = trendService.computeWeeklyTrends(collected, top, weeks);
        return ResponseEntity.ok(resp);
    }

    // ----- 내부: 네이버 뉴스 페이지네이션(기간 기준 중단) -----

    private List<NewsItem> collectRecentByMonths(String query, int months, int display, String sort, int maxRequests) throws Exception {
        var KST = java.time.ZoneId.of("Asia/Seoul");
        var now = java.time.ZonedDateTime.now(KST);
        var since = now.minusMonths(months);

        List<NewsItem> all = new ArrayList<>();
        int start = 1;
        int requests = 0;

        while (true) {
            if (requests >= maxRequests) break;
            if (start > 1000) break;

            List<NewsItem> page = fetchNewsPage(query, display, start, sort);
            requests++;
            if (page.isEmpty()) break;

            boolean olderReached = false;
            for (NewsItem it : page) {
                var pub = parsePub(it.pubDate());
                if (pub != null && pub.isBefore(since)) {
                    olderReached = true;
                    break;
                }
                all.add(it);
            }
            if (olderReached) break;

            start += display;
        }
        return all;
    }

    private java.time.ZonedDateTime parsePub(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            return java.time.ZonedDateTime.parse(pubDate, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"));
        } catch (Exception e) { return null; }
    }

    private List<NewsItem> fetchNewsPage(String query, int display, int start, String sort) throws Exception {
        String q = URLEncoder.encode(query, "UTF-8");
        String url = String.format("%s?query=%s&display=%d&start=%d&sort=%s", newsUrl, q, display, start, sort);

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("X-Naver-Client-Id", clientId);
        con.setRequestProperty("X-Naver-Client-Secret", clientSecret);

        try (var br = new BufferedReader(new InputStreamReader(
                con.getResponseCode() == 200 ? con.getInputStream() : con.getErrorStream()
        ))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);

            JsonNode root = om.readTree(sb.toString());
            JsonNode items = root.path("items");
            List<NewsItem> list = new ArrayList<>();

            for (JsonNode it : items) {
                list.add(new NewsItem(
                        it.path("title").asText(null),
                        it.path("description").asText(null),
                        it.path("pubDate").asText(null),
                        it.path("originallink").asText(null),
                        it.path("link").asText(null)
                ));
            }
            return list;
        }
    }
}