package bench;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a step that changes the app in a way no undo can take back (a confirmed
 * transfer). The traversal executor runs such branches last among their siblings,
 * and replays the prefix from a fresh launch if anything still has to run after them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Irreversible { }
