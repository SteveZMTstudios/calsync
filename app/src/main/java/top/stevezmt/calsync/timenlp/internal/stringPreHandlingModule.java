package top.stevezmt.calsync.timenlp.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapted (trimmed) from Time-NLP stringPreHandlingModule.java
 * Responsibilities:
 *  - Normalize full-width chars / remove noise tokens
 *  - Convert Chinese numerals to Arabic digits for downstream regex
 */
public class stringPreHandlingModule {

    private static final Map<Character, Integer> CH_NUM = new HashMap<>();
    static {
        CH_NUM.put('零',0); CH_NUM.put('〇',0); CH_NUM.put('一',1); CH_NUM.put('二',2); CH_NUM.put('两',2);
        CH_NUM.put('三',3); CH_NUM.put('四',4); CH_NUM.put('五',5); CH_NUM.put('六',6); CH_NUM.put('七',7);
        CH_NUM.put('八',8); CH_NUM.put('九',9); CH_NUM.put('十',10); CH_NUM.put('百',100); CH_NUM.put('千',1000);
    }

    public static String numberTranslator(String target) {
        if (target == null || target.isEmpty()) return target;
        // very small subset: translate sequences like "二十三" "一百二十" "二零二五" etc.
        // IMPORTANT: Do NOT translate the single weekday char after "周" or "星期" (e.g. "周五"/"星期三").
        StringBuilder out = new StringBuilder();
        int section = 0; // current section value
        int number = 0;  // last single digit(s) collected
        boolean hasUnit = false;
        boolean protectNextChineseDigitForWeek = false; // when previous char was 周 or 期 (from 星期)

        for (int i=0;i<target.length();i++) {
            char c = target.charAt(i);
            // Enable protection if we encounter 周 or 期 (for 星期X). Only applies to the very next Chinese digit char.
            if (c == '周' || c == '期') {
                // flush any pending numbers before appending plain char
                if (section != 0 || hasUnit) {
                    section += number;
                    out.append(section);
                    section = 0; number = 0; hasUnit = false;
                } else if (number != 0) {
                    out.append(number); number = 0;
                }
                out.append(c);
                protectNextChineseDigitForWeek = true;
                continue;
            }

            Integer val = CH_NUM.get(c);
            // If this is the weekday digit immediately after 周/星期, do not convert it
            if (protectNextChineseDigitForWeek && val != null && val < 10) {
                // flush accumulated Arabic number first
                if (section != 0 || hasUnit) {
                    section += number;
                    out.append(section);
                    section = 0; number = 0; hasUnit = false;
                } else if (number != 0) {
                    out.append(number); number = 0;
                }
                out.append(c); // keep Chinese weekday numeral
                protectNextChineseDigitForWeek = false; // only protect a single char
                continue;
            }
            // reset protection flag if current char isn't the protected weekday char
            if (protectNextChineseDigitForWeek && (val == null || val >= 10)) {
                protectNextChineseDigitForWeek = false;
            }

            if (val == null) {
                // flush
                if (section != 0 || hasUnit) {
                    section += number;
                    out.append(section);
                    section = 0; number = 0; hasUnit = false;
                } else if (number != 0) {
                    out.append(number); number = 0;
                }
                out.append(c);
                continue;
            }
            if (val >= 10) { // unit
                if (val == 10 && (number == 0)) number = 1; // e.g. "十五" -> 1*10 +5
                section += number * val;
                number = 0; hasUnit = true;
            } else {
                number = number * 10 + val; // support 二零二五 → 2025
            }
        }
        if (section != 0 || hasUnit) {
            section += number;
            out.append(section);
        } else if (number != 0) {
            out.append(number);
        }
        return out.toString();
    }

    public static String preHandling(String target) {
        if (target == null) return null;
        String s = target;
        s = s.replaceAll("[\u3000\t]+", " ");
        s = s.replaceAll("今儿", "今天");
        // additional normalization rules could be added here
        return numberTranslator(s);
    }
}
