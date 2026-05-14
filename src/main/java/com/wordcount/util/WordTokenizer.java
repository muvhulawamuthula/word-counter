package com.wordcount.util;

import java.util.HashMap;
import java.util.Map;

public class WordTokenizer {

    public static Map<String, Long> tokenize(String text) {
        Map<String, Long> counts = new HashMap<>();

        String[] words = text.toLowerCase().split("[^a-z']+");
        for (String word : words) {
            if (!word.isBlank() && word.length() > 1) {
                counts.merge(word, 1L, Long::sum);
            }
        }
        return counts;
    }
}
