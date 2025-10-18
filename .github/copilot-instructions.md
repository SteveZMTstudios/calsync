# Copilot instructions for calsync

This repo is an Android (Kotlin) app that listens to system notifications, parses Chinese time expressions, and writes calendar events. Keep responses concise and follow the codebase’s patterns below.

## Architecture and data flow
- Entry points
  - `NotificationMonitorService` (NotificationListenerService): receives notifications, extracts title/content, and invokes `NotificationProcessor.process(...)` on a background dispatcher.
  - `KeepAliveService` (foreground service, type dataSync): optional keep-alive, shows ongoing notification; controlled by `SettingsStore.isKeepAliveEnabled`.
  - `CalSyncApp` (Application): window inset handling, crash capture + deferred broadcast via `CrashNotifierReceiver`.
- Processing pipeline (single source of truth)
  1) Keyword filter from `SettingsStore.getKeywords()` and optional app filter from `SettingsStore.getSelectedSourceAppPkgs()`.
  2) Sentence extraction with `DateTimeParser.extractAllSentencesContainingDate(...)`.
  3) Date-time parsing: prefer Rule-based parser with context and baseMillis; fallback to `TimeNLPAdapter` iff `SettingsStore.isTimeNLPEnabled`.
  4) Event insertion via `CalendarHelper.insertEvent(...)`.
  5) Confirmation via `NotificationUtils.sendEventCreated(...)` and in-app broadcast `NotificationUtils.ACTION_EVENT_CREATED`.
- Parsing contracts
  - Public: `DateTimeParser.parseDateTime(sentence)` and `DateTimeParser.parseDateTime(context, sentence, baseMillis)` returning `ParseResult(startMillis, endMillis?, title?, location?)`.
  - `TimeNLPAdapter.parse(text, baseMillis?) -> List<ParseSlot>`; includes small LRU cache and merges date-only/time-only units and ranges like "周五3点到5点".
  - Relative time support like “3天后”“1个半小时后”“还剩X天/小时/分钟/秒” is handled in `DateTimeParser`.

## Project-specific conventions
- Language/locale: Simplified Chinese-first UX; tests freeze a base time when checking parsing.
- Prefer-future behavior is tri-state via `SettingsStore.getPreferFutureOption()`:
  - 0=Auto (heuristics), 1=Prefer future, 2=Disable (don’t roll forward).
- Default times: missing explicit time defaults to 09:00; evening/pm tokens map to 19:00 where appropriate.
- Title/location extraction uses `JiebaWrapper` and heuristics; avoid over-engineering titles—keep short and meaningful.
- Notification channels consolidated: confirmations use `NotificationUtils.CHANNEL_CONFIRM`; errors use `CHANNEL_ERROR`.
- All confirmation notifications include a delete-action handled by `EventActionReceiver` and a deep link to the created event.
- Do not log or toast full notification contents in production paths; rely on `NotificationCache` for brief summaries.

## Key files to learn by example
- Parsing: `DateTimeParser.kt`, `TimeNLPAdapter.kt`, `timenlp/internal/*` (regex-based normalizer), `JiebaWrapper.kt`.
- Notification flow: `NotificationMonitorService.kt`, `NotificationHelper.kt`, `NotificationUtils.kt`, `NotificationCache.kt`.
- Calendar I/O: `CalendarHelper.kt`, `EventActionReceiver.kt`.
- Settings: `SettingsStore.kt` (keywords, selected calendars, app allowlist, prefer-future, TimeNLP toggle, relative words).

## Build, run, test
- JDK 21, AGP 8.13, Kotlin 2.0.21; module `:app` targets minSdk 23, targetSdk 36; Kotlin/JVM target 11.
- Local build examples (PowerShell on Windows):
  - Debug APK: `./gradlew assembleDebug`
  - Release APK: `./gradlew assembleRelease`
  - Unit tests: `./gradlew :app:testDebugUnitTest --no-daemon --info`
  - If OOM, set `GRADLE_OPTS=-Xmx3g` for the process.
- CI: see `.github/workflows/android-build-release.yml`; it builds debug/release and attaches APKs to a GitHub Release.

## Safe changes and patterns for agents
- When adding new parse rules:
  - Extend `DateTimeParser` first; add corresponding unit tests in `app/src/test/java/top/stevezmt/calsync/` with frozen base calendars (see `DateTimeParserTest.kt`).
  - Only fall back to `TimeNLPAdapter` after rule-based attempts.
- When touching notification flow:
  - Keep work on background dispatcher; don’t block `NotificationListenerService` callbacks.
  - Update channels via `NotificationUtils.ensureChannels`; use `safeNotify` to handle T+ permission.
- Calendar writes:
  - Use `SettingsStore.getSelectedCalendarId` when set; otherwise fallback to first visible calendar; always set timezone.
- Respect privacy:
  - App has no INTERNET permission; avoid adding network code. Keep logs minimal and avoid leaking sensitive notification text.

## Quick examples
- Extract sentences: `DateTimeParser.extractAllSentencesContainingDate(ctx, title + "。" + content)`.
- Parse with fixed base: `DateTimeParser.parseDateTime(ctx, sentence, baseMillis)`; expected to honor prefer-future option.
- Insert event: `CalendarHelper.insertEvent(ctx, title, desc, start, end, location)` then `NotificationUtils.sendEventCreated(...)`.

If any parts of these instructions feel incomplete (e.g., missing timenlp/internal details or ROM-specific quirks you rely on), tell me what to clarify and I’ll amend this file.