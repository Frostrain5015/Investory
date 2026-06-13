package com.investory.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;

/**
 * 汉字拼音转换工具类。
 * <p>
 * 基于 pinyin4j 库，提供将中文文本转换为拼音首字母缩写的功能。
 * 主要用于股票搜索场景：用户可通过输入拼音首字母（如 "gzmt" 或 "byd"）
 * 快速模糊匹配股票名称，提升搜索体验。
 * 非汉字字母字符（如英文字母）会直接以小写形式保留在结果中。
 * </p>
 */
public class PinyinUtil {

    /**
     * 全局共享的拼音输出格式配置（线程安全，静态初始化一次）。
     * 配置说明：
     * - 大小写：小写（LOWERCASE），缩写结果全部为小写字母
     * - 声调：不含声调（WITHOUT_TONE），避免带数字的声调干扰缩写结果
     */
    private static final HanyuPinyinOutputFormat FMT;

    static {
        // 静态初始化块：在类加载时初始化拼音格式，避免每次调用重复创建对象
        FMT = new HanyuPinyinOutputFormat();
        FMT.setCaseType(HanyuPinyinCaseType.LOWERCASE);   // 输出小写字母
        FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE); // 不输出声调数字
    }

    /**
     * 将中文文本转换为拼音首字母缩写（Pinyin Abbreviation）。
     * <p>
     * 处理规则：
     * <ul>
     *   <li>汉字：取其拼音的第一个字母（小写），多音字取第一读音</li>
     *   <li>英文字母：直接转为小写保留</li>
     *   <li>数字、标点、空格等非字母字符：忽略，不计入结果</li>
     * </ul>
     * 示例：
     * <pre>
     *   "贵州茅台" → "gzmt"
     *   "比亚迪"   → "byd"
     *   "中国平安" → "zgpa"
     * </pre>
     * </p>
     *
     * @param text 待转换的中文或混合文本，允许为 null 或空白
     * @return 拼音首字母缩写字符串；若输入为 null 或空白，返回空字符串 {@code ""}
     */
    public static String toAbbr(String text) {
        if (text == null || text.isBlank()) return ""; // 空输入直接返回空字符串
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') {
                // 判断字符是否在 CJK 统一汉字区间（U+4E00 ~ U+9FFF）
                // 使用 '一'（U+4E00）和 '鿿'（U+9FFF）作为边界进行快速过滤
                try {
                    // 获取该汉字对应的所有读音（多音字会有多个）
                    String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c, FMT);
                    if (pinyins != null && pinyins.length > 0 && !pinyins[0].isEmpty()) {
                        // 取第一个读音的首字母追加到结果中
                        sb.append(pinyins[0].charAt(0));
                    }
                } catch (Exception ignored) {
                    // 转换失败时忽略该字符，不影响整体结果（容错处理）
                }
            } else if (Character.isLetter(c)) {
                // 非汉字的字母字符（如英文）直接转为小写追加
                sb.append(Character.toLowerCase(c));
            }
            // 数字、标点、空格等其他字符直接跳过，不计入缩写
        }
        return sb.toString();
    }
}
