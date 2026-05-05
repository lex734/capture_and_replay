package replay;

import common.BinarySchema;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Conservative mandatory static applicability check.
 *
 * <p>This first pass only analyzes methods referenced by the distilled schedule
 * and rejects on obvious inverse synchronization relationships.
 */
public final class ScheduleStaticAnalyzer {
    public static final Path DEFAULT_SCHEDULE = Path.of("schedule-boundaries.tsv");
    public static final Path DEFAULT_CLASSES_ROOT = Path.of(".");

    private ScheduleStaticAnalyzer() {}

    public enum Status {
        APPLICABLE,
        STATICALLY_INAPPLICABLE
    }

    public static final class Result {
        public final Status status;
        public final List<String> reasons;

        private Result(Status status, List<String> reasons) {
            this.status = status;
            this.reasons = reasons;
        }
    }

    private static final class MethodKey {
        final String className;
        final String methodName;

        MethodKey(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MethodKey)) return false;
            MethodKey other = (MethodKey) o;
            return className.equals(other.className) && methodName.equals(other.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className, methodName);
        }
    }

    private static final class MethodScan {
        final Set<Integer> events = new HashSet<>();
        final Set<MethodKey> helperCallees = new HashSet<>();
    }

    private interface ClassSource extends AutoCloseable {
        boolean hasClass(String className) throws IOException;
        InputStream openClass(String className) throws IOException;

        @Override
        void close() throws IOException;
    }

    private static final class DirectoryClassSource implements ClassSource {
        private final Path classesRoot;

        DirectoryClassSource(Path classesRoot) {
            this.classesRoot = classesRoot;
        }

        @Override
        public boolean hasClass(String className) {
            return Files.exists(classFile(className));
        }

        @Override
        public InputStream openClass(String className) throws IOException {
            return Files.newInputStream(classFile(className));
        }

        private Path classFile(String className) {
            return classesRoot.resolve(className.replace('.', '/') + ".class");
        }

        @Override
        public void close() {}
    }

    private static final class JarClassSource implements ClassSource {
        private final JarFile jar;

        JarClassSource(Path jarPath) throws IOException {
            this.jar = new JarFile(jarPath.toFile());
        }

        @Override
        public boolean hasClass(String className) {
            return jar.getJarEntry(classEntryName(className)) != null;
        }

        @Override
        public InputStream openClass(String className) throws IOException {
            JarEntry entry = jar.getJarEntry(classEntryName(className));
            if (entry == null) {
                throw new IOException("Missing class entry " + classEntryName(className));
            }
            return jar.getInputStream(entry);
        }

        private static String classEntryName(String className) {
            return className.replace('.', '/') + ".class";
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    public static Result analyze(Path schedulePath, Path classesRoot) {
        List<ScheduleDistiller.ScheduleEntry> schedule = ScheduleArtifacts.loadSchedule(schedulePath);
        Map<MethodKey, Set<Integer>> expected = new HashMap<>();
        for (ScheduleDistiller.ScheduleEntry entry : schedule) {
            expected.computeIfAbsent(new MethodKey(entry.className, entry.methodName), k -> new HashSet<>())
                    .add(entry.eventType);
        }

        try (ClassSource classSource = openClassSource(classesRoot)) {
            List<String> reasons = new ArrayList<>();
            for (Map.Entry<MethodKey, Set<Integer>> item : expected.entrySet()) {
                MethodKey key = item.getKey();
                Set<Integer> actual = inspectReachableBoundaries(classSource, key);
                for (int expectedEvent : item.getValue()) {
                    if (actual.contains(expectedEvent)) {
                        continue;
                    }
                    for (int inverse : inverseEventTypes(expectedEvent)) {
                        if (actual.contains(inverse)) {
                            reasons.add("inverse boundary reachable from " + key.className + "." + key.methodName
                                    + ": expected=" + eventName(expectedEvent)
                                    + " inverse=" + eventName(inverse));
                        }
                    }
                }
            }
            return reasons.isEmpty()
                    ? new Result(Status.APPLICABLE, List.of())
                    : new Result(Status.STATICALLY_INAPPLICABLE, reasons);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to analyze classes from " + classesRoot, e);
        }
    }

    private static ClassSource openClassSource(Path classesPath) throws IOException {
        if (Files.isDirectory(classesPath)) {
            return new DirectoryClassSource(classesPath);
        }
        String fileName = classesPath.getFileName() == null ? "" : classesPath.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return new JarClassSource(classesPath);
        }
        throw new IOException("Unsupported class path for static analysis: " + classesPath
                + " (expected classes directory or .jar)");
    }

    private static Set<Integer> inspectReachableBoundaries(ClassSource classSource, MethodKey seed) {
        ArrayDeque<MethodKey> worklist = new ArrayDeque<>();
        Set<MethodKey> visited = new HashSet<>();
        Set<Integer> events = new HashSet<>();
        worklist.add(seed);
        while (!worklist.isEmpty()) {
            MethodKey key = worklist.removeFirst();
            if (!visited.add(key)) continue;

            MethodScan scan = inspectMethod(classSource, key);
            events.addAll(scan.events);
            for (MethodKey callee : scan.helperCallees) {
                if (!visited.contains(callee)) {
                    worklist.addLast(callee);
                }
            }
        }
        return events;
    }

    private static MethodScan inspectMethod(ClassSource classSource, MethodKey key) {
        try {
            if (!classSource.hasClass(key.className)) {
                return new MethodScan();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve class " + key.className, e);
        }
        try (InputStream in = classSource.openClass(key.className)) {
            ClassReader reader = new ClassReader(in);
            MethodScan scan = new MethodScan();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    if (!name.equals(key.methodName)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.MONITORENTER) scan.events.add(BinarySchema.Event.MONITOR_ENTER);
                            if (opcode == Opcodes.MONITOREXIT) scan.events.add(BinarySchema.Event.MONITOR_EXIT);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                    boolean isInterface) {
                            classifyMethodBoundary(owner, name, descriptor, scan.events);
                            maybeRecordHelperCall(classSource, owner, name, scan.helperCallees);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (key.methodName.equals("<clinit>")) {
                scan.events.add(BinarySchema.Event.CLASS_INIT_BEGIN);
                scan.events.add(BinarySchema.Event.CLASS_INIT_END);
            }
            return scan;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect " + key.className, e);
        }
    }

    private static void maybeRecordHelperCall(ClassSource classSource, String owner, String name,
                                              Set<MethodKey> helperCallees) {
        if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/")
                || owner.startsWith("sun/")) {
            return;
        }
        String className = owner.replace('/', '.');
        try {
            if (classSource.hasClass(className)) {
                helperCallees.add(new MethodKey(className, name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect helper class " + className, e);
        }
    }

    private static void classifyMethodBoundary(String owner, String name, String descriptor, Set<Integer> events) {
        if (owner.equals("java/lang/Thread")) {
            if (name.equals("start")) events.add(BinarySchema.Event.THREAD_START);
            else if (name.equals("join")) {
                events.add(descriptor.equals("()V")
                        ? BinarySchema.Event.THREAD_JOIN
                        : BinarySchema.Event.THREAD_JOIN_TIMEOUT);
            } else if (name.equals("sleep")) events.add(BinarySchema.Event.THREAD_SLEEP);
            else if (name.equals("yield")) events.add(BinarySchema.Event.THREAD_YIELD);
            else if (name.equals("interrupt")) events.add(BinarySchema.Event.THREAD_INTERRUPT);
            else if (name.equals("interrupted") || name.equals("isInterrupted")) {
                events.add(BinarySchema.Event.THREAD_INTERRUPT_CHECK);
            }
            return;
        }
        if (owner.equals("java/lang/Object")) {
            if (name.equals("wait")) events.add(BinarySchema.Event.THREAD_WAIT);
            else if (name.equals("notify")) events.add(BinarySchema.Event.THREAD_NOTIFY);
            else if (name.equals("notifyAll")) events.add(BinarySchema.Event.THREAD_NOTIFY_ALL);
            return;
        }
        if (owner.equals("java/util/concurrent/locks/LockSupport")) {
            if (name.equals("park") || name.equals("parkNanos") || name.equals("parkUntil")) {
                events.add(BinarySchema.Event.THREAD_PARK);
            } else if (name.equals("unpark")) {
                events.add(BinarySchema.Event.THREAD_UNPARK);
            }
            return;
        }
        if (owner.equals("java/util/concurrent/locks/Condition")) {
            if (name.startsWith("await")) events.add(BinarySchema.Event.THREAD_WAIT);
            else if (name.equals("signal")) events.add(BinarySchema.Event.THREAD_NOTIFY);
            else if (name.equals("signalAll")) events.add(BinarySchema.Event.THREAD_NOTIFY_ALL);
            return;
        }
        if (owner.equals("java/util/concurrent/locks/ReentrantLock")
                || owner.equals("java/util/concurrent/locks/Lock")) {
            if (name.equals("lock")) events.add(BinarySchema.Event.MONITOR_ENTER);
            else if (name.equals("unlock")) events.add(BinarySchema.Event.MONITOR_EXIT);
        }
    }

    private static Set<Integer> inverseEventTypes(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER:
                return Set.of(BinarySchema.Event.MONITOR_EXIT);
            case BinarySchema.Event.MONITOR_EXIT:
                return Set.of(BinarySchema.Event.MONITOR_ENTER);
            case BinarySchema.Event.THREAD_START:
                return Set.of(BinarySchema.Event.THREAD_JOIN, BinarySchema.Event.THREAD_JOIN_TIMEOUT);
            case BinarySchema.Event.THREAD_JOIN:
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT:
                return Set.of(BinarySchema.Event.THREAD_START);
            case BinarySchema.Event.THREAD_WAIT:
                return Set.of(BinarySchema.Event.THREAD_NOTIFY, BinarySchema.Event.THREAD_NOTIFY_ALL,
                        BinarySchema.Event.THREAD_UNPARK);
            case BinarySchema.Event.THREAD_NOTIFY:
            case BinarySchema.Event.THREAD_NOTIFY_ALL:
                return Set.of(BinarySchema.Event.THREAD_WAIT);
            case BinarySchema.Event.THREAD_PARK:
                return Set.of(BinarySchema.Event.THREAD_UNPARK);
            case BinarySchema.Event.THREAD_UNPARK:
                return Set.of(BinarySchema.Event.THREAD_PARK);
            case BinarySchema.Event.THREAD_INTERRUPT:
                return Set.of(BinarySchema.Event.THREAD_INTERRUPT_CHECK);
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK:
                return Set.of(BinarySchema.Event.THREAD_INTERRUPT);
            default:
                return Set.of();
        }
    }

    private static String eventName(int eventType) {
        switch (eventType) {
            case BinarySchema.Event.MONITOR_ENTER: return "MONITOR_ENTER";
            case BinarySchema.Event.MONITOR_EXIT: return "MONITOR_EXIT";
            case BinarySchema.Event.THREAD_PARK: return "THREAD_PARK";
            case BinarySchema.Event.THREAD_UNPARK: return "THREAD_UNPARK";
            case BinarySchema.Event.THREAD_START: return "THREAD_START";
            case BinarySchema.Event.THREAD_JOIN: return "THREAD_JOIN";
            case BinarySchema.Event.THREAD_INTERRUPT: return "THREAD_INTERRUPT";
            case BinarySchema.Event.THREAD_SLEEP: return "THREAD_SLEEP";
            case BinarySchema.Event.THREAD_WAKEUP: return "THREAD_WAKEUP";
            case BinarySchema.Event.THREAD_YIELD: return "THREAD_YIELD";
            case BinarySchema.Event.THREAD_WAIT: return "THREAD_WAIT";
            case BinarySchema.Event.THREAD_NOTIFY: return "THREAD_NOTIFY";
            case BinarySchema.Event.THREAD_NOTIFY_ALL: return "THREAD_NOTIFY_ALL";
            case BinarySchema.Event.THREAD_JOIN_TIMEOUT: return "THREAD_JOIN_TIMEOUT";
            case BinarySchema.Event.THREAD_INTERRUPT_CHECK: return "THREAD_INTERRUPT_CHECK";
            case BinarySchema.Event.ATOMIC_READ: return "ATOMIC_READ";
            case BinarySchema.Event.ATOMIC_WRITE: return "ATOMIC_WRITE";
            case BinarySchema.Event.ATOMIC_RMW: return "ATOMIC_RMW";
            case BinarySchema.Event.CLASS_INIT_BEGIN: return "CLASS_INIT_BEGIN";
            case BinarySchema.Event.CLASS_INIT_END: return "CLASS_INIT_END";
            case BinarySchema.Event.EXCEPTION_THROW: return "EXCEPTION_THROW";
            case BinarySchema.Event.ATOMIC_CAS: return "ATOMIC_CAS";
            case BinarySchema.Event.NONDETERMINISTIC_INT: return "NONDETERMINISTIC_INT";
            case BinarySchema.Event.NONDETERMINISTIC_LONG: return "NONDETERMINISTIC_LONG";
            case BinarySchema.Event.FIELD_READ: return "FIELD_READ";
            case BinarySchema.Event.FIELD_WRITE: return "FIELD_WRITE";
            case BinarySchema.Event.ARRAY_READ: return "ARRAY_READ";
            case BinarySchema.Event.ARRAY_WRITE: return "ARRAY_WRITE";
            default: return "EVENT_" + eventType;
        }
    }

    public static void main(String[] args) {
        Path schedule = args.length > 0 ? Path.of(args[0]) : DEFAULT_SCHEDULE;
        Path classesRoot = args.length > 1 ? Path.of(args[1]) : DEFAULT_CLASSES_ROOT;
        Result result = analyze(schedule, classesRoot);
        System.out.println("status=" + result.status);
        for (String reason : result.reasons) {
            System.out.println("reason=" + reason);
        }
        if (result.status != Status.APPLICABLE) {
            System.exit(2);
        }
    }
}
