package com.liskovsoft.smartyoutubetv2.common.misc.ai;

import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Small persistent cache of generated summaries keyed by YouTube video id.
 *
 * <p>Summaries are expensive (a full video inference), so the cache avoids paying for the same
 * video twice. Stored as a single JSON object and trimmed to {@link #MAX_ENTRIES} entries.
 */
public class AiSummaryStore extends SharedPreferencesBase {
    private static final String TAG = AiSummaryStore.class.getSimpleName();
    private static final String PREF_NAME = "ai_summary_cache";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;

    private static AiSummaryStore sInstance;

    private AiSummaryStore(Context context) {
        super(context, PREF_NAME);
    }

    public static AiSummaryStore instance(Context context) {
        if (sInstance == null) {
            sInstance = new AiSummaryStore(context);
        }

        return sInstance;
    }

    public String get(String videoId) {
        if (videoId == null) {
            return null;
        }

        JSONObject entries = readEntries();

        return entries != null ? entries.optString(videoId, null) : null;
    }

    public void put(String videoId, String summary) {
        if (videoId == null || summary == null || summary.isEmpty()) {
            return;
        }

        JSONObject entries = readEntries();

        if (entries == null) {
            entries = new JSONObject();
        }

        try {
            entries.put(videoId, summary);
        } catch (JSONException e) {
            Log.e(TAG, "Cannot store AI summary: %s", e.getMessage());
            return;
        }

        trim(entries);
        putString(KEY_ENTRIES, entries.toString());
    }

    public int getCount() {
        JSONObject entries = readEntries();

        return entries != null ? entries.length() : 0;
    }

    public void clear() {
        putString(KEY_ENTRIES, null);
    }

    private void trim(JSONObject entries) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = entries.keys();

        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }

        int excess = keys.size() - MAX_ENTRIES;

        for (int i = 0; i < excess; i++) {
            entries.remove(keys.get(i));
        }
    }

    private JSONObject readEntries() {
        String raw = getString(KEY_ENTRIES, null);

        if (raw == null || raw.isEmpty()) {
            return null;
        }

        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            Log.e(TAG, "Cannot read AI summary cache: %s", e.getMessage());
            return null;
        }
    }
}
