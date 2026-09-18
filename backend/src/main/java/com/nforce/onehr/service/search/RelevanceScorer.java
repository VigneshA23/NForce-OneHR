package com.nforce.onehr.service.search;

/** Simple, deliberately non-fuzzy relevance ranking shared by the in-memory search providers. */
public final class RelevanceScorer {

    private RelevanceScorer() {
    }

    /** 3 = exact match, 2 = starts-with, 1 = contains, 0 = no match. Case-insensitive. */
    public static int score(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return 0;
        String t = text.trim().toLowerCase();
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return 0;
        if (t.equals(q)) return 3;
        if (t.startsWith(q)) return 2;
        if (t.contains(q)) return 1;
        return 0;
    }

    /** The best score across all of a record's candidate fields (e.g. name, code, email). */
    public static int bestScore(String query, String... fields) {
        int best = 0;
        for (String field : fields) {
            int s = score(field, query);
            if (s > best) best = s;
        }
        return best;
    }
}
