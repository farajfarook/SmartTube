package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.ai.AiSummaryData;
import com.liskovsoft.smartyoutubetv2.common.misc.ai.AiSummaryStore;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen for the on-demand AI video summaries feature.
 */
public class AiSummarySettingsPresenter extends BasePresenter<Void> {
    private static final int[] TIMEOUT_OPTIONS_SEC = {30, 60, 120, 300, 600};

    private final AiSummaryData mData;

    private AiSummarySettingsPresenter(Context context) {
        super(context);
        mData = AiSummaryData.instance(context);
    }

    public static AiSummarySettingsPresenter instance(Context context) {
        return new AiSummarySettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendEnableSwitch(settingsPresenter);
        appendApiKeyButton(settingsPresenter);
        appendBaseUrlButton(settingsPresenter);
        appendPathPrefixButton(settingsPresenter);
        appendModelButton(settingsPresenter);
        appendRequestFormatCategory(settingsPresenter);
        appendAuthStyleCategory(settingsPresenter);
        appendPromptButton(settingsPresenter);
        appendTimeoutCategory(settingsPresenter);
        appendCachedNoticeSwitch(settingsPresenter);
        appendRestoreDefaultsButton(settingsPresenter);
        appendCacheCategory(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.settings_ai_summary));
    }

    private void appendEnableSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_enable),
                getContext().getString(R.string.ai_summary_enable_desc),
                option -> mData.setEnabled(option.isSelected()),
                mData.isEnabled()
        ));
    }

    private void appendApiKeyButton(AppDialogPresenter settingsPresenter) {
        String apiKey = mData.getApiKey();
        boolean isSet = apiKey != null;

        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(isSet ? R.string.ai_summary_api_key_set : R.string.ai_summary_api_key_not_set),
                option -> SimpleEditDialog.showPassword(
                        getContext(),
                        getContext().getString(R.string.ai_summary_api_key),
                        isSet ? apiKey : "",
                        newValue -> {
                            mData.setApiKey(newValue);
                            return true;
                        })
        ));

        if (isSet) {
            settingsPresenter.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.ai_summary_clear_api_key),
                    option -> mData.setApiKey(null)
            ));
        }
    }

    private void appendBaseUrlButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                String.format("%s: %s", getContext().getString(R.string.ai_summary_base_url), mData.getBaseUrl()),
                option -> SimpleEditDialog.show(
                        getContext(),
                        getContext().getString(R.string.ai_summary_base_url),
                        getContext().getString(R.string.ai_summary_base_url_hint),
                        mData.getBaseUrl(),
                        newValue -> {
                            mData.setBaseUrl(newValue);
                            return true;
                        })
        ));
    }

    private void appendPathPrefixButton(AppDialogPresenter settingsPresenter) {
        String pathPrefix = mData.getPathPrefix();

        settingsPresenter.appendSingleButton(UiOptionItem.from(
                String.format("%s: %s", getContext().getString(R.string.ai_summary_path_prefix), pathPrefix != null ? pathPrefix : "-"),
                option -> SimpleEditDialog.show(
                        getContext(),
                        getContext().getString(R.string.ai_summary_path_prefix),
                        getContext().getString(R.string.ai_summary_path_prefix_hint),
                        pathPrefix != null ? pathPrefix : "",
                        newValue -> {
                            mData.setPathPrefix(newValue);
                            return true;
                        })
        ));
    }

    private void appendModelButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                String.format("%s: %s", getContext().getString(R.string.ai_summary_model), mData.getModel()),
                option -> SimpleEditDialog.show(
                        getContext(),
                        getContext().getString(R.string.ai_summary_model),
                        mData.getModel(),
                        newValue -> {
                            mData.setModel(newValue);
                            return true;
                        })
        ));
    }

    private void appendRequestFormatCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_request_format_gemini),
                option -> mData.setRequestFormat(AiSummaryData.REQUEST_FORMAT_GEMINI),
                mData.getRequestFormat() == AiSummaryData.REQUEST_FORMAT_GEMINI
        ));

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_request_format_openai),
                option -> mData.setRequestFormat(AiSummaryData.REQUEST_FORMAT_OPENAI),
                mData.getRequestFormat() == AiSummaryData.REQUEST_FORMAT_OPENAI
        ));

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ai_summary_request_format), options);
    }

    private void appendAuthStyleCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_auth_style_api_key),
                option -> mData.setAuthStyle(AiSummaryData.AUTH_STYLE_API_KEY),
                mData.getAuthStyle() == AiSummaryData.AUTH_STYLE_API_KEY
        ));

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_auth_style_bearer),
                option -> mData.setAuthStyle(AiSummaryData.AUTH_STYLE_BEARER),
                mData.getAuthStyle() == AiSummaryData.AUTH_STYLE_BEARER
        ));

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ai_summary_auth_style), options);
    }

    private void appendPromptButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_prompt),
                option -> SimpleEditDialog.show(
                        getContext(),
                        getContext().getString(R.string.ai_summary_prompt),
                        mData.getPrompt(),
                        newValue -> {
                            mData.setPrompt(newValue);
                            return true;
                        })
        ));
    }

    private void appendTimeoutCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int timeoutSec : TIMEOUT_OPTIONS_SEC) {
            options.add(UiOptionItem.from(
                    getContext().getString(R.string.ai_summary_seconds, timeoutSec),
                    option -> mData.setTimeoutSec(timeoutSec),
                    mData.getTimeoutSec() == timeoutSec
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ai_summary_timeout), options);
    }

    private void appendCachedNoticeSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_cached_notice),
                option -> mData.setCachedNoticeEnabled(option.isSelected()),
                mData.isCachedNoticeEnabled()
        ));
    }

    private void appendRestoreDefaultsButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_restore_defaults),
                option -> {
                    mData.setBaseUrl(null);
                    mData.setPathPrefix(null);
                    mData.setModel(null);
                    mData.setPrompt(null);
                    MessageHelpers.showMessage(getContext(), R.string.ai_summary_defaults_restored);
                }
        ));
    }

    private void appendCacheCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_cache_count, AiSummaryStore.instance(getContext()).getCount())
        ));

        options.add(UiOptionItem.from(
                getContext().getString(R.string.ai_summary_clear_cache),
                option -> {
                    AiSummaryStore.instance(getContext()).clear();
                    MessageHelpers.showMessage(getContext(), getContext().getString(R.string.ai_summary_cache_cleared));
                }
        ));

        settingsPresenter.appendStringsCategory(getContext().getString(R.string.ai_summary_cache), options);
    }
}
