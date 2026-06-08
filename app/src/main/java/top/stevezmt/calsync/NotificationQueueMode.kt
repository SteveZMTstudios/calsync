package top.stevezmt.calsync

enum class NotificationQueueMode(val id: Int, val displayName: String) {
    OFF(0, "关闭"),
    FAST(1, "尽快添加事件"),
    COMPLETE(2, "完整读取消息");

    override fun toString(): String = displayName

    companion object {
        fun fromId(id: Int): NotificationQueueMode = entries.firstOrNull { it.id == id } ?: OFF
        fun fromDisplayName(label: String?): NotificationQueueMode =
            entries.firstOrNull { it.displayName == label } ?: OFF
    }
}
