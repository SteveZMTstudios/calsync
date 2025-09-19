package top.stevezmt.calsync.timenlp.internal;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class TimeNormalizer {

    private static final Pattern TIME_EXPRESSION = Pattern.compile(
        // One or more of the allowed tokens glued together to form a time phrase
        "(((下下周|下周|本周|这周)(?:周|星期)?[一二三四五六日天])|" +
        "((?:周|星期)[一二三四五六日天])|" +
        "本周末|这个周末|下周末|本周|这周|今天|明天|后天|大后天|昨天|今早|明早|明晚|今晚|明日|后晚|" +
        // Explicit Y/M/D
        "[0-9]{1,4}年[0-9]{1,2}月[0-9]{1,2}[日号]|" +
        // Chinese month-day
        "[0-9]{1,2}月[0-9]{1,2}[日号]?|" +
        // Slash date MM/DD
        "[0-9]{1,2}/[0-9]{1,2}|" +
        // Dot or dash date M.DD or M-DD
        "[0-9]{1,2}[.-][0-9]{1,2}|" +
        // HH:mm or H点M
        "[0-9]{1,2}[:：点][0-9]{1,2}|[0-9]{1,2}点(?:半)?|" +
        // fuzzy periods
        "凌晨|清晨|早上|上午|中午|下午|午后|傍晚|晚上|晚间|深夜|午夜|" +
        // relative durations: X天/小时/分钟/秒 后
        "[一二三四五六七八九十百零0-9]+(?:个)?(?:天|小时|分(?:钟)?|秒)后)+"
    );

    private Calendar baseTime;
    private final List<TimeUnit> units = new ArrayList<>();

    public void parse(String text, Calendar base) {
        units.clear();
        this.baseTime = (Calendar) base.clone();
        String pre = stringPreHandlingModule.preHandling(text);
        Matcher m = TIME_EXPRESSION.matcher(pre);
        while (m.find()) {
            String exp = m.group();
            // merge consecutive punctuation trimmed
            exp = exp.replaceAll("[，。,.]+$","" );
            units.add(new TimeUnit(exp, baseTime));
        }
    }

    public List<TimeUnit> getTimeUnits() { return units; }
}
