package bench.traversal;

import bench.Irreversible;
import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mode B's view of the glue: the same Cucumber-annotated methods mode A runs,
 * matched with the same Cucumber expressions, called directly.
 */
public final class StepLibrary {

    /** How a step behaves when the executor has to move between branches. */
    public enum Kind {
        /** A {@code @Then}: reads state, doesn't change it. */
        CHECK,
        /** Changes state; restorable only if it registered an undo. */
        ACTION,
        /** {@link Irreversible}: changes state for good. */
        IRREVERSIBLE
    }

    private record Def(Expression expression, Method method, Object instance, Kind kind) { }

    private final List<Def> defs = new ArrayList<>();

    public StepLibrary(Class<?>... glue) {
        ExpressionFactory factory = new ExpressionFactory(new ParameterTypeRegistry(Locale.ENGLISH));
        for (Class<?> c : glue) {
            Object instance;
            try {
                instance = c.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("can't create glue " + c, e);
            }
            for (Method m : c.getDeclaredMethods()) {
                String pattern = null;
                Kind kind = Kind.ACTION;
                if (m.isAnnotationPresent(Given.class)) pattern = m.getAnnotation(Given.class).value();
                if (m.isAnnotationPresent(When.class)) pattern = m.getAnnotation(When.class).value();
                if (m.isAnnotationPresent(Then.class)) { pattern = m.getAnnotation(Then.class).value(); kind = Kind.CHECK; }
                if (pattern == null) continue;
                if (m.isAnnotationPresent(Irreversible.class)) kind = Kind.IRREVERSIBLE;
                defs.add(new Def(factory.createExpression(pattern), m, instance, kind));
            }
        }
    }

    public Kind kind(String text) {
        return find(text).def.kind();
    }

    public void run(String text) throws Throwable {
        Match m = find(text);
        Object[] args = new Object[m.args.size()];
        for (int i = 0; i < args.length; i++) args[i] = m.args.get(i).getValue();
        try {
            m.def.method().invoke(m.def.instance(), args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private record Match(Def def, List<Argument<?>> args) { }

    private Match find(String text) {
        Match found = null;
        for (Def d : defs) {
            List<Argument<?>> args = d.expression().match(text, d.method().getGenericParameterTypes());
            if (args == null) continue;
            if (found != null) throw new IllegalStateException("ambiguous step: '" + text + "'");
            found = new Match(d, args);
        }
        if (found == null) throw new IllegalStateException("undefined step: '" + text + "'");
        return found;
    }
}
