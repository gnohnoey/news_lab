package com.example.neutral_news.controller.news;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/naver")
public class NaverNewsController {

    @Value("${naver.client-id}")
    private String clientId;

    @Value("${naver.client-secret}")
    private String clientSecret;

    @Value("${naver.news-url}") // e.g. https://openapi.naver.com/v1/search/news.json
    private String newsUrl;

    @GetMapping("/news")
    public ResponseEntity<String> searchNews(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int display,
            @RequestParam(defaultValue = "1") int start,
            @RequestParam(defaultValue = "sim") String sort
    ) {
        try {
            display = Math.max(1, Math.min(display, 100));   // 네이버 가이드
            start   = Math.max(1, Math.min(start, 1000));
            if (!sort.equals("sim") && !sort.equals("date")) sort = "sim";

            String q = URLEncoder.encode(query, "UTF-8");
            String apiURL = String.format("%s?query=%s&display=%d&start=%d&sort=%s",
                    newsUrl, q, display, start, sort);

            Map<String, String> headers = new HashMap<>();
            headers.put("X-Naver-Client-Id", clientId);
            headers.put("X-Naver-Client-Secret", clientSecret);

            String responseBody = get(apiURL, headers);
            return ResponseEntity.ok(responseBody);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("API 요청 실패: " + e.getMessage());
        }
    }

    private String get(String apiUrl, Map<String, String> headers) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(apiUrl).openConnection();
        con.setRequestMethod("GET");
        headers.forEach(con::setRequestProperty);

        int code = con.getResponseCode();
        InputStream in = (code == HttpURLConnection.HTTP_OK) ? con.getInputStream() : con.getErrorStream();
        return readBody(in);
    }

    private String readBody(InputStream body) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(body))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}