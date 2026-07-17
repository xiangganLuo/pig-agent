package io.pigagent.core.memory.search;

/** Small vector helpers (L2 normalization + cosine) for the vector layer of hybrid memory search. */
public final class Vectors {

    private Vectors() {
    }

    /** L2-normalize {@code v} in place and return it (a zero vector is returned unchanged). */
    public static float[] l2normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    /** Cosine similarity in {@code [-1, 1]} (0 when either vector is null/empty/zero or dims differ). */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
