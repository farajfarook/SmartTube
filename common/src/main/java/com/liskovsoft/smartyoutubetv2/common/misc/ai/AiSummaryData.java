package com.liskovsoft.smartyoutubetv2.common.misc.ai;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataSaverBase;

/**
 * Persisted configuration for the on-demand AI video summaries feature.
 *
 * <p>NOTE: {@link DataSaverBase} stores values positionally, so never reorder or remove
 * the index constants below. Append new values at the end instead.
 */
public class AiSummaryData extends DataSaverBase {
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    /**
     * Matches the model used by the pi-web-access extension. Fully editable in the settings screen,
     * e.g. a LiteLLM proxy may expose a different alias such as gemini-3.8-flash.
     */
    public static final String DEFAULT_MODEL = "gemini-3.6-flash";
    public static final int AUTH_STYLE_API_KEY = 0;
    public static final int AUTH_STYLE_BEARER = 1;
    public static final int DEFAULT_TIMEOUT_SEC = 120;
    public static final String DEFAULT_PROMPT =
            "Summarize this YouTube video for a viewer who has not watched it yet. Include:\n" +
            "1. A short title of what the video is about\n" +
            "2. A concise summary (4-6 sentences)\n" +
            "3. Key points as a bullet list\n" +
            "4. Who would find it useful\n" +
            "Format the result as plain markdown. Don't include a transcript.";

    private static final int IDX_ENABLED = 0;
    private static final int IDX_API_KEY = 1;
    private static final int IDX_BASE_URL = 2;
    private static final int IDX_PATH_PREFIX = 3;
    private static final int IDX_MODEL = 4;
    private static final int IDX_AUTH_STYLE = 5;
    private static final int IDX_PROMPT = 6;
    private static final int IDX_TIMEOUT_SEC = 7;
    private static final int IDX_CACHED_NOTICE = 8;
    private static final int IDX_PLAYER_BUTTON = 9;

    private static AiSummaryData sInstance;

    private AiSummaryData(Context context) {
        super(context);
    }

    public static AiSummaryData instance(Context context) {
        if (sInstance == null) {
            sInstance = new AiSummaryData(context);
        }

        return sInstance;
    }

    public boolean isEnabled() {
        return getBoolean(IDX_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        setBoolean(IDX_ENABLED, enabled);
    }

    public String getApiKey() {
        return normalize(getString(IDX_API_KEY));
    }

    public void setApiKey(String apiKey) {
        setString(IDX_API_KEY, normalize(apiKey));
    }

    public String getBaseUrl() {
        return orDefault(getString(IDX_BASE_URL), DEFAULT_BASE_URL);
    }

    public void setBaseUrl(String baseUrl) {
        setString(IDX_BASE_URL, normalize(baseUrl));
    }

    public String getPathPrefix() {
        return normalize(getString(IDX_PATH_PREFIX));
    }

    public void setPathPrefix(String pathPrefix) {
        setString(IDX_PATH_PREFIX, normalize(pathPrefix));
    }

    public String getModel() {
        return orDefault(getString(IDX_MODEL), DEFAULT_MODEL);
    }

    public void setModel(String model) {
        setString(IDX_MODEL, normalize(model));
    }

    public int getAuthStyle() {
        return getInt(IDX_AUTH_STYLE, AUTH_STYLE_API_KEY);
    }

    public void setAuthStyle(int authStyle) {
        setInt(IDX_AUTH_STYLE, authStyle);
    }

    public String getPrompt() {
        return orDefault(getString(IDX_PROMPT), DEFAULT_PROMPT);
    }

    public void setPrompt(String prompt) {
        setString(IDX_PROMPT, normalize(prompt));
    }

    public int getTimeoutSec() {
        int timeoutSec = getInt(IDX_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC);
        return timeoutSec > 0 ? timeoutSec : DEFAULT_TIMEOUT_SEC;
    }

    public void setTimeoutSec(int timeoutSec) {
        setInt(IDX_TIMEOUT_SEC, timeoutSec);
    }

    public boolean isCachedNoticeEnabled() {
        return getBoolean(IDX_CACHED_NOTICE, true);
    }

    public void setCachedNoticeEnabled(boolean enabled) {
        setBoolean(IDX_CACHED_NOTICE, enabled);
    }

    public boolean isPlayerButtonEnabled() {
        return getBoolean(IDX_PLAYER_BUTTON, false);
    }

    public void setPlayerButtonEnabled(boolean enabled) {
        setBoolean(IDX_PLAYER_BUTTON, enabled);
    }

    /**
     * A usable API key is required. The remaining values always have safe defaults.
     */
    public boolean isConfigured() {
        return getApiKey() != null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String orDefault(String value, String defaultValue) {
        String normalized = normalize(value);

        return normalized != null ? normalized : defaultValue;
    }
}
