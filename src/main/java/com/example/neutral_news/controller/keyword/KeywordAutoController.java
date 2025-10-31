package com.example.neutral_news.controller.keyword;

import com.example.neutral_news.dto.KeywordCount;
import com.example.neutral_news.dto.NewsItem;
import com.example.neutral_news.service.analzer.KeywordExtractorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/keywords")
public class KeywordAutoController {

    @Value("${naver.client-id}")     private String clientId;
    @Value("${naver.client-secret}") private String clientSecret;
    @Value("${naver.news-url}")      private String newsUrl; // e.g. https://openapi.naver.com/v1/search/news.json

    private final KeywordExtractorService extractor;
    private final ObjectMapper om = new ObjectMapper();
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public KeywordAutoController(KeywordExtractorService extractor) {
        this.extractor = extractor;
    }

    /**
     * 예)
     * GET /api/keywords/auto?query=트럼프&topN=30&months=3&display=100&sort=date&maxRequests=20
     * - months: 최근 N개월까지만 수집 (기본 3개월)
     * - display: 페이지당 기사 수(1~100, 기본 100)
     * - sort: sim|date (기본 date 권장)
     * - maxRequests: API 호출 최대 횟수(과도 호출 방지, 기본 20)
     *
     * 동작:
     * 1) 최신순으로 페이지네이션하며 수집(start += display)
     * 2) 각 아이템의 pubDate가 since(= now - months) 이전이면 수집 중단
     * 3) 수집된 기사 전체에 형태소 분석 → 빈도 Top N 반환
     */
    @GetMapping("/auto")
    public ResponseEntity<List<KeywordCount>> auto(
            @RequestParam String query,
            @RequestParam(defaultValue = "30")  int topN,
            @RequestParam(defaultValue = "3")   int months,
            @RequestParam(defaultValue = "100") int display,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "20")  int maxRequests
    ) throws Exception {

        // 안전 범위 보정
        months      = Math.max(1, Math.min(months, 12));   // 과도한 기간 제한
        display     = Math.max(1, Math.min(display, 100)); // 네이버 제한
        maxRequests = Math.max(1, Math.min(maxRequests, 50));
        if (!sort.equals("sim") && !sort.equals("date")) sort = "date";

        // 기준 시점: 최근 N개월
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        ZonedDateTime since  = nowKst.minusMonths(months);

        List<NewsItem> collected = new ArrayList<>();
        int start = 1; // Naver start는 1부터
        int requests = 0;

        while (true) {
            if (requests >= maxRequests) break;
            if (start > 1000) break; // 네이버 start 상한

            List<NewsItem> page = fetchNewsPage(query, display, start, sort);
            requests++;

            if (page.isEmpty()) break;

            // pubDate 기준으로 기간 필터
            boolean olderReached = false;
            for (NewsItem it : page) {
                ZonedDateTime pub = parsePubDate(it.pubDate());
                // pubDate가 null이면 포함/제외를 어떻게 할지 정책을 정해야 함. 여기선 포함.
                if (pub != null && pub.isBefore(since)) {
                    olderReached = true;
                    break;
                }
                collected.add(it);
            }

            if (olderReached) break;
            start += display; // 다음 페이지
        }

        var result = extractor.extractTopN(collected, topN);
        return ResponseEntity.ok(result);
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

    private ZonedDateTime parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            // 예: "Fri, 31 Oct 2025 09:00:00 +0900"
            return ZonedDateTime.parse(pubDate, RFC1123);
        } catch (Exception ignore) {
            return null;
        }
    }
}