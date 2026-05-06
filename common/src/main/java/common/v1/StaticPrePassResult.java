package common.v1;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class StaticPrePassResult {
    private final Set<ConcurrentEntryRoot> concurrentEntryRoots;
    private final Set<ClassKey> concurrentReachableClasses;
    private final Set<MethodKey> tierBEligibleMethods;
    private final Set<ClassKey> tierBEligibleClasses;

    public StaticPrePassResult(
            Set<ConcurrentEntryRoot> concurrentEntryRoots,
            Set<ClassKey> concurrentReachableClasses,
            Set<MethodKey> tierBEligibleMethods,
            Set<ClassKey> tierBEligibleClasses) {
        this.concurrentEntryRoots = unmodifiableCopy(concurrentEntryRoots);
        this.concurrentReachableClasses = unmodifiableCopy(concurrentReachableClasses);
        this.tierBEligibleMethods = unmodifiableCopy(tierBEligibleMethods);
        this.tierBEligibleClasses = unmodifiableCopy(tierBEligibleClasses);
    }

    public static StaticPrePassResult empty() {
        return new StaticPrePassResult(Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet());
    }

    private static <T> Set<T> unmodifiableCopy(Set<T> input) {
        Objects.requireNonNull(input, "input");
        return Collections.unmodifiableSet(new LinkedHashSet<>(input));
    }

    public Set<ConcurrentEntryRoot> concurrentEntryRoots() {
        return concurrentEntryRoots;
    }

    public Set<ClassKey> concurrentReachableClasses() {
        return concurrentReachableClasses;
    }

    public Set<MethodKey> tierBEligibleMethods() {
        return tierBEligibleMethods;
    }

    public Set<ClassKey> tierBEligibleClasses() {
        return tierBEligibleClasses;
    }

    public StaticPrePassResult merge(StaticPrePassResult other) {
        if (other == null) {
            return this;
        }
        LinkedHashSet<ConcurrentEntryRoot> roots = new LinkedHashSet<>(concurrentEntryRoots);
        LinkedHashSet<ClassKey> reachable = new LinkedHashSet<>(concurrentReachableClasses);
        LinkedHashSet<MethodKey> methods = new LinkedHashSet<>(tierBEligibleMethods);
        LinkedHashSet<ClassKey> classes = new LinkedHashSet<>(tierBEligibleClasses);
        roots.addAll(other.concurrentEntryRoots);
        reachable.addAll(other.concurrentReachableClasses);
        methods.addAll(other.tierBEligibleMethods);
        classes.addAll(other.tierBEligibleClasses);
        return new StaticPrePassResult(roots, reachable, methods, classes);
    }
}
