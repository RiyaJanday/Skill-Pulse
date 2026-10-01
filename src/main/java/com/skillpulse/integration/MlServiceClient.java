package com.skillpulse.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * HTTP client for the Python ML service (ml-service/). It contains no ML logic: every method
 * returns null when the service is disabled, unreachable or answers with an error, and callers
 * then use their own plain (non-ML) safety net so the app keeps working.
 */
@Component
public class MlServiceClient {
    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);
    private static final long COOLDOWN_MS = 30000L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() { };

    private final RestTemplate fast;
    private final RestTemplate slow;
    private final boolean enabled;
    private final String baseUrl;
    private final String key;
    private final int streamReadTimeoutMs;
    private volatile long downUntil = 0L;

    public MlServiceClient(RestTemplateBuilder builder,
                           @Value("${skillpulse.ml.enabled:true}") boolean enabled,
                           @Value("${skillpulse.ml.base-url:http://localhost:8001}") String baseUrl,
                           @Value("${skillpulse.ml.key:}") String key,
                           @Value("${skillpulse.ml.timeout-ms:3000}") long timeoutMs,
                           @Value("${skillpulse.ml.plan-timeout-seconds:60}") long planTimeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key == null ? "" : key.trim();
        this.streamReadTimeoutMs = (int) Math.max(5L, planTimeoutSeconds) * 1000;
        this.fast = builder.setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofMillis(timeoutMs)).build();
        this.slow = builder.setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(planTimeoutSeconds)).build();
    }

    // ---- value types -------------------------------------------------------------------------
    public static final class ReplayAttempt {
        public final boolean correct;
        public final String difficulty;
        public final int seconds;
        public final int daysSinceLast;

        public ReplayAttempt(boolean correct, String difficulty, int seconds, int daysSinceLast) {
            this.correct = correct;
            this.difficulty = difficulty;
            this.seconds = seconds;
            this.daysSinceLast = daysSinceLast;
        }
    }

    public static final class ReviewResult {
        public final int repetitions;
        public final int intervalDays;
        public final double easeFactor;
        public final int quality;

        public ReviewResult(int repetitions, int intervalDays, double easeFactor, int quality) {
            this.repetitions = repetitions;
            this.intervalDays = intervalDays;
            this.easeFactor = easeFactor;
            this.quality = quality;
        }
    }

    public static final class DecayInput {
        public final long key;
        public final double mastery;
        public final int daysSinceLast;
        public final double threshold;

        public DecayInput(long key, double mastery, int daysSinceLast, double threshold) {
            this.key = key;
            this.mastery = mastery;
            this.daysSinceLast = daysSinceLast;
            this.threshold = threshold;
        }
    }

    // ---- API ---------------------------------------------------------------------------------
    /** One Bayesian Knowledge Tracing step. Returns the new mastery, or null if unavailable. */
    public Double bktUpdate(double mastery, boolean correct, String difficulty, int seconds, int daysSinceLast,
                            double pInit, double pLearn, double pGuess, double pSlip) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("pInit", pInit);
        params.put("pLearn", pLearn);
        params.put("pGuess", pGuess);
        params.put("pSlip", pSlip);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("mastery", mastery);
        body.put("correct", correct);
        body.put("difficulty", difficulty);
        body.put("seconds", seconds);
        body.put("daysSinceLast", daysSinceLast);
        body.put("params", params);
        Map<String, Object> result = post(fast, "/bkt/update", body, true);
        return result == null ? null : number(result.get("mastery"));
    }

    /** Seeds mastery for topics that already have answer history. Keys are topic ids. */
    public Map<Long, Double> bktReplay(Map<Long, List<ReplayAttempt>> attemptsByTopic) {
        List<Object> items = new ArrayList<Object>();
        for (Map.Entry<Long, List<ReplayAttempt>> entry : attemptsByTopic.entrySet()) {
            List<Object> attempts = new ArrayList<Object>();
            for (ReplayAttempt a : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("correct", a.correct);
                row.put("difficulty", a.difficulty);
                row.put("seconds", a.seconds);
                row.put("daysSinceLast", a.daysSinceLast);
                attempts.add(row);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", String.valueOf(entry.getKey()));
            item.put("attempts", attempts);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        Map<String, Object> result = post(fast, "/bkt/replay", body, true);
        if (result == null || !(result.get("results") instanceof List)) return null;

        Map<Long, Double> out = new LinkedHashMap<Long, Double>();
        for (Object o : (List<?>) result.get("results")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) o;
            Double mastery = number(row.get("mastery"));
            if (row.get("key") != null && mastery != null) {
                out.put(Long.valueOf(String.valueOf(row.get("key"))), mastery);
            }
        }
        return out;
    }

    /** Days until each topic's mastery (after forgetting) drops below its threshold. */
    public Map<Long, Integer> daysToThreshold(List<DecayInput> inputs) {
        if (inputs.isEmpty()) return new LinkedHashMap<Long, Integer>();
        List<Object> items = new ArrayList<Object>();
        for (DecayInput in : inputs) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", String.valueOf(in.key));
            item.put("mastery", in.mastery);
            item.put("daysSinceLast", in.daysSinceLast);
            item.put("threshold", in.threshold);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        Map<String, Object> result = post(fast, "/bkt/decay", body, true);
        if (result == null || !(result.get("results") instanceof List)) return null;

        Map<Long, Integer> out = new LinkedHashMap<Long, Integer>();
        for (Object o : (List<?>) result.get("results")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) o;
            if (row.get("key") != null && row.get("daysToThreshold") instanceof Number) {
                out.put(Long.valueOf(String.valueOf(row.get("key"))), ((Number) row.get("daysToThreshold")).intValue());
            }
        }
        return out;
    }

    /** Mastery after forgetting (decayed for the idle days), per topic. Returns null if unavailable. */
    public Map<Long, Double> effectiveMasteries(List<DecayInput> inputs) {
        if (inputs.isEmpty()) return new LinkedHashMap<Long, Double>();
        List<Object> items = new ArrayList<Object>();
        for (DecayInput in : inputs) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", String.valueOf(in.key));
            item.put("mastery", in.mastery);
            item.put("daysSinceLast", in.daysSinceLast);
            item.put("threshold", in.threshold);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        Map<String, Object> result = post(fast, "/bkt/decay", body, true);
        if (result == null || !(result.get("results") instanceof List)) return null;

        Map<Long, Double> out = new LinkedHashMap<Long, Double>();
        for (Object o : (List<?>) result.get("results")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) o;
            Double effective = number(row.get("effectiveMastery"));
            if (row.get("key") != null && effective != null) {
                out.put(Long.valueOf(String.valueOf(row.get("key"))), effective);
            }
        }
        return out;
    }

    /** SM-2 spaced-repetition step. Returns null if unavailable. */
    public ReviewResult reviewSchedule(int repetitions, int intervalDays, double easeFactor,
                                       boolean correct, int seconds) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("repetitions", repetitions);
        body.put("intervalDays", intervalDays);
        body.put("easeFactor", easeFactor);
        body.put("correct", correct);
        body.put("seconds", seconds);
        Map<String, Object> r = post(fast, "/review/schedule", body, true);
        if (r == null || number(r.get("easeFactor")) == null || !(r.get("intervalDays") instanceof Number)) return null;
        return new ReviewResult(integer(r.get("repetitions")), integer(r.get("intervalDays")),
                number(r.get("easeFactor")), integer(r.get("quality")));
    }

    /**
     * SM-2 state for many questions at once, replayed from their stored attempts (oldest first). Keys are question
     * ids. Returns null if unavailable, in which case the caller uses its fixed interval ladder.
     */
    public Map<Long, ReviewResult> reviewReplay(Map<Long, List<ReplayAttempt>> attemptsByQuestion) {
        if (attemptsByQuestion.isEmpty()) return new LinkedHashMap<Long, ReviewResult>();
        List<Object> items = new ArrayList<Object>();
        for (Map.Entry<Long, List<ReplayAttempt>> entry : attemptsByQuestion.entrySet()) {
            List<Object> attempts = new ArrayList<Object>();
            for (ReplayAttempt a : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("correct", a.correct);
                row.put("seconds", a.seconds);
                attempts.add(row);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", String.valueOf(entry.getKey()));
            item.put("attempts", attempts);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        Map<String, Object> result = post(fast, "/review/replay", body, true);
        if (result == null || !(result.get("results") instanceof List)) return null;

        Map<Long, ReviewResult> out = new LinkedHashMap<Long, ReviewResult>();
        for (Object o : (List<?>) result.get("results")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) o;
            if (row.get("key") == null || number(row.get("easeFactor")) == null
                    || !(row.get("intervalDays") instanceof Number)) continue;
            out.put(Long.valueOf(String.valueOf(row.get("key"))), new ReviewResult(integer(row.get("repetitions")),
                    integer(row.get("intervalDays")), number(row.get("easeFactor")), integer(row.get("quality"))));
        }
        return out;
    }

    /** LLM-generated 7-day plan (same JSON shape as PersonalizationDtos.PlanResponse), or null. */
    public Map<String, Object> generatePlan(Map<String, Object> request) {
        return post(slow, "/plan/generate", request, false);
    }

    /** Skill decay analysis; forwards the request unchanged. */
    public Map<String, Object> analyze(Map<String, Object> request) {
        return post(fast, "/analyze", request, true);
    }

    /** Quick AI call (one learner's risk, adaptive question order). Trips the outage breaker like the BKT calls. */
    public Map<String, Object> callFast(String path, Object body) {
        return post(fast, path, body, true);
    }

    /**
     * Heavier AI calls (cohort analytics, LLM coach). Uses the long timeout and never trips the outage breaker, so a
     * slow or failing AI feature can not switch off mastery tracking for everybody.
     */
    public Map<String, Object> callSlow(String path, Object body) {
        return post(slow, path, body, false);
    }

    /**
     * Streams the study chat from the ML service (POST /chat/stream, Server-Sent Events). RestTemplate buffers whole
     * responses, so this uses HttpURLConnection and reads the events line by line.
     *
     * onEvent gets each event (type meta, delta, done or error) and returns false to stop early; stopping closes the
     * connection, which makes the ML service stop asking the LLM. Blocking: call it from a worker thread.
     * Returns false when the service could not be reached or refused the request, true otherwise. Like callSlow, it
     * never trips the outage breaker, so a failing chat can not switch off mastery tracking.
     */
    public boolean streamChat(Object body, Predicate<Map<String, Object>> onEvent) {
        if (!enabled || System.currentTimeMillis() < downUntil) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/chat/stream").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(streamReadTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (!key.isEmpty()) conn.setRequestProperty("X-Internal-Key", key);
            byte[] payload = JSON.writeValueAsBytes(body);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) {
                trip("rejected our credentials (HTTP " + status + "); check that ML_SERVICE_KEY matches on both sides");
                return false;
            }
            if (status != 200) {
                log.warn("ML service call /chat/stream answered HTTP {}", status);
                return false;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    Map<String, Object> event;
                    try {
                        event = JSON.readValue(line.substring(5).trim(), MAP_TYPE);
                    } catch (IOException bad) {
                        log.warn("Ignoring an unreadable chat event: {}", bad.getMessage());
                        continue;
                    }
                    if (!onEvent.test(event)) return true;
                }
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("ML service call /chat/stream failed: {}", ex.toString());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** GET helper for read-only ML endpoints such as /risk/info. Returns null when unavailable. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String path) {
        if (!enabled || System.currentTimeMillis() < downUntil) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            if (!key.isEmpty()) headers.set("X-Internal-Key", key);
            return (Map<String, Object>) fast.exchange(baseUrl + path, HttpMethod.GET,
                    new HttpEntity<Void>(headers), Map.class).getBody();
        } catch (RuntimeException ex) {
            log.warn("ML service GET {} failed: {}", path, ex.toString());
            return null;
        }
    }

    public boolean isUp() {
        if (!enabled) return false;
        try {
            Map<?, ?> r = fast.getForObject(baseUrl + "/health", Map.class);
            boolean up = r != null && Boolean.TRUE.equals(r.get("ready"));
            if (up) downUntil = 0L;
            return up;
        } catch (RestClientException ex) {
            return false;
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> post(RestTemplate client, String path, Object body, boolean tripOnOutage) {
        if (!enabled || System.currentTimeMillis() < downUntil) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!key.isEmpty()) headers.set("X-Internal-Key", key);
            return (Map<String, Object>) client.exchange(baseUrl + path, HttpMethod.POST,
                    new HttpEntity<Object>(body, headers), Map.class).getBody();
        } catch (ResourceAccessException ex) {
            if (tripOnOutage) {
                trip("unreachable (" + ex.getMessage() + ")");
            } else {
                log.warn("ML service call {} timed out or was refused: {}", path, ex.getMessage());
            }
            return null;
        } catch (HttpStatusCodeException ex) {
            int status = ex.getRawStatusCode();
            if (status == 401 || status == 403) {
                // The key is wrong on one side; every call would fail the same way, so stop hammering it.
                trip("rejected our credentials (HTTP " + status + "); check that ML_SERVICE_KEY matches on both sides");
            } else if (status >= 500 && tripOnOutage) {
                trip("failing with HTTP " + status);
            } else {
                // e.g. 503 from /plan/generate means every LLM provider failed; that is not an outage of the fast endpoints.
                log.warn("ML service call {} answered HTTP {}", path, status);
            }
            return null;
        } catch (RestClientException ex) {
            log.warn("ML service call {} failed: {}", path, ex.getMessage());
            return null;
        } catch (RuntimeException ex) {
            // Whatever goes wrong here, ML must never break the caller: they all have non-ML fallbacks.
            log.warn("ML service call {} failed unexpectedly: {}", path, ex.toString());
            return null;
        }
    }

    private void trip(String reason) {
        downUntil = System.currentTimeMillis() + COOLDOWN_MS;
        log.warn("ML service {}; using built-in safety nets for {}s.", reason, COOLDOWN_MS / 1000);
    }

    private static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    private static int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
