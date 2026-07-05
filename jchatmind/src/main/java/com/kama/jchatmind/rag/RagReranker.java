package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagReranker {
    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

    public List<RagSearchResult> rerank(
            String query,
            List<RagSearchResult> candidates,
            double vectorWeight,
            double lexicalWeight
    ) {
        if (!StringUtils.hasText(query) || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        List<RagSearchResult> reranked = new ArrayList<>();
        for (RagSearchResult candidate : candidates) {
            double vectorScore = candidate.getScore() == null ? 0.0 : candidate.getScore();
            double lexicalScore = lexicalScore(query, searchableText(candidate));
            double rerankScore = vectorWeight * vectorScore + lexicalWeight * lexicalScore;
            reranked.add(candidate.toBuilder()
                    .lexicalScore(lexicalScore)
                    .rerankScore(rerankScore)
                    .build());
        }

        reranked.sort((left, right) -> Double.compare(
                right.getRerankScore() == null ? 0.0 : right.getRerankScore(),
                left.getRerankScore() == null ? 0.0 : left.getRerankScore()
        ));
        return reranked;
    }

    double lexicalScore(String query, String text) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(text)) {
            return 0.0;
        }

        Set<String> queryFeatures = features(query);
        if (queryFeatures.isEmpty()) {
            return 0.0;
        }

        Set<String> textFeatures = features(text);
        if (textFeatures.isEmpty()) {
            return 0.0;
        }

        long hitCount = queryFeatures.stream().filter(textFeatures::contains).count();
        return (double) hitCount / queryFeatures.size();
    }

    private String searchableText(RagSearchResult result) {
        return "%s\n%s\n%s".formatted(
                result.getDocumentTitle() == null ? "" : result.getDocumentTitle(),
                result.getMetadata() == null ? "" : result.getMetadata(),
                result.getContent() == null ? "" : result.getContent()
        );
    }

    private Set<String> features(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> features = new HashSet<>();

        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2) {
                features.add(token);
            }
        }

        List<Integer> cjkCodePoints = normalized.codePoints()
                .filter(this::isCjk)
                .boxed()
                .toList();
        for (int i = 0; i < cjkCodePoints.size(); i++) {
            features.add(new String(Character.toChars(cjkCodePoints.get(i))));
            if (i + 1 < cjkCodePoints.size()) {
                features.add(new String(Character.toChars(cjkCodePoints.get(i)))
                        + new String(Character.toChars(cjkCodePoints.get(i + 1))));
            }
        }

        return features;
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
