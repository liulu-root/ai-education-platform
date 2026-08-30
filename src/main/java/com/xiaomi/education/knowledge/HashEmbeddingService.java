package com.xiaomi.education.knowledge;

import com.xiaomi.education.config.VectorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnProperty(prefix = "edu.vector", name = "embedding-provider", havingValue = "local-hash", matchIfMissing = true)
public class HashEmbeddingService implements EmbeddingService {

    private final int dimensions;

    public HashEmbeddingService(VectorProperties properties) {
        this.dimensions = Math.max(32, properties.dimensions());
    }

    @Override
    public float[] embed(String text) {
        var vector = new float[dimensions];
        var normalized = text == null ? "" : text.toLowerCase().replaceAll("\\s+", " ").trim();
        var codePoints = normalized.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            addFeature(vector, Integer.toString(codePoints[index]), 1.0f);
            if (index + 1 < codePoints.length) {
                addFeature(vector, codePoints[index] + ":" + codePoints[index + 1], 1.7f);
            }
            if (index + 2 < codePoints.length) {
                addFeature(vector, codePoints[index] + ":" + codePoints[index + 1] + ":" + codePoints[index + 2], 0.8f);
            }
        }
        normalize(vector);
        return vector;
    }

    private void addFeature(float[] vector, String feature, float weight) {
        var bytes = feature.getBytes(StandardCharsets.UTF_8);
        var hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        var slot = Math.floorMod(hash, vector.length);
        vector[slot] += (hash & 1) == 0 ? weight : -weight;
    }

    private void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return;
        }
        var scale = Math.sqrt(norm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / scale);
        }
    }
}
