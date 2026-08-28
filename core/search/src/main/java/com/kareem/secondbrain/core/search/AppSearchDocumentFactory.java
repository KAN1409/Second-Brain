package com.kareem.secondbrain.core.search;

import androidx.appsearch.app.AppSearchSchema;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.GenericDocument;

final class AppSearchDocumentFactory {
    static final String SCHEMA = "MemoryChunkV1";
    static final String NAMESPACE = "memory_chunks";
    static final String PROP_MEMORY_ID = "memoryId";
    static final String PROP_TEXT = "text";
    static final String PROP_EMBEDDING = "embedding";

    private AppSearchDocumentFactory() {}

    static AppSearchSchema schema() {
        AppSearchSchema.StringPropertyConfig memoryId =
                new AppSearchSchema.StringPropertyConfig.Builder(PROP_MEMORY_ID)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .build();
        AppSearchSchema.StringPropertyConfig text =
                new AppSearchSchema.StringPropertyConfig.Builder(PROP_TEXT)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build();
        AppSearchSchema.EmbeddingPropertyConfig embedding =
                new AppSearchSchema.EmbeddingPropertyConfig.Builder(PROP_EMBEDDING)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setIndexingType(AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY)
                        .build();
        return new AppSearchSchema.Builder(SCHEMA)
                .addProperty(memoryId)
                .addProperty(text)
                .addProperty(embedding)
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static GenericDocument document(SemanticIndexDocument source) {
        GenericDocument.Builder builder =
                new GenericDocument.Builder(NAMESPACE, source.getChunkId(), SCHEMA);
        return builder
                .setPropertyString(PROP_MEMORY_ID, source.getMemoryId())
                .setPropertyString(PROP_TEXT, source.getText())
                .setPropertyEmbedding(
                        PROP_EMBEDDING,
                        new EmbeddingVector(source.getVector(), source.getModelSignature()))
                .build();
    }
}
