package top.stevezmt.calsync

import android.content.Context
import android.util.Log

object NotificationProcessor {
	private const val TAG = "NotificationProcessor"

	data class ProcessInput(
		val packageName: String,
		val title: String,
		val content: String,
		val isTest: Boolean = false
	)

	data class ProcessResult(
		val handled: Boolean,
		val eventId: Long? = null,
		val reason: String? = null
	)

	/**
	 * Core processing pipeline used by both real notifications and test simulation.
	 * Steps:
	 * 1) keyword match
	 * 2) selected source app filter (multi or single)
	 * 3) extract sentence containing date/time
	 * 4) parse date/time
	 * 5) build event title/description -> insert calendar
	 * 6) send confirmation notification
	 */
	fun process(context: Context, input: ProcessInput, notifier: ConfirmationNotifier): ProcessResult {
		return try {
			// Capture a single 'now' for this processing run to ensure consistent relative parsing
			val baseMillis = System.currentTimeMillis()
			val engine = SettingsStore.getParsingEngine(context)
			notifier.onDebugLog("process start pkg=${input.packageName} isTest=${input.isTest} baseMillis=$baseMillis engine=${engine.id}")
			val fullText = input.title + "。" + input.content
			val keywords = SettingsStore.getKeywords(context)
			val matchesKeyword = keywords.any { kw ->
				input.title.contains(kw, true) || input.content.contains(kw, true)
			}
			if (!matchesKeyword) return ProcessResult(false, reason = "未匹配关键字")

			val selectedPkgs = SettingsStore.getSelectedSourceAppPkgs(context)
			if (selectedPkgs.isNotEmpty() && input.packageName !in selectedPkgs && !input.isTest) {
				return ProcessResult(false, reason = "包名未在选择列表")
			}

			// Battery saver: do a lightweight guess before full parsing
			if (SettingsStore.isGuessBeforeParseEnabled(context)) {
				if (!DateTimeParser.guessContainsDateTime(context, fullText)) {
					notifier.onDebugLog("prefilter=false (skip)")
					return ProcessResult(false, reason = "预筛选：不像日程")
				}
				notifier.onDebugLog("prefilter=true")
			}

			val sentences = if (engine == ParseEngine.AI_GGUF) {
				listOf(fullText.trim()).filter { it.isNotEmpty() }
			} else {
				DateTimeParser.extractAllSentencesContainingDate(context, fullText)
			}
			if (sentences.isEmpty()) return ProcessResult(false, reason = if (engine == ParseEngine.AI_GGUF) "AI 模式下全文为空" else "未包含时间句子")
			notifier.onDebugLog("sentences=${sentences.size}")
			val (globalTitle, globalLocation) = DateTimeParser.extractTitleAndLocationFromText(context, fullText)

			var anyCreated = false
			var lastEventId: Long? = null
			var lastReason: String? = null
			for (sentence in sentences) {
				try {
					notifier.onDebugLog("sentence='${sentence.take(120)}'")
					val parsed = DateTimeParser.parseDateTime(context, sentence, baseMillis)
					if (parsed == null) { lastReason = "解析失败($sentence)"; continue }
					notifier.onDebugLog("parsed start=${parsed.startMillis} end=${parsed.endMillis} title=${parsed.title} loc=${parsed.location}")

					val chosenLocation = parsed.location ?: globalLocation
					val preferredTitle = globalTitle?.takeIf { it.isNotBlank() }?.let { if (it.length > 60) it.take(60) else it }
					val parsedTitle = parsed.title?.takeIf { it.isNotBlank() }?.let { if (it.length > 60) it.take(60) else it }
					val fallbackTitle = run {
						val trimmed = sentence.trim().replace(Regex("\\n+"), " ").replace(Regex("\\s+"), " ")
						if (trimmed.length > 60) trimmed.take(60) else trimmed
					}
					val eventTitle = preferredTitle ?: parsedTitle ?: fallbackTitle
					var desc = "来源: ${if (input.isTest) "测试" else input.packageName}\n原文:\n${input.title}\n${input.content}"
					if (!chosenLocation.isNullOrBlank()) desc += "\n地点: ${chosenLocation}"

					val eventId = CalendarHelper.insertEvent(context, eventTitle, desc, parsed.startMillis, parsed.endMillis, chosenLocation)
					if (eventId != null) {
						NotificationUtils.sendEventCreated(context, eventId, parsed.startMillis, eventTitle, chosenLocation)
						notifier.onEventCreated(eventId, eventTitle, parsed.startMillis, parsed.endMillis ?: (parsed.startMillis + 60*60*1000L), chosenLocation)
						// also broadcast baseMillis so UI can display what 'now' was when parsing
						try {
							val b = android.content.Intent(NotificationUtils.ACTION_EVENT_CREATED)
							b.setPackage(context.packageName)
							b.putExtra(NotificationUtils.EXTRA_EVENT_ID, eventId)
							b.putExtra(NotificationUtils.EXTRA_EVENT_TITLE, eventTitle)
							b.putExtra(NotificationUtils.EXTRA_EVENT_START, parsed.startMillis)
							b.putExtra(NotificationUtils.EXTRA_EVENT_BASE, baseMillis)
							context.sendBroadcast(b)
						} catch (_: Throwable) {}
						anyCreated = true
						lastEventId = eventId
					} else {
						lastReason = "插入日历失败($sentence)"
					}
				} catch (t: Throwable) {
					Log.w(TAG, "failed processing sentence: $sentence", t)
					lastReason = "异常: ${t.message}"
					try { NotificationUtils.sendError(context, Exception(t)) } catch (_: Throwable) {}
					notifier.onDebugLog("exception=${t::class.java.simpleName}:${t.message}")
				}
			}
			return if (anyCreated) ProcessResult(true, eventId = lastEventId) else ProcessResult(false, reason = lastReason)
		} catch (t: Throwable) {
			Log.e(TAG, "process failed", t)
			try { NotificationUtils.sendError(context, Exception(t)) } catch (_: Throwable) {}
			notifier.onError("处理异常: ${t.message}")
			ProcessResult(false, reason = t.message)
		}
	}

	interface ConfirmationNotifier {
		fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?)
		fun onError(message: String?)
		fun onDebugLog(line: String) {
			// default no-op
		}
	}
}
