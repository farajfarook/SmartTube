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
 * Client for on-demand YouTube video summaries.
 *
 * <p>Public YouTube videos are passed to the model by URL, so nothing has to be downloaded or
 * uploaded from the device.
 *
 * <p>Two request shapes are supported because they behave differently in practice:
 * <ul>
 *     <li>{@link AiSummaryData#REQUEST_FORMAT_GEMINI} - the native Gemini
 *     <code>generateContent</code> shape with a <code>fileData.fileUri</code> part. This is the
 *     documented path for <code>generativelanguage.googleapis.com</code>.</li>
 *     <li>{@link AiSummaryData#REQUEST_FORMAT_OPENAI} - an OpenAI-compatible
 *     <code>/v1/chat/completions</code> call carrying a <code>video_url</code> content part.
 *     Required for LiteLLM, whose Gemini-format route silently drops the video.</li>
 * </ul>
 *
 * <p>Both the native <code>x-goog-api-key</code> header and a plain
 * <code>Authorization: Bearer</code> header are supported for authentication.
 */
public class AiSummaryClient {
    private static final String TAG = AiSummaryClient.class.getSimpleName();
    private static final String GEMINI_API_VERSION = "v1beta";
    private static final String OPENAI_CHAT_PATH = "v1/chat/completions";
    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=%s";
    private static final int CONNECT_TIMEOUT_SEC = 20;
    private static final int WRITE_TIMEOUT_SEC = 30;
    private static final int MAX_ERROR_TEXT_LENGTH = 300;
    /**
     * Caps reasoning plus summary tokens on the OpenAI shape. Reasoning models can otherwise spend
     * the whole budget on reasoning and return empty content.
     */
    private static final int OPENAI_MAX_TOKENS = 4096;
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

        boolean isOpenAi = data.getRequestFormat() == AiSummaryData.REQUEST_FORMAT_OPENAI;
        String url = isOpenAi ? buildOpenAiUrl(data) : buildGeminiUrl(data);
        String requestBody = isOpenAi
                ? buildOpenAiRequestBody(videoId, data)
                : buildGeminiRequestBody(videoId, data.getPrompt());
        Request request = buildRequest(url, requestBody, data.getAuthStyle(), apiKey);

        Log.d(TAG, "Requesting AI summary for video %s (%s)", videoId, isOpenAi ? "openai" : "gemini");

        OkHttpClient client = createClient(data.getTimeoutSec());

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String rawBody = body != null ? body.string() : "";

            if (!response.isSuccessful()) {
                throw new IOException(String.format("AI summary request failed (HTTP %s): %s",
                        response.code(), redact(shorten(rawBody), apiKey)));
            }

            return isOpenAi ? parseOpenAiResponse(rawBody) : parseGeminiResponse(rawBody);
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

    // ---------------------------------------------------------------- request shapes

    private String buildGeminiRequestBody(String videoId, String prompt) throws IOException {
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

    private String buildOpenAiRequestBody(String videoId, AiSummaryData data) throws IOException {
        try {
            JSONObject videoUrl = new JSONObject();
            videoUrl.put("url", String.format(YOUTUBE_URL, videoId));

            JSONObject videoPart = new JSONObject();
            videoPart.put("type", "video_url");
            videoPart.put("video_url", videoUrl);

            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", data.getPrompt());

            JSONArray content = new JSONArray();
            content.put(textPart);
            content.put(videoPart);

            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", content);

            JSONArray messages = new JSONArray();
            messages.put(message);

            JSONObject root = new JSONObject();
            root.put("model", data.getModel());
            root.put("max_tokens", OPENAI_MAX_TOKENS);
            root.put("messages", messages);

            return root.toString();
        } catch (JSONException e) {
            throw new IOException("Cannot build AI summary request: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- response shapes

    private String parseGeminiResponse(String rawBody) throws IOException {
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

                appendPiece(text, piece);
            }

            if (text.length() == 0) {
                String finishReason = candidate != null ? candidate.optString("finishReason", null) : null;

                throw new IOException("AI summary response is empty"
                        + (finishReason != null ? " (finish reason: " + finishReason + ")" : ""));
            }

            verifyGeminiVideoIngested(root);

            return text.toString();
        } catch (JSONException e) {
            throw new IOException("Cannot parse AI summary response: " + e.getMessage(), e);
        }
    }

    private String parseOpenAiResponse(String rawBody) throws IOException {
        try {
            JSONObject root = new JSONObject(rawBody);
            JSONArray choices = root.optJSONArray("choices");

            if (choices == null || choices.length() == 0) {
                throw new IOException("AI summary response has no choices");
            }

            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice != null ? choice.optJSONObject("message") : null;
            StringBuilder text = new StringBuilder();

            if (message != null) {
                Object content = message.opt("content");

                if (content instanceof String) {
                    appendPiece(text, (String) content);
                } else if (content instanceof JSONArray) {
                    // Some providers return a list of parts instead of a plain string.
                    JSONArray parts = (JSONArray) content;

                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);

                        if (part != null) {
                            appendPiece(text, part.optString("text", null));
                        }
                    }
                }
            }

            if (text.length() == 0) {
                String finishReason = choice != null ? choice.optString("finish_reason", null) : null;

                throw new IOException("AI summary response is empty"
                        + (finishReason != null ? " (finish reason: " + finishReason + ")" : ""));
            }

            verifyOpenAiVideoIngested(root);

            return text.toString();
        } catch (JSONException e) {
            throw new IOException("Cannot parse AI summary response: " + e.getMessage(), e);
        }
    }

    private void appendPiece(StringBuilder text, String piece) {
        if (piece == null || piece.isEmpty()) {
            return;
        }

        if (text.length() > 0) {
            text.append("\n");
        }

        text.append(piece);
    }

    // ---------------------------------------------------------------- ingestion guards

    /**
     * Some endpoints accept the request but silently drop the video part, then answer from the text
     * prompt alone and produce a confidently wrong summary. Detect that using the reported prompt
     * token count and fail loudly instead of showing it.
     */
    private void verifyGeminiVideoIngested(JSONObject root) throws IOException {
        JSONObject usage = root.optJSONObject("usageMetadata");
        int promptTokens = usage != null ? usage.optInt("promptTokenCount", -1) : -1;

        if (promptTokens >= 0 && promptTokens < MIN_VIDEO_PROMPT_TOKENS) {
            throw new IOException(videoIgnoredMessage(promptTokens));
        }
    }

    /**
     * Prefers the explicit video token count when the provider reports it, and otherwise falls back
     * to the total prompt token count. Skipped entirely when nothing is reported, so a provider that
     * omits usage data is not falsely rejected.
     */
    private void verifyOpenAiVideoIngested(JSONObject root) throws IOException {
        JSONObject usage = root.optJSONObject("usage");

        if (usage == null) {
            return;
        }

        JSONObject details = usage.optJSONObject("prompt_tokens_details");

        if (details != null && details.has("video_tokens")) {
            int videoTokens = details.optInt("video_tokens", 0);

            if (videoTokens <= 0) {
                throw new IOException(videoIgnoredMessage(usage.optInt("prompt_tokens", -1)));
            }

            return;
        }

        int promptTokens = usage.optInt("prompt_tokens", -1);

        if (promptTokens >= 0 && promptTokens < MIN_VIDEO_PROMPT_TOKENS) {
            throw new IOException(videoIgnoredMessage(promptTokens));
        }
    }

    private String videoIgnoredMessage(int promptTokens) {
        String tokens = promptTokens >= 0 ? String.valueOf(promptTokens) : "unknown";

        return String.format(
                "The endpoint ignored the video and answered from the prompt alone (prompt tokens: %s). "
                        + "Use the native Gemini API, or switch the request format to OpenAI chat completions.",
                tokens);
    }

    // ---------------------------------------------------------------- url building

    private String buildGeminiUrl(AiSummaryData data) {
        String model = stripModelsPrefix(data.getModel());

        return String.format("%s/%s/models/%s:generateContent",
                buildBaseUrl(data), GEMINI_API_VERSION, model);
    }

    private String buildOpenAiUrl(AiSummaryData data) {
        return String.format("%s/%s", buildBaseUrl(data), OPENAI_CHAT_PATH);
    }

    private String buildBaseUrl(AiSummaryData data) {
        String base = trimTrailingSlashes(data.getBaseUrl());
        String pathPrefix = data.getPathPrefix();

        if (pathPrefix != null) {
            String normalizedPrefix = trimTrailingSlashes(pathPrefix);

            if (!normalizedPrefix.startsWith("/")) {
                normalizedPrefix = "/" + normalizedPrefix;
            }

            base = base + normalizedPrefix;
        }

        return base;
    }

    private String stripModelsPrefix(String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    // ---------------------------------------------------------------- helpers

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
