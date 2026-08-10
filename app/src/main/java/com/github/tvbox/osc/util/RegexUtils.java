package com.github.tvbox.osc.util;

import android.util.LruCache;

import java.util.regex.Pattern;

public class RegexUtils {

    // 有界缓存，避免正则模式无上限累积；LruCache 线程安全
    private static final int MAX_PATTERN_CACHE = 200;
    private static final LruCache<String, Pattern> patternCache = new LruCache<>(MAX_PATTERN_CACHE);

    public static Pattern getPattern(String regex) {
        Pattern pattern = patternCache.get(regex);
        if (pattern == null) {
            pattern = Pattern.compile(regex);
            patternCache.put(regex, pattern);
        }
        return pattern;
    }

    public static Pattern getPattern(String regex, int flag) {
        // flag 需参与缓存 key，避免与无 flag 版本互相错用
        String key = flag == 0 ? regex : regex + "#" + flag;
        Pattern pattern = patternCache.get(key);
        if (pattern == null) {
            pattern = Pattern.compile(regex, flag);
            patternCache.put(key, pattern);
        }
        return pattern;
    }
}
