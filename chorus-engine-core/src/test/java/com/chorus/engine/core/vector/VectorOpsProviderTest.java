package com.chorus.engine.core.vector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.*;

class VectorOpsProviderTest {

    private static final String PROPERTY_KEY = "chorus.vector.ops";
    private String originalValue;

    @BeforeEach
    void saveOriginalProperty() {
        originalValue = System.getProperty(PROPERTY_KEY);
    }

    @AfterEach
    void restoreOriginalProperty() {
        if (originalValue == null) {
            System.clearProperty(PROPERTY_KEY);
        } else {
            System.setProperty(PROPERTY_KEY, originalValue);
        }
    }

    @Test
    void autoDetectReturnsNonNull() {
        VectorOperations ops = VectorOpsProvider.INSTANCE.get();
        assertThat(ops).isNotNull();
    }

    @Test
    void getReturnsSameInstance() {
        VectorOperations first = VectorOpsProvider.INSTANCE.get();
        VectorOperations second = VectorOpsProvider.INSTANCE.get();
        assertThat(first).isSameAs(second);
    }

    @Test
    void vectorOperationsAutoDetectWorks() {
        VectorOperations ops = VectorOperations.autoDetect();
        assertThat(ops).isNotNull();
        assertThat(ops.implementationName()).isNotBlank();
    }

    @Test
    void propertyOverrideScalar() throws Exception {
        System.setProperty(PROPERTY_KEY, "scalar");
        VectorOpsProvider provider = newInstanceViaReflection();
        assertThat(provider.get()).isInstanceOf(ScalarOperations.class);
    }

    @Test
    void propertyOverrideFma() throws Exception {
        System.setProperty(PROPERTY_KEY, "fma");
        VectorOpsProvider provider = newInstanceViaReflection();
        assertThat(provider.get()).isInstanceOf(FmaOperations.class);
    }

    @Test
    void propertyOverrideVectorApi() throws Exception {
        System.setProperty(PROPERTY_KEY, "vectorapi");
        VectorOpsProvider provider = newInstanceViaReflection();
        // VectorApiOperations may not be available on all runtimes, so just assert non-null
        assertThat(provider.get()).isNotNull();
    }

    @Test
    void unknownPropertyFallsBackToAutoDetect() throws Exception {
        System.setProperty(PROPERTY_KEY, "unknown");
        VectorOpsProvider provider = newInstanceViaReflection();
        assertThat(provider.get()).isNotNull();
    }

    private static VectorOpsProvider newInstanceViaReflection() throws Exception {
        Constructor<VectorOpsProvider> constructor = VectorOpsProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
