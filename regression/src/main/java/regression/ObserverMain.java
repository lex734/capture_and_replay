package regression;

import regression.annotations.Observed;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper main class used by the regression harness instead of the target class directly.
 *
 * <p>After the target's {@code main()} returns, all {@link Observed}-annotated static fields
 * and methods are read/invoked synchronously and printed as {@code [OBS] id=value} lines.
 * Synchronous invocation (not a shutdown hook) ensures field reads are consumed by the
 * replay agent before its shutdown-hook fidelity report runs.
 *
 * <p>Usage: {@code java -Dregression.target.class=<fqcn> regression.ObserverMain [args...]}
 */
public final class ObserverMain {

    public static void main(String[] args) throws Throwable {
        String targetClassName = System.getProperty("regression.target.class");
        if (targetClassName == null || targetClassName.isEmpty()) {
            System.err.println("[ObserverMain] Missing -Dregression.target.class");
            System.exit(1);
        }

        Class<?> targetClass = Class.forName(targetClassName);
        Method mainMethod = targetClass.getMethod("main", String[].class);
        List<Member> observed = collectObserved(targetClass);

        Throwable mainException = null;
        try {
            mainMethod.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            mainException = e.getCause() != null ? e.getCause() : e;
        } finally {
            for (Member member : observed) {
                emitObservation(member);
            }
        }

        if (mainException != null) throw mainException;
    }

    private static void emitObservation(Member member) {
        try {
            String id;
            Object value;
            if (member instanceof Field) {
                Field f = (Field) member;
                id    = f.getAnnotation(Observed.class).id();
                value = f.get(null);
            } else {
                Method m = (Method) member;
                id    = m.getAnnotation(Observed.class).id();
                value = m.invoke(null);
            }
            System.out.println("[OBS] " + id + "=" + value);
        } catch (Exception e) {
            System.err.println("[ObserverMain] @Observed member threw: " + e.getMessage());
        }
    }

    private static List<Member> collectObserved(Class<?> cls) {
        List<Member> result = new ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (f.isAnnotationPresent(Observed.class)) {
                f.setAccessible(true);
                result.add(f);
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Observed.class)) {
                m.setAccessible(true);
                result.add(m);
            }
        }
        return result;
    }
}
