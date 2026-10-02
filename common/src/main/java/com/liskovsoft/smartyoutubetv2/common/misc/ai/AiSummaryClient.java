package com.liskovsoft.smartyoutubetv2.common.misc.ai;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Minimal Gemini <code>generateContent</code> client for YouTube video summarization.
 *
 * <p>Public YouTube videos are passed to the model by URL
 * (<code>fileData.fileUri</code>), so nothing has to be downloaded or uploaded from the TV.
 *
 * <p>Two authentication styles are supported so a self-hosted/proxy endpoint can be used:
 * the native <code>x-goog-api-key</code> header, and a plain
 * <code>Authorization: Bearer</code> header.
 */
public class AiSummaryClient {
    private static final String TAG = AiSummaryClient.class.getSimpleName();
    private static final String API_VERSION = "v1beta";
    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=%s";
    private static final int CONNECT_TIMEOUT_SEC = 20;
    private static final int WRITE_TIMEOUT_SEC = 30;
    private static final int MAX_ERROR_TEXT_LENGTH = 300;
    /**
     * A real video prompt costs hundreds/thousands of tokens. Anything below this means the video
     * was not attached.
     */
    private static final int MIN_VIDEO_PROMPT_TOKENS = 100;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * Shared so repeated summaries reuse the same connection pool and dispatcher instead of
     * creating a new one per request. Per-request timeouts derive from it via {@code newBuilder()}.
     */
    private static volatile OkHttpClient sClient;

    /**
     * @throws IOException when the request is not configured, fails, or returns an unusable payload
     */
    public String summarize(String videoId, AiSummaryData data) throws IOException {
        String apiKey = data.getApiKey();

        if (apiKey == null) {
            throw new IOException("AI summary API key is not set");
        }

        String url = buildUrl(data);
        String requestBody = buildRequestBody(videoId, data.getPrompt());
        Request request = buildRequest(url, requestBody, data.getAuthStyle(), apiKey);

        Log.d(TAG, "Requesting AI summary for video %s", videoId);

        OkHttpClient client = createClient(data.getTimeoutSec());

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String rawBody = body != null ? body.string() : "";

            if (!response.isSuccessful()) {
                throw new IOException(String.format("AI summary request failed (HTTP %s): %s",
                        response.code(), redact(shorten(rawBody), apiKey)));
            }

            return parseResponse(rawBody);
        }
    }

    private OkHttpClient createClient(int timeoutSec) {
        return baseClient().newBuilder()
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
    }

    private static OkHttpClient baseClient() {
        if (sClient == null) {
            synchronized (AiSummaryClient.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .readTimeout(AiSummaryData.DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .build();
                }
            }
        }

        return sClient;
    }

    private Request buildRequest(String url, String body, int authStyle, String apiKey) throws IOException {
        Request.Builder builder;

        try {
            builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid AI summary URL: " + url, e);
        }

        if (authStyle == AiSummaryData.AUTH_STYLE_BEARER) {
            builder.header("Authorization", "Bearer " + apiKey);
        } else {
            builder.header("x-goog-api-key", apiKey);
        }

        return builder.build();
    }

    private String buildRequestBody(String videoId, String prompt) throws IOException {
        try {
            JSONObject fileData = new JSONObject();
            fileData.put("fileUri", String.format(YOUTUBE_URL, videoId));

            JSONObject videoPart = new JSONObject();
            videoPart.put("fileData", fileData);

            JSONObject textPart = new JSONObject();
            textPart.put("text", prompt);

            JSONArray parts = new JSONArray();
            parts.put(videoPart);
            parts.put(textPart);

            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", parts);

            JSONArray contents = new JSONArray();
            contents.put(content);

            JSONObject root = new JSONObject();
            root.put("contents", contents);

            return root.toString();
        } catch (JSONException e) {
            throw new IOException("Cannot build AI summary request: " + e.getMessage(), e);
        }
    }

    private String parseResponse(String rawBody) throws IOException {
        try {
            JSONObject root = new JSONObject(rawBody);

            JSONObject promptFeedback = root.optJSONObject("promptFeedback");
            String blockReason = promptFeedback != null ? promptFeedback.optString("blockReason", null) : null;

            if (blockReason != null && !blockReason.isEmpty()) {
                throw new IOException("AI summary was blocked by the model: " + blockReason);
            }

            JSONArray candidates = root.optJSONArray("candidates");

            if (candidates == null || candidates.length() == 0) {
                throw new IOException("AI summary response has no candidates");
            }

            JSONObject candidate = candidates.optJSONObject(0);
            JSONObject content = candidate != null ? candidate.optJSONObject("content") : null;
            JSONArray parts = content != null ? content.optJSONArray("parts") : null;

            if (parts == null) {
                throw new IOException("AI summary response is empty");
            }

            StringBuilder text = new StringBuilder();

            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                String piece = part != null ? part.optString("text", null) : null;

                if (piece != null && !piece.isEmpty()) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }

                    text.append(piece);
                }
            }

            if (text.length() == 0) {
                String finishReason = candidate != null ? candidate.optString("finishReason", null) : null;

                throw new IOException("AI summary response is empty"
                        + (finishReason != null ? " (finish reason: " + finishReason + ")" : ""));
            }

            verifyVideoIngested(root);

            return text.toString();
        } catch (JSONException e) {
            throw new IOException("Cannot parse AI summary response: " + e.getMessage(), e);
        }
    }

    /**
     * Some endpoints (e.g. LiteLLM's Gemini-format route) accept the request but silently drop the
     * video part, then answer from the text prompt alone and produce a confidently wrong summary.
     * Detect that using the reported prompt token count and fail loudly instead of showing it.
     */
    private void verifyVideoIngested(JSONObject root) throws IOException {
        JSONObject usage = root.optJSONObject("usageMetadata");
        int promptTokens = usage != null ? usage.optInt("promptTokenCount", -1) : -1;

        if (promptTokens >= 0 && promptTokens < MIN_VIDEO_PROMPT_TOKENS) {
            throw new IOException(String.format(
                    "The endpoint ignored the video and answered from the prompt alone (only %d prompt tokens). "
                            + "Use the native Gemini API, or a proxy path that forwards the video.",
                    promptTokens));
        }
    }

    private String buildUrl(AiSummaryData data) {
        String base = trimTrailingSlashes(data.getBaseUrl());
        String pathPrefix = data.getPathPrefix();

        if (pathPrefix != null) {
            String normalizedPrefix = trimTrailingSlashes(pathPrefix);

            if (!normalizedPrefix.startsWith("/")) {
                normalizedPrefix = "/" + normalizedPrefix;
            }

            base = base + normalizedPrefix;
        }

        String model = data.getModel();

        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }

        return String.format("%s/%s/models/%s:generateContent", base, API_VERSION, model);
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }

        String singleLine = text.replaceAll("\\s+", " ").trim();

        return singleLine.length() > MAX_ERROR_TEXT_LENGTH
                ? singleLine.substring(0, MAX_ERROR_TEXT_LENGTH) + "…"
                : singleLine;
    }

    private String redact(String text, String apiKey) {
        if (text == null) {
            return "";
        }

        return apiKey != null && !apiKey.isEmpty() ? text.replace(apiKey, "***") : text;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value;

        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }
}
