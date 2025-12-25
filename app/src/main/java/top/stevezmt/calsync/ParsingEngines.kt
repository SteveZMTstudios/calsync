package top.stevezmt.calsync

/**
 * Keep engines as enums for stable persistence and simple UI.
 * Extend by adding new enum values (keep ids stable).
 */
enum class ParseEngine(val id: Int, val displayName: String, val description: String) {
    BUILTIN(0, "内置引擎", "基于规则，支持相对时间表达，经过广泛测试，速度快"),
    XK_TIME(1, "xk-time", "支持复杂中文时间表达，准确性欠佳"),
    ML_KIT(2, "ML Kit", "Google ML Kit 实体提取，需要 Google Play 服务，准确性最好"),
    AI_GGUF(3, "AI 本地模型 (GGUF)", "使用 GGUF 模型进行解析，占用空间大，极其不稳定，需额外模型文件");

    override fun toString(): String = displayName

    companion object {
        fun fromId(id: Int): ParseEngine = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

enum class EventParseEngine(val id: Int, val displayName: String, val description: String) {
    BUILTIN(0, "内置引擎", "默认事件解析"),
    ML_KIT(2, "ML Kit", "使用 ML Kit 提取事件标题和地点，需要 Google Play 服务，准确性最好"),
    AI_GGUF(1, "AI 本地模型 (GGUF)", "使用 AI 提取事件标题和地点，极其不稳定，需额外模型文件");

    override fun toString(): String = displayName

    companion object {
        fun fromId(id: Int): EventParseEngine = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}
