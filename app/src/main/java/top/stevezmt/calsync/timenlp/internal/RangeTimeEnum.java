package top.stevezmt.calsync.timenlp.internal;

public enum RangeTimeEnum {
    day_break(3),      // 拂晓
    early_morning(8),  // 早晨
    morning(10),       // 上午
    noon(12),          // 中午
    afternoon(15),     // 下午
    night(18),         // 晚上/傍晚
    lateNight(20),     // 深夜前段
    midNight(23);      // 深夜末段

    private final int hour;
    RangeTimeEnum(int hour) { this.hour = hour; }
    public int getHour() { return hour; }
}
