package io.codetriage.similarity;

/** One JPlag comparison, before it is classified or interpreted. */
public record RawPair(String leftName, String rightName,
                      double similarity, double maximalSimilarity) {
}
