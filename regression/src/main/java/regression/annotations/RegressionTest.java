package regression.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a regression test target.
 * The class must have a {@code public static void main(String[])} entry point
 * and exactly one {@link Observed}-annotated method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegressionTest {
    int runs() default 100;
    long captureTimeoutMs() default 10_000;
    long replayTimeoutMs() default 15_000;
}
