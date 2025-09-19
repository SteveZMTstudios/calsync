package top.stevezmt.calsync.timenlp.internal;

import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highly trimmed adaptation of Time-NLP TimeUnit.java
 * Keeps core normalization for: year/month/day/hour/minute/second + fuzzy period words
 */
public class TimeUnit {
    public final TimePoint tp = new TimePoint();
    private final String exp; // matched expression fragment
    private final Calendar contextCal; // base time for relative resolution
    private Long resolvedTime; // millis

    public TimeUnit(String exp, Calendar base) {
        this.exp = exp;
        this.contextCal = (Calendar) base.clone();
        normalize();
    }

    private void normalize() {
        String s = exp;
        // year
        Matcher m = Pattern.compile("(\\d{2,4})年").matcher(s);
        if (m.find()) tp.tunit[0] = Integer.parseInt(m.group(1));
        // month
        m = Pattern.compile("(\\d{1,2})月").matcher(s);
        if (m.find()) tp.tunit[1] = Integer.parseInt(m.group(1));
        // day
        m = Pattern.compile("(\\d{1,2})[日号]").matcher(s);
        if (m.find()) tp.tunit[2] = Integer.parseInt(m.group(1));
        // short date MM/DD
        m = Pattern.compile("(\\d{1,2})/(\\d{1,2})").matcher(s);
        if (m.find()) {
            tp.tunit[1] = Integer.parseInt(m.group(1));
            tp.tunit[2] = Integer.parseInt(m.group(2));
        }
        // dot date M.DD
        m = Pattern.compile("(\\d{1,2})[.](\\d{1,2})").matcher(s);
        if (m.find()) {
            tp.tunit[1] = Integer.parseInt(m.group(1));
            tp.tunit[2] = Integer.parseInt(m.group(2));
        }
        // dash date M-DD
        m = Pattern.compile("(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (m.find()) {
            tp.tunit[1] = Integer.parseInt(m.group(1));
            tp.tunit[2] = Integer.parseInt(m.group(2));
        }
        // weekday tokens: 周X 或 星期X
        m = Pattern.compile("(?:周|星期)([一二三四五六日天])").matcher(s);
        if (m.find()) {
            int targetDow = charWeekday(m.group(1).charAt(0));
            if (targetDow > 0) {
                int weekOffset = s.contains("下下周") ? 2 : (s.contains("下周") ? 1 : 0);
                moveToWeekday(targetDow, weekOffset);
            }
        }
        // fuzzy period => set hour
        if (s.contains("凌晨") || s.contains("清晨")) tp.tunit[3] = 5;
        else if (s.contains("早上") || s.contains("上午") || s.contains("早晨")) tp.tunit[3] = 9;
        else if (s.contains("中午")) tp.tunit[3] = 12;
        else if (s.contains("下午") || s.contains("午后")) tp.tunit[3] = 15;
        else if (s.contains("傍晚")) tp.tunit[3] = 18;
    else if (s.contains("午夜")) { tp.tunit[3] = 0; tp.tunit[4] = 0; } // handle before generic night tokens
    else if (s.contains("晚上") || s.contains("晚间") || s.contains("夜间") || s.contains("今晚") || s.contains("明晚")) tp.tunit[3] = 20;
    else if (s.contains("深夜")) tp.tunit[3] = 23;
        // HH:mm or explicit hour — preserve fuzzy period context (e.g. "下午5点" -> 17:00)
        m = Pattern.compile("(\\d{1,2})[:：点](\\d{1,2})").matcher(s);
        if (m.find()) {
            int parsedHour = Integer.parseInt(m.group(1));
            tp.tunit[4] = Integer.parseInt(m.group(2));
            // if fuzzy period indicates PM/晚/傍晚, convert hour to 24h if needed
            if ((s.contains("下午") || s.contains("傍晚") || s.contains("晚上") || s.contains("晚间") || s.contains("今晚") || s.contains("明晚") || s.contains("夜")) && parsedHour < 12) {
                parsedHour += 12;
            }
            // handle 凌晨 12 -> 0
            if (s.contains("凌晨") && parsedHour == 12) parsedHour = 0;
            tp.tunit[3] = parsedHour;
            // // special case: 表达为“今晚0点”应当指向次日 00:00
            // if (s.contains("今晚") && parsedHour == 0) {
            //     contextCal.add(Calendar.DAY_OF_MONTH, 1);
            // }
        } else {
            // hour only
            m = Pattern.compile("(\\d{1,2})点").matcher(s);
            if (m.find()) {
                int parsedHour = Integer.parseInt(m.group(1));
                if ((s.contains("下午") || s.contains("傍晚") || s.contains("晚上") || s.contains("晚间") || s.contains("今晚") || s.contains("明晚") || s.contains("夜")) && parsedHour < 12) {
                    parsedHour += 12;
                }
                if (s.contains("凌晨") && parsedHour == 12) parsedHour = 0;
                tp.tunit[3] = parsedHour;
                // special case: '今晚0点' when matched as hour-only should map to next day's 00:00
                // if (s.contains("今晚") && parsedHour == 0) {
                //     contextCal.add(Calendar.DAY_OF_MONTH, 1);
                // }
            }
        }
        // relative days / weeks
    if (s.contains("今天")) {}
        else if (s.contains("明天") || s.contains("明早") || s.contains("明晚") || s.contains("明日")) contextCal.add(Calendar.DAY_OF_MONTH,1);
        else if (s.contains("大后天")) contextCal.add(Calendar.DAY_OF_MONTH,3); // check before 后天 to avoid premature match
        else if (s.contains("后天")) contextCal.add(Calendar.DAY_OF_MONTH,2);
        else if (s.contains("昨天")) contextCal.add(Calendar.DAY_OF_MONTH,-1);

        // relative durations like X天/小时/分钟/秒后, include half-hour handling
        // handle patterns like '3个半小时后' first
        Matcher dm = Pattern.compile("([一二三四五六七八九十百零0-9]+)(?:个)?半小时后").matcher(s);
        if (dm.find()) {
            int n = parseChineseOrArabic(dm.group(1));
            contextCal.add(Calendar.HOUR_OF_DAY, n);
            contextCal.add(Calendar.MINUTE, 30);
        } else {
            Matcher halfOnly = Pattern.compile("(?:半小时后|半个小时后)").matcher(s);
            if (halfOnly.find()) {
                contextCal.add(Calendar.MINUTE, 30);
            }
        }
            // then normal integer units
            // Note: support combined forms like "1天2小时后" (units may not each be followed by '后')
            dm = Pattern.compile("([一二三四五六七八九十百零0-9]+)(?:个)?天").matcher(s);
            if (dm.find()) contextCal.add(Calendar.DAY_OF_MONTH, parseChineseOrArabic(dm.group(1)));
            dm = Pattern.compile("([一二三四五六七八九十百零0-9]+)(?:个)?小时").matcher(s);
            // avoid double-counting when a '半小时' pattern was already matched above
            if (!s.contains("半小时") && dm.find()) contextCal.add(Calendar.HOUR_OF_DAY, parseChineseOrArabic(dm.group(1)));
            dm = Pattern.compile("([一二三四五六七八九十百零0-9]+)(?:个)?分(?:钟)?").matcher(s);
            if (dm.find()) contextCal.add(Calendar.MINUTE, parseChineseOrArabic(dm.group(1)));
            dm = Pattern.compile("([一二三四五六七八九十百零0-9]+)(?:个)?秒").matcher(s);
            if (dm.find()) contextCal.add(Calendar.SECOND, parseChineseOrArabic(dm.group(1)));
        // If any '后' appeared and no explicit hour/minute in the token, adopt from updated context
        if ((s.contains("后") || s.contains("之后")) && tp.tunit[3] == -1) {
            tp.tunit[3] = contextCal.get(Calendar.HOUR_OF_DAY);
        }
        if ((s.contains("后") || s.contains("之后")) && tp.tunit[4] == -1) {
            tp.tunit[4] = contextCal.get(Calendar.MINUTE);
        }

        // weekend phrases -> set to Saturday morning as anchor; adapter may extend to range later
        if (s.contains("本周末") || s.contains("这个周末") || s.contains("这周末")) moveToWeekend(0);
        else if (s.contains("下周末")) moveToWeekend(1);
        // explicit '本周' day not specified: keep base week (no shift)
        if (s.contains("后晚")) { // treat as the day after tomorrow evening unless already moved
            contextCal.add(Calendar.DAY_OF_MONTH,2);
            tp.tunit[3] = 20; // evening default
        }


        if (s.contains("午夜")) {
            // If hour is 0 due to 午夜, and no explicit date moved yet, move to next day midnight
            // Heuristic: if no explicit day/month/year was found and no relative day words other than 今晚/今天 present
            boolean hasExplicitYMD = tp.tunit[0] != -1 || tp.tunit[1] != -1 || tp.tunit[2] != -1;
            boolean hasRelativeBeyondToday = s.contains("明") || s.contains("后天") || s.contains("下周");
            if (!hasExplicitYMD && !hasRelativeBeyondToday) {
                contextCal.add(Calendar.DAY_OF_MONTH, 1);
                tp.tunit[0] = contextCal.get(Calendar.YEAR);
                tp.tunit[1] = contextCal.get(Calendar.MONTH) + 1;
                tp.tunit[2] = contextCal.get(Calendar.DAY_OF_MONTH);
            }
        }

        // fill from context when missing
        if (tp.tunit[0] == -1) tp.tunit[0] = contextCal.get(Calendar.YEAR);
        if (tp.tunit[1] == -1) tp.tunit[1] = contextCal.get(Calendar.MONTH)+1;
        if (tp.tunit[2] == -1) tp.tunit[2] = contextCal.get(Calendar.DAY_OF_MONTH);
        if (tp.tunit[3] == -1) tp.tunit[3] = 9; // default hour
        if (tp.tunit[4] == -1) tp.tunit[4] = 0;
        if (tp.tunit[5] == -1) tp.tunit[5] = 0;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, tp.tunit[0]);
        cal.set(Calendar.MONTH, tp.tunit[1]-1);
        cal.set(Calendar.DAY_OF_MONTH, tp.tunit[2]);
        cal.set(Calendar.HOUR_OF_DAY, tp.tunit[3]);
        cal.set(Calendar.MINUTE, tp.tunit[4]);
        cal.set(Calendar.SECOND, tp.tunit[5]);
        cal.set(Calendar.MILLISECOND,0);
        resolvedTime = cal.getTimeInMillis();
    }

    private void moveToWeekend(int weekOffset) {
        Calendar c = contextCal;
        c.add(Calendar.WEEK_OF_YEAR, weekOffset);
        // Move to Saturday
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1 Sun ... 7 Sat
    int diff;
        if (dow == Calendar.SATURDAY) diff = 0; else {
            // compute days until Saturday
            diff = (Calendar.SATURDAY - dow + 7) % 7;
        }
        c.add(Calendar.DAY_OF_MONTH, diff);
        // set a default morning hour if none chosen
        if (tp.tunit[3] == -1) tp.tunit[3] = 9;
    }

    private void moveToWeekday(int targetDow, int weekOffset) {
        // Anchor to Monday of the target week, then add (targetDow-1) days.
    Calendar base = (Calendar) contextCal.clone();
        // Step 1: move to Monday of the current week (do not advance to next week yet)
        int dow = base.get(Calendar.DAY_OF_WEEK); // 1..7 (Sun..Sat)
        int diffToMonday = Calendar.MONDAY - dow;
        if (diffToMonday > 0) diffToMonday -= 7; // move backwards to current Monday
        base.add(Calendar.DAY_OF_MONTH, diffToMonday);
        // Step 2: add week offset (0 for 本周, 1 for 下周, 2 for 下下周)
        if (weekOffset > 0) base.add(Calendar.WEEK_OF_YEAR, weekOffset);
        // Step 3: add days from Monday to target weekday (Mon=1 .. Sun=7)
        int daysFromMonday = targetDow - 1;
        base.add(Calendar.DAY_OF_MONTH, daysFromMonday);
        // If plain 周X without 本周/下周 and target day already passed for this week, move to next week
        if (!exp.contains("本周") && !exp.contains("这周") && !exp.contains("下周") && !exp.contains("下下周")) {
            Calendar now = (Calendar) contextCal.clone();
            Calendar cand = (Calendar) base.clone();
            if (cand.before(now)) {
                base.add(Calendar.WEEK_OF_YEAR, 1);
            }
        }
        // write the computed candidate date back to contextCal (Y-M-D only)
        contextCal.set(Calendar.YEAR, base.get(Calendar.YEAR));
        contextCal.set(Calendar.MONTH, base.get(Calendar.MONTH));
        contextCal.set(Calendar.DAY_OF_MONTH, base.get(Calendar.DAY_OF_MONTH));
    }

    // removed unused mapToMondayFirst helper

    private int charWeekday(char c) {
        switch (c){
            case '一': return 1; case '二': return 2; case '三': return 3; case '四': return 4; case '五': return 5; case '六': return 6; case '日': case '天': return 7; default: return -1;
        }
    }

    public Long getResolvedTime() { return resolvedTime; }
    public String getExp() { return exp; }

    private int parseChineseOrArabic(String s) {
        // very small helper: try parse int, otherwise sum chinese digits with 十/百 support
    try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        int result = 0, temp = 0, unit = 1;
        for (int i=0;i<s.length();i++) {
            char c = s.charAt(i);
            int v;
            switch (c) {
                case '零': case '〇': v = 0; break;
                case '一': v = 1; break; case '二': case '两': v = 2; break; case '三': v = 3; break; case '四': v = 4; break;
                case '五': v = 5; break; case '六': v = 6; break; case '七': v = 7; break; case '八': v = 8; break; case '九': v = 9; break;
                case '十': unit = 10; if (temp == 0) temp = 1; temp *= unit; continue;
                case '百': unit = 100; if (temp == 0) temp = 1; temp *= unit; continue;
                default: continue;
            }
            if (unit > 1) { temp += v * unit; unit = 1; } else { temp = temp * 10 + v; }
        }
        result += temp;
        return result == 0 ? 0 : result;
    }
}
