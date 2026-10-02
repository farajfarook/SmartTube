# On-demand AI video summaries (Gemini) — implementation plan

Status: planning
Owner: TBD
Target: `smarttubetv` + `common` modules

## Decisions locked

| Topic | Decision |
|---|---|
| Trigger | Long-press card menu only (video `VideoMenuPresenter`) |
| Video input | Gemini **native YouTube URL** (`fileData.fileUri`) |
| Providers | Native Gemini (`x-goog-api-key`) **and** Bearer-token proxies with configurable base URL / path prefix |
| First deliverable | This plan, then phase 1 implementation |

Non-goals for this feature: transcript-only mode, OpenAI-compatible `chat/completions`, player toolbar button, auto-summary on `onPlayEnd()`. These are documented as future phases.

---

## 1. Prerequisite (blocker)

`git submodule status` reports `SharedModules` and `MediaServiceCore` as not checked out (both directories are empty), so the project does not compile as cloned.

```bash
git submodule update --init --recursive
```

Do this before any code work or verification.

---

## 2. Reference: how `pi-web-access` does it

Files: `gemini-api.ts`, `youtube-extract.ts`, `video-extract.ts`, `gemini-web-config.ts`.

### 2.1 Base URL resolution

Precedence (highest first):

1. `GOOGLE_GEMINI_BASE_URL` environment variable
2. `geminiBaseUrl` key in the extension config file
3. `https://generativelanguage.googleapis.com`

Normalization: trim whitespace, strip trailing `/`. Versioned base is `{host}/v1beta`. Upload base is `{host}/upload/v1beta`.

### 2.2 Authentication

- Default header: `x-goog-api-key: <key>`
- Cloudflare AI Gateway special case: when the host contains `gateway.ai.cloudflare.com`, use `cf-aig-authorization: Bearer <cloudflareApiKey>`
- Key source: `GEMINI_API_KEY` env var, else `geminiApiKey` config value
- Credential query parameters (`?key=`, `?api_key=`) are explicitly rejected
- Requests are validated against a host allowlist
- Credentials are redacted from thrown error messages

### 2.3 The important trick — YouTube URL straight to Gemini

`youtube-extract.ts` canonicalizes the URL to `https://www.youtube.com/watch?v=<videoId>` and passes it directly as `fileData.fileUri`. No download, upload, or transcript scraping. Gemini ingests the public YouTube video itself (frames + audio + captions).

Request shape:

```http
POST {base}/v1beta/models/{model}:generateContent
x-goog-api-key: <key>
Content-Type: application/json
```

```json
{
  "contents": [
    {
      "role": "user",
      "parts": [
        { "fileData": { "fileUri": "https://www.youtube.com/watch?v=<videoId>" } },
        { "text": "<prompt>" }
      ]
    }
  ]
}
```

Response text is `candidates[0].content.parts[].text`, joined with newlines. Default model in pi-web-access is `gemini-3.6-flash`.

### 2.4 Prompt used for extraction

Asks for: title/duration, a 2–3 sentence summary, a transcript with timestamps, and descriptions of on-screen code, commands, diagrams, slides, and UI.

### 2.5 Not needed here

The Files API pipeline (resumable upload → poll `state == ACTIVE` → `generateContent` → `DELETE`) is only used for local video files. SmartTube always has a YouTube video ID, so the native URL path is sufficient.

---

## 3. SmartTube extension points

| Concern | Location |
|---|---|
| Video context menu | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/dialogs/menu/VideoMenuPresenter.java` |
| Menu item ids + order | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/MainUIData.java` (`MENU_ITEM_*`, `MENU_ITEM_DEFAULT`, `MENU_ITEM_DEFAULT_ORDER`) |
| Settings root list | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/AppDataSourceManager.java` (`getSettingItems()`) |
| Settings screen pattern | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/SponsorBlockSettingsPresenter.java` |
| Persisted config pattern | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/*.java` extending `common/.../prefs/common/DataSaverBase.java` |
| HTTP client | `com.liskovsoft.sharedutils.okhttp.OkHttpManager`; usage precedent in `common/.../utils/Utils.java::testUrl` |
| Per-video external data (closest precedent) | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/DeArrowProcessor.java` |
| Long-text display | `AppDialogPresenter.appendLongTextCategory(CharSequence, OptionItem)` — used by `VideoMenuPresenter.showLongTextDialog` for the description dialog |
| Secret / text entry dialog | `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/utils/SimpleEditDialog.java` (`showPassword`) |
| Strings | `common/src/main/res/values/strings.xml` (Crowdin-translated) |
| Settings icon | `common/src/main/res/drawable-nodpi/` |

---

## 4. Proposed design

### 4.1 New package

`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/ai/`

| Class | Responsibility |
|---|---|
| `AiSummaryData` | `extends DataSaverBase`. Persisted config: `enabled`, `apiKey`, `baseUrl`, `pathPrefix`, `model`, `authStyle`, `timeoutSec`, `prompt`, `language`, `showCachedNotice`. |
| `AiSummaryClient` | Pure networking, no UI. Base URL normalization, request building, header construction per auth style, response parsing, credential redaction. |
| `AiSummaryStore` | Per-`videoId` cache of generated summaries (LRU, capped) so repeat opens are instant and free. |
| `AiSummaryManager` | Orchestration, exposes RxJava `Observable<String>` matching the codebase style: cache lookup → client call → store → emit. |
| `AiSummarySettingsPresenter` | Settings screen (see 4.4). |

### 4.2 Trigger: long-press card menu

1. Add to `MainUIData`:
   ```java
   public static final long MENU_ITEM_AI_SUMMARY = MENU_ITEM_ADD_TO_WATCH_LATER << 1;
   ```
   Add it to `MENU_ITEM_DEFAULT_ORDER` (placement decides its default position in the menu).
2. In `VideoMenuPresenter`:
   - field `mIsAiSummaryButtonEnabled`
   - `appendAiSummaryButton()` — mirrors `appendOpenDescriptionButton()`:
     - guard on `mVideo == null || mVideo.videoId == null`
     - show "wait" message
     - call `AiSummaryManager`, then render via `showLongTextDialog(...)` / `appendLongTextCategory(...)`
     - on error show a long message with the redacted reason
   - register in `mMenuMapping`
   - enable in `updateEnabledMenuItems()` from `AiSummaryData.isEnabled()`
3. Disable for live/upcoming/shorts if desired (Shorts has its own menu path — confirm during implementation).

### 4.3 Request construction

```
POST {baseUrl}{pathPrefix}/v1beta/models/{model}:generateContent
```

Auth styles:

| Style | Headers |
|---|---|
| `x-goog-api-key` (default) | `x-goog-api-key: <apiKey>` |
| `bearer` (proxies) | `Authorization: Bearer <apiKey>` |

Body is the Gemini-native shape from section 2.3, with `fileUri = https://www.youtube.com/watch?v=<videoId>`.

> Caveat: Bearer-token proxies only work if the endpoint accepts the Gemini `:generateContent` schema **and** supports YouTube `fileUri` ingestion. A plain OpenAI-compatible `/v1/chat/completions` endpoint will not. Base URL and path prefix are configurable so a LiteLLM Gemini passthrough can be used if enabled.

### 4.4 Settings screen

New `AiSummarySettingsPresenter`, registered in `AppDataSourceManager.getSettingItems()` with `R.string.settings_ai_summary` and a new drawable.

- Enable/disable switch
- API key — `SimpleEditDialog.showPassword`
- Base URL — default `https://generativelanguage.googleapis.com`
- Path prefix — optional, for proxy deployments
- Auth style — radio: `x-goog-api-key` / `Authorization: Bearer`
- Model — text, default `gemini-2.5-flash` (revisit once verified against the target proxy)
- Prompt — long text override
- Timeout seconds
- Clear summary cache

### 4.5 UX flow

1. Long-press a video card.
2. Choose **AI Summary**.
3. Show a loading/wait indication.
4. On success: long-text dialog with the summary (same surface as the description dialog).
5. On failure: long message including the redacted HTTP status/reason.
6. Cached summaries render instantly; optionally show a "cached" hint plus a refresh action.

---

## 5. Risks and open questions
- **Template preview**: YouTube-URL ingestion in the Gemini API is a preview capability with regional/model limits; age-restricted, private, and members-only videos will fail. Errors must surface cleanly.
- **Cost**: each uncached summary is a full video inference. The cache is not optional.
- **Secret storage**: API keys go into plain `SharedPreferences` (same as the existing web-proxy password). Not encrypted.
- **TV text entry**: entering a long API key with a remote is awkward. Existing precedent (`WebProxyDialog`) is the only mitigation.
- **Model availability**: model ids change; the settings field must stay freely editable and default sensibly.
- **Localization**: new strings need Crowdin coverage; follow existing `strings.xml` conventions.
- **Shorts**: confirm whether the short-card menu routes through `VideoMenuPresenter` or a separate path.

## 6. Phases
1. Submodule init; `AiSummaryData`; settings screen (key, base URL, model, auth). No feature yet.
2. `AiSummaryClient` + `AiSummaryStore` + `AiSummaryManager`; wire the long-press menu item. Verify end-to-end against the configured endpoint.
3. Cache management UI, error messaging polish, localization.
4. Deferred: transcript-only fallback; OpenAI-compatible mode; player toolbar button; auto-summary on `onPlayEnd()`.

---

## 7. Implementation status

Implemented (long-press menu trigger, Gemini native YouTube URL, native + Bearer auth):

| File | Purpose |
|---|---|
| `common/.../common/misc/ai/AiSummaryData.java` | Persisted config (enabled, API key, base URL, path prefix, model, auth style, prompt, timeout, cached notice) |
| `common/.../common/misc/ai/AiSummaryClient.java` | Gemini `generateContent` client; `x-goog-api-key` and `Authorization: Bearer` styles; shared OkHttp client |
| `common/.../common/misc/ai/AiSummaryStore.java` | JSON cache keyed by video id, capped at 100 entries |
| `common/.../common/misc/ai/AiSummaryManager.java` | Cache-then-network orchestration returning `Observable<Result>` |
| `common/.../presenters/settings/AiSummarySettingsPresenter.java` | Settings screen (incl. restore defaults, clear cache) |
| `common/.../prefs/common/DataSaverBase.java` | Added `getString`/`setString` helpers |
| `common/.../prefs/MainUIData.java` | `MENU_ITEM_AI_SUMMARY = 1L << 39` |
| `common/.../dialogs/menu/VideoMenuPresenter.java` | `appendAiSummaryButton` + async fetch + summary dialog |
| `common/.../misc/AppDataSourceManager.java` | Settings entry |
| `common/src/main/res/values/strings.xml`, `drawable-nodpi/settings_ai_summary.png` | Strings and icon |

### Verified behaviour of the configured endpoints

Tested live against a self-hosted LiteLLM proxy (model `gemini-3.8-flash` → `google/gemini-3.8-flash`) using a
short-lived virtual key. Test videos: *Me at the zoo* (`jNQXAC9IVRw`, really a man at an elephant enclosure)
and *Never Gonna Give You Up* (`dQw4w9WgXcQ`).

| Request shape | Auth | Video ingested? | Evidence |
|---|---|---|---|
| `{base}/gemini/v1beta/models/{m}:generateContent` | `x-goog-api-key` | **Route broken** | HTTP 500 even for a text-only prompt |
| `{base}/v1beta/models/{m}:generateContent` | `Authorization: Bearer` | **No** | `promptTokenCount: 10`; a different hallucination each run ("desert, red rock formations", "pipette watering a seedling") |
| `{base}/v1/chat/completions` + `video_url` part | `Authorization: Bearer` | **Yes** | Me at the zoo: `video_tokens: 1197`, `audio_tokens: 479`, correct answer. Rick Astley: `video_tokens: 19387`, correct answer |

Conclusions:

- The native Gemini `fileData` shape is only usable against Google directly
  (`generativelanguage.googleapis.com` with `x-goog-api-key`). Through this LiteLLM proxy it is a dead end.
- The OpenAI-compatible route with a `video_url` content part genuinely ingests video **and audio**, so it is
the working proxy path. Hence the `Request format` setting.

Reference configuration for a LiteLLM proxy:

| Setting | Value |
|---|---|
| API base URL | `https://<proxy-host>` |
| Request format | OpenAI chat completions |
| Authentication | `Authorization: Bearer` |
| Model | whatever the proxy exposes, e.g. `gemini-3.8-flash` |

Reference configuration for Google directly:

| Setting | Value |
|---|---|
| API base URL | `https://generativelanguage.googleapis.com` (default) |
| Request format | Gemini generateContent (default) |
| Authentication | `x-goog-api-key` (default) |

### Ingestion guard

A silently dropped video produces a confidently wrong summary, which is worse than an error. Both response
parsers therefore check the usage data and fail loudly instead of showing the text:

- Gemini: `usageMetadata.promptTokenCount` below 100.
- OpenAI: `usage.prompt_tokens_details.video_tokens` must be greater than 0 when reported, otherwise
  `usage.prompt_tokens` below 100. Skipped when the provider reports no usage data at all.

This guard was validated against the broken route above, which returns `promptTokenCount: 10`.

### Known gaps

- Not compiled or run: no JDK or Android SDK was available. Verification was code review plus live
  request-shape testing against a real proxy.
- No live/upcoming video guard; those will surface as an API error.
- The API key is stored in plain `SharedPreferences`, like the existing web-proxy password.
- Only the default `values/` strings were added; other locales fall back to English.


