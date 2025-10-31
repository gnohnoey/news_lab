package com.example.neutral_news.controller.trend;

import com.example.neutral_news.dto.*;
import com.example.neutral_news.service.combine.CombinedTrendService;
import com.example.neutral_news.service.analzer.KeywordExtractorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/trends")
public class TrendCombinedController {

    @Value("${naver.client-id}")     private String clientId;
    @Value("${naver.client-secret}") private String clientSecret;
    @Value("${naver.news-url}")      private String newsUrl;

    private final ObjectMapper om = new ObjectMapper();
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KeywordExtractorService extractor;
    private final CombinedTrendService combined;

    public TrendCombinedController(KeywordExtractorService extractor, CombinedTrendService combined) {
        this.extractor = extractor;
        this.combined = combined;
    }

    // 예)
    // GET /api/trends/auto/combined?query=트럼프&months=3&topN=30&display=100&sort=date&maxRequests=15&wNews=0.6&wData=0.4
    @GetMapping("/auto/combined")
    public ResponseEntity<CombinedTrendResponse> autoCombined(
            @RequestParam String query,
            @RequestParam(defaultValue = "3")   int months,
            @RequestParam(defaultValue = "30")  int topN,
            @RequestParam(defaultValue = "100") int display,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "15")  int maxRequests,
            @RequestParam(defaultValue = "0.6") double wNews,
            @RequestParam(defaultValue = "0.4") double wData
    ) throws Exception {
        display     = Math.max(1, Math.min(display, 100));
        months      = Math.max(1, Math.min(months, 12));
        maxRequests = Math.max(1, Math.min(maxRequests, 50));
        if (!sort.equals("sim") && !sort.equals("date")) sort = "date";

        // 1) 수집(최신순 페이지네이션, months 이전이면 중단)
        List<NewsItem> items = collectRecentByMonths(query, months, display, sort, maxRequests);

        // 2) 형태소 기반 TopN
        List<KeywordCount> top = extractor.extractTopN(items, topN);

        // 3) 주차 수(≈개월→주), 과도값 가드
        int weeks = months * 4 + 1;
        if (weeks < 8) weeks = 8;
        if (weeks > 26) weeks = 26;

        // 4) 결합 트렌드
        CombinedTrendResponse resp = combined.build(items, top, weeks, wNews, wData);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auto/keywords")
    public ResponseEntity<KeywordListResponse> autoKeywords(
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

        // 1) 기사 수집 (기존 collectRecentByMonths 사용)
        List<NewsItem> items = collectRecentByMonths(query, months, display, sort, maxRequests);

        // 2) KOMORAN으로 키워드 TopN
        List<KeywordCount> top = extractor.extractTopN(items, topN);

        // 3) 간단 응답으로 반환
        return ResponseEntity.ok(new KeywordListResponse(top.size(), top));
    }

    // --------------------------------------

    private List<NewsItem> collectRecentByMonths(String query, int months, int display, String sort, int maxRequests) throws Exception {
        var now = ZonedDateTime.now(KST);
        var since = now.minusMonths(months);

        List<NewsItem> all = new ArrayList<>();
        Set<LocalDate> seenWeeks = new HashSet<>(); // ✅ 주차 커버리지 추적용 세트 추가
        int start = 1, requests = 0;

        while (true) {
            if (requests >= maxRequests || start > 1000) break;
            List<NewsItem> page = fetchNewsPage(query, display, start, sort);
            requests++;
            if (page.isEmpty()) break;

            boolean olderReached = false;
            for (NewsItem it : page) {
                var pub = parsePub(it.pubDate());
                if (pub != null) {
                    // 주차별로 기사 분포 추적
                    LocalDate ws = pub.toLocalDate().minusDays((pub.getDayOfWeek().getValue() + 6) % 7);
                    seenWeeks.add(ws);
                    System.out.printf("pub=%s | weekStart=%s%n", pub, ws);
                }
                if (pub != null && pub.isBefore(since)) {
                    olderReached = true;
                    break;
                }
                all.add(it);
            }

            // ✅ 최소 6주 이상 커버되었을 때만 중단 (너무 빨리 끊기 방지)
            if (olderReached && seenWeeks.size() >= 6) break;

            start += display;
        }

        return all;
    }

    private ZonedDateTime parsePub(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            return ZonedDateTime.parse(pubDate, RFC1123).withZoneSameInstant(KST);
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
                String pub = it.path("pubDate").asText(null);
                System.out.println("pubDate=" + pub);
            }
            return list;
        }
    }
}