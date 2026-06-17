package regression.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static field or static method as an observable for regression classification.
 * The regression harness reads the field value (or invokes the method) after {@code main()}
 * returns and emits {@code [OBS] id=<value>} to stdout, where the value is obtained via
 * {@link Object#toString()} on whatever the field or method produces.
 *
 * <p>The field or method may be of any type. Multiple {@code @Observed} annotations on the
 * same class are allowed; each is matched independently by its {@code id}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Observed {
    /** Logical name for this observation, referenced by {@link Outcome#id()}. */
    String id();
}
