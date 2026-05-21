package com.investory.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;

public class PinyinUtil {

    private static final HanyuPinyinOutputFormat FMT;

    static {
        FMT = new HanyuPinyinOutputFormat();
        FMT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    /** Returns first-letter pinyin abbreviation. E.g. 贵州茅台 → "gzmt", 比亚迪 → "byd" */
    public static String toAbbr(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') {
                try {
                    String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c, FMT);
                    if (pinyins != null && pinyins.length > 0 && !pinyins[0].isEmpty()) {
                        sb.append(pinyins[0].charAt(0));
                    }
                } catch (Exception ignored) {}
            } else if (Character.isLetter(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
