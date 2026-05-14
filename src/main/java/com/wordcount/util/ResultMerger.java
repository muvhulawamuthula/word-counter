package com.wordcount.util;

import java.util.Map;

public class ResultMerger {

    public static Map<String, Long> merge(Map<String, Long> base,
                                          Map<String, Long> other) {
        other.forEach((word, count) ->
                base.merge(word, count, Long::sum));
        return base;
    }
}
