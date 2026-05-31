package common;

import java.util.Objects;

public final class ClassKey {
    private final String internalName;

    public ClassKey(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            throw new IllegalArgumentException("internalName must be non-empty");
        }
        this.internalName = internalName;
    }

    public String internalName() {
        return internalName;
    }

    public static ClassKey of(String internalName) {
        return new ClassKey(internalName);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ClassKey)) {
            return false;
        }
        return internalName.equals(((ClassKey) other).internalName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(internalName);
    }

    @Override
    public String toString() {
        return internalName;
    }
}
