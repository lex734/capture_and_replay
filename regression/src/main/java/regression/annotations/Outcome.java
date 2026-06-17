package regression.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an expected outcome for a specific {@link Observed} variable.
 *
 * <p>{@code id} references the {@link Observed#id()} of the variable being matched.
 * {@code value} holds regex patterns matched against the observed value's {@code toString()}.
 * Outcomes are evaluated in declaration order for each observation; the first pattern
 * match wins. Unmatched observations default to {@link OutcomeExpectation#INTERESTING}.
 *
 * <p>Exception and timeout exits are automatically classified
 * {@link OutcomeExpectation#FORBIDDEN}, bypassing pattern matching entirely.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Outcomes.class)
public @interface Outcome {
    /** The {@link Observed#id()} this outcome applies to. */
    String id();
    /** Regex patterns matched against the observed value (toString). Any match selects this outcome. */
    String[] value();
    OutcomeExpectation expect();
    String desc() default "";
}

