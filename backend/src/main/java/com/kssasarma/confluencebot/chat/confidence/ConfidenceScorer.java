package com.kssasarma.confluencebot.chat.confidence;

/**
 * Turns retrieval evidence into a single 0–1 number.
 *
 * <p>This is a strategy on purpose. The signals that best predict a good answer differ per corpus,
 * and a deployment that wants to weigh them differently — or replace the whole thing with a
 * learned model — should be able to publish another bean rather than edit the chat pipeline.
 *
 * <p><strong>What the number means.</strong> It measures how well the question matched the indexed
 * documentation. It is <em>not</em> a claim that the answer is correct: an answer can be
 * confidently retrieved and still wrong. Callers must label it accordingly.
 */
@FunctionalInterface
public interface ConfidenceScorer {

    /**
     * @return a score in {@code [0, 1]}, higher meaning a stronger match against the corpus
     */
    double score(ConfidenceSignals signals);
}
