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
			val keywords = SettingsStore.getKeywords(context)
			val matchesKeyword = keywords.any { kw ->
				input.title.contains(kw, true) || input.content.contains(kw, true)
			}
			if (!matchesKeyword) return ProcessResult(false, reason = "未匹配关键字")

			val selectedPkgs = SettingsStore.getSelectedSourceAppPkgs(context)
			if (selectedPkgs.isNotEmpty() && input.packageName !in selectedPkgs && !input.isTest) {
				return ProcessResult(false, reason = "包名未在选择列表")
			}

			val sentences = DateTimeParser.extractAllSentencesContainingDate(context, input.title + "。" + input.content)
			if (sentences.isEmpty()) return ProcessResult(false, reason = "未包含时间句子")

			var anyCreated = false
			var lastEventId: Long? = null
			var lastReason: String? = null
				for (sentence in sentences) {
				try {
						val parsed = DateTimeParser.parseDateTime(context, sentence, baseMillis)
					if (parsed == null) { lastReason = "解析失败($sentence)"; continue }

					val eventTitle = parsed.title?.takeIf { it.isNotBlank() } ?: run {
						val trimmed = sentence.trim().replace(Regex("\\n+"), " ").replace(Regex("\\s+"), " ")
						if (trimmed.length > 60) trimmed.take(60) else trimmed
					}
					var desc = "来源: ${if (input.isTest) "测试" else input.packageName}\n原文:\n${input.title}\n${input.content}"
					if (!parsed.location.isNullOrBlank()) desc += "\n地点: ${parsed.location}"

					val eventId = CalendarHelper.insertEvent(context, eventTitle, desc, parsed.startMillis, parsed.endMillis, parsed.location)
					if (eventId != null) {
						NotificationUtils.sendEventCreated(context, eventId, parsed.startMillis, eventTitle, parsed.location)
						notifier.onEventCreated(eventId, eventTitle, parsed.startMillis, parsed.endMillis ?: (parsed.startMillis + 60*60*1000L), parsed.location)
						anyCreated = true
						lastEventId = eventId
					} else {
						lastReason = "插入日历失败($sentence)"
					}
				} catch (e: Exception) {
					Log.w(TAG, "failed processing sentence: $sentence", e)
					lastReason = "异常: ${e.message}"
					try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
				}
			}
			return if (anyCreated) ProcessResult(true, eventId = lastEventId) else ProcessResult(false, reason = lastReason)
		} catch (e: Exception) {
			Log.e(TAG, "process failed", e)
			try { NotificationUtils.sendError(context, e) } catch (_: Throwable) {}
			notifier.onError("处理异常: ${e.message}")
			ProcessResult(false, reason = e.message)
		}
	}

	interface ConfirmationNotifier {
		fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?)
		fun onError(message: String?)
	}
}
