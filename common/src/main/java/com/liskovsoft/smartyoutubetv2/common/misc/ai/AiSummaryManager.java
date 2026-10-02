package com.liskovsoft.smartyoutubetv2.common.misc.ai;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.io.IOException;

import io.reactivex.Observable;

/**
 * Entry point for the on-demand AI video summaries feature.
 *
 * <p>Serves cached summaries instantly and only talks to the model on a cache miss (or when a
 * refresh is explicitly requested).
 */
public class AiSummaryManager {
    private static AiSummaryManager sInstance;
    private final Context mContext;

    private AiSummaryManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static AiSummaryManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new AiSummaryManager(context);
        }

        return sInstance;
    }

    public static class Result {
        public final String videoId;
        public final String text;
        public final boolean fromCache;

        public Result(String videoId, String text, boolean fromCache) {
            this.videoId = videoId;
            this.text = text;
            this.fromCache = fromCache;
        }
    }

    public Observable<Result> summarize(Video video) {
        return summarize(video, false);
    }

    /**
     * @param forceRefresh skip the cached value and request a new summary
     */
    public Observable<Result> summarize(Video video, boolean forceRefresh) {
        if (video == null || video.videoId == null) {
            return Observable.error(new IOException("Video is not available"));
        }

        final String videoId = video.videoId;

        return Observable.fromCallable(() -> loadSummary(videoId, forceRefresh));
    }

    public boolean isConfigured() {
        return AiSummaryData.instance(mContext).isConfigured();
    }

    private Result loadSummary(String videoId, boolean forceRefresh) throws IOException {
        AiSummaryData data = AiSummaryData.instance(mContext);
        AiSummaryStore store = AiSummaryStore.instance(mContext);

        if (!forceRefresh) {
            String cached = store.get(videoId);

            if (cached != null && !cached.isEmpty()) {
                return new Result(videoId, cached, true);
            }
        }

        if (!data.isConfigured()) {
            throw new IOException("AI summary API key is not set");
        }

        String text = new AiSummaryClient().summarize(videoId, data);
        store.put(videoId, text);

        return new Result(videoId, text, false);
    }
}
