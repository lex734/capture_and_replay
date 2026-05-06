package common.v1;

import java.util.Objects;

public final class FieldKey {
    private final ClassKey owner;
    private final String name;
    private final String descriptor;

    public FieldKey(ClassKey owner, String name, String descriptor) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be non-empty");
        }
        if (descriptor == null || descriptor.isEmpty()) {
            throw new IllegalArgumentException("descriptor must be non-empty");
        }
        this.name = name;
        this.descriptor = descriptor;
    }

    public static FieldKey of(String owner, String name, String descriptor) {
        return new FieldKey(ClassKey.of(owner), name, descriptor);
    }

    public ClassKey owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FieldKey)) {
            return false;
        }
        FieldKey that = (FieldKey) other;
        return owner.equals(that.owner) && name.equals(that.name) && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name, descriptor);
    }

    @Override
    public String toString() {
        return owner + "." + name + ":" + descriptor;
    }
}
