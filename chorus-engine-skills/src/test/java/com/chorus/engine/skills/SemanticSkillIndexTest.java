package com.chorus.engine.skills;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.store.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticSkillIndexTest {

    FakeEmbeddingClient fakeEmbedding = new FakeEmbeddingClient();
    InMemoryVectorStore vectorStore = new InMemoryVectorStore();
    SemanticSkillIndex index = new SemanticSkillIndex(vectorStore);

    @Test
    void indexStoresSkillEmbeddings() {
        Skill skill = new Skill("s1", "Search", "Web search skill", "prompt", List.of(), Map.of(), List.of());
        float[] embedding = {1.0f, 0.0f, 0.0f, 0.0f};

        index.index(skill, embedding);

        assertThat(vectorStore.count()).isEqualTo(1);
    }

    @Test
    void searchFindsSemanticallySimilarSkills() {
        Skill skillA = new Skill("s1", "Web Search", "Search the web", "prompt", List.of(), Map.of(), List.of());
        Skill skillB = new Skill("s2", "Code Writer", "Write code", "prompt", List.of(), Map.of(), List.of());

        float[] vecA = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] vecB = {0.0f, 1.0f, 0.0f, 0.0f};
        float[] query = {0.99f, 0.01f, 0.0f, 0.0f};

        index.index(skillA, vecA);
        index.index(skillB, vecB);

        List<Skill> results = index.query(query, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("s1");
        assertThat(results.get(1).id()).isEqualTo("s2");
    }

    @Test
    void emptyIndexSearchReturnsEmpty() {
        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};

        List<Skill> results = index.query(query, 5);

        assertThat(results).isEmpty();
    }

    @Test
    void topKRespected() {
        Skill skillA = new Skill("s1", "A", "Desc A", "prompt", List.of(), Map.of(), List.of());
        Skill skillB = new Skill("s2", "B", "Desc B", "prompt", List.of(), Map.of(), List.of());
        Skill skillC = new Skill("s3", "C", "Desc C", "prompt", List.of(), Map.of(), List.of());

        index.index(skillA, new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        index.index(skillB, new float[]{0.9f, 0.1f, 0.0f, 0.0f});
        index.index(skillC, new float[]{0.1f, 0.9f, 0.0f, 0.0f});

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        List<Skill> results = index.query(query, 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void nullSkillRejection() {
        assertThatThrownBy(() -> index.index(null, new float[]{1.0f, 0.0f, 0.0f, 0.0f}))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullEmbeddingRejection() {
        Skill skill = new Skill("s1", "A", "Desc", "prompt", List.of(), Map.of(), List.of());
        assertThatThrownBy(() -> index.index(skill, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullQueryEmbeddingRejection() {
        assertThatThrownBy(() -> index.query(null, 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void topKLessThanOneReturnsEmpty() {
        Skill skill = new Skill("s1", "A", "Desc", "prompt", List.of(), Map.of(), List.of());
        index.index(skill, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        assertThat(index.query(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 0)).isEmpty();
        assertThat(index.query(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, -1)).isEmpty();
    }

    @Test
    void usesProvidedVectorOperations() {
        VectorOperations customOps = new VectorOperations() {
            @Override
            public double dotProduct(float[] a, float[] b) {
                return 0.0;
            }

            @Override
            public double cosineSimilarity(float[] a, float[] b) {
                return 0.5; // Fixed similarity for testing
            }

            @Override
            public double euclideanDistance(float[] a, float[] b) {
                return 0.0;
            }

            @Override
            public double squaredEuclideanDistance(float[] a, float[] b) {
                return 0.0;
            }

            @Override
            public void normalize(float[] vec) {
            }

            @Override
            public String implementationName() {
                return "fixed";
            }
        };

        InMemoryVectorStore store = new InMemoryVectorStore(customOps);
        SemanticSkillIndex customIndex = new SemanticSkillIndex(store, customOps);

        Skill skill = new Skill("s1", "A", "Desc", "prompt", List.of(), Map.of(), List.of());
        customIndex.index(skill, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        List<Skill> results = customIndex.query(new float[]{0.0f, 1.0f, 0.0f, 0.0f}, 1);
        assertThat(results).hasSize(1);
    }
}
