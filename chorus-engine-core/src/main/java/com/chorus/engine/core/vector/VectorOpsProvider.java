package com.chorus.engine.core.vector;

import org.jspecify.annotations.NonNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Auto-detects the fastest {@link VectorOperations} implementation.
 *
 * <p>Detection order:
 * <ol>
 *   <li>Java Vector API (SIMD) — if {@code jdk.incubator.vector} is available</li>
 *   <li>FMA scalar — if CPU supports FMA instructions</li>
 *   <li>Pure scalar — always works</li>
 * </ol>
 *
 * <p>User can override via system property: {@code -Dchorus.vector.ops=vectorapi|fma|scalar}.
 */
final class VectorOpsProvider {

    private static final Logger LOGGER = Logger.getLogger(VectorOpsProvider.class.getName());
    static final VectorOpsProvider INSTANCE = new VectorOpsProvider();

    private final VectorOperations implementation;

    private VectorOpsProvider() {
        String override = System.getProperty("chorus.vector.ops");
        if (override != null) {
            this.implementation = switch (override.toLowerCase()) {
                case "vectorapi" -> createVectorApi();
                case "fma" -> new FmaOperations();
                case "scalar" -> new ScalarOperations();
                default -> {
                    LOGGER.warning("Unknown chorus.vector.ops=" + override + ", auto-detecting...");
                    yield autoDetectImpl();
                }
            };
        } else {
            this.implementation = autoDetectImpl();
        }
        LOGGER.info("VectorOperations selected: " + implementation.implementationName());
    }

    @NonNull VectorOperations get() {
        return implementation;
    }

    private static @NonNull VectorOperations autoDetectImpl() {
        VectorOperations vectorApi = createVectorApi();
        if (vectorApi != null) return vectorApi;

        if (isFmaSupported()) {
            return new FmaOperations();
        }
        return new ScalarOperations();
    }

    private static VectorOperations createVectorApi() {
        try {
            // Loading VectorApiOperations triggers resolution of jdk.incubator.vector classes.
            // In native image, if the module is absent, this throws NoClassDefFoundError.
            return VectorApiOperations.create();
        } catch (NoClassDefFoundError | UnsupportedOperationException e) {
            LOGGER.fine("Vector API not available: " + e.getMessage());
            return null;
        }
    }

    private static boolean isFmaSupported() {
        try {
            // Math.fma exists since Java 9, but we check if it's intrinsified
            float test = Math.fma(1.0f, 2.0f, 3.0f);
            return test == 5.0f;
        } catch (Exception e) {
            return false;
        }
    }
}
