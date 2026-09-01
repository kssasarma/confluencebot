package com.kssasarma.confluencebot.rag.model;

/**
 * A chunk retrieved from the vector store, with its embedding and metadata
 * needed for MMR re-ranking and citation generation.
 */
public class RetrievedChunk {

    private String chunkId;
    private String content;
    private String pageId;
    private String title;
    private String pageUrl;
    private String spaceKey;
    private String sectionHeading;
    private String chunkType;   // TEXT | CODE | TABLE
    private double similarity;
    private float[] embedding;

    private RetrievedChunk() {}

    public static Builder builder() { return new Builder(); }

    public String getChunkId()       { return chunkId; }
    public String getContent()       { return content; }
    public String getPageId()        { return pageId; }
    public String getTitle()         { return title; }
    public String getPageUrl()       { return pageUrl; }
    public String getSpaceKey()      { return spaceKey; }
    public String getSectionHeading(){ return sectionHeading; }
    public String getChunkType()     { return chunkType; }
    public double getSimilarity()    { return similarity; }
    public float[] getEmbedding()    { return embedding; }

    public void setSimilarity(double similarity) { this.similarity = similarity; }

    public boolean containsCode() {
        return "CODE".equals(chunkType) || (content != null && content.contains("```"));
    }

    public static final class Builder {
        private final RetrievedChunk chunk = new RetrievedChunk();

        public Builder chunkId(String v)        { chunk.chunkId = v;        return this; }
        public Builder content(String v)        { chunk.content = v;        return this; }
        public Builder pageId(String v)         { chunk.pageId = v;         return this; }
        public Builder title(String v)          { chunk.title = v;          return this; }
        public Builder pageUrl(String v)        { chunk.pageUrl = v;        return this; }
        public Builder spaceKey(String v)       { chunk.spaceKey = v;       return this; }
        public Builder sectionHeading(String v) { chunk.sectionHeading = v; return this; }
        public Builder chunkType(String v)      { chunk.chunkType = v;      return this; }
        public Builder similarity(double v)     { chunk.similarity = v;     return this; }
        public Builder embedding(float[] v)     { chunk.embedding = v;      return this; }
        public RetrievedChunk build()           { return chunk; }
    }
}
