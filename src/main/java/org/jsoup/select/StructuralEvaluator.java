package org.jsoup.select;

import org.jsoup.internal.Functions;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.NodeIterator;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Base structural evaluator.
 */
public abstract class StructuralEvaluator extends Evaluator {
    protected final Evaluator evaluator;

    protected StructuralEvaluator(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    // Memoize inner matches, to save repeated re-evaluations of parent, sibling etc.
    // root + element: Boolean matches. ThreadLocal in case the Evaluator is compiled then reused across multi threads
    protected final ThreadLocal<IdentityHashMap<Element, IdentityHashMap<Element, Boolean>>>
        threadMemo = ThreadLocal.withInitial(IdentityHashMap::new);

    protected boolean memoMatches(final Element root, final Element element) {
        Map<Element, IdentityHashMap<Element, Boolean>> rootMemo = threadMemo.get();
        Map<Element, Boolean> memo = rootMemo.computeIfAbsent(root, Functions.identityMapFunction());
        return memo.computeIfAbsent(element, key -> evaluator.matches(root, key));
    }

    @Override protected void reset() {
        threadMemo.get().clear();
        super.reset();
    }

    public static final class Root extends Evaluator {
        @Override
        public boolean matches(Element root, Element element) {
            return root == element;
        }

        @Override protected int cost() {
            return 1;
        }

        @Override public String toString() {
            return "";
        }
    }

    public static final class Has extends StructuralEvaluator {
        static final ThreadLocal<NodeIterator<Element>> ThreadElementIter =
            ThreadLocal.withInitial(() -> new NodeIterator<>(new Element("html"), Element.class));
        // the element here is just a placeholder so this can be final - gets set in restart()

        public Has(Evaluator evaluator) {
            super(evaluator);
        }

        @Override public boolean matches(Element root, Element element) {
            // for :has, we only want to match children (or below), not the input element. And we want to minimize GCs
            NodeIterator<Element> it = ThreadElementIter.get();

            it.restart(element);
            while (it.hasNext()) {
                Element el = it.next();
                if (el == element) continue; // don't match self, only descendants
                if (evaluator.matches(element, el))
                    return true;
            }
            return false;
        }

        @Override protected int cost() {
            return 10 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":has(%s)", evaluator);
        }
    }

    /** Implements the :is(sub-query) pseudo-selector */
    public static final class Is extends StructuralEvaluator {
        public Is(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return evaluator.matches(root, element);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":is(%s)", evaluator);
        }
    }

    public static final class Not extends StructuralEvaluator {
        public Not(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return !memoMatches(root, element);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":not(%s)", evaluator);
        }
    }

    public static final class Parent extends StructuralEvaluator {
        public Parent(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        public boolean matches(Element root, Element element) {
            if (root == element)
                return false;

            Element parent = element.parent();
            while (parent != null) {
                if (memoMatches(root, parent))
                    return true;
                if (parent == root)
                    break;
                parent = parent.parent();
            }
            return false;
        }

        @Override protected int cost() {
            return 2 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format("%s ", evaluator);
        }
    }

    /**
     Holds a list of evaluators for one > two > three immediate parent matches, and the final direct evaluator under
     test. To match, these are effectively ANDed together, starting from the last, matching up to the first.
     */
    public static final class ImmediateParentRun extends Evaluator {
        final ArrayList<Evaluator> evaluators = new ArrayList<>();
        int cost = 2;

        public ImmediateParentRun(Evaluator evaluator) {
            evaluators.add(evaluator);
            cost += evaluator.cost();
        }

        void add(Evaluator evaluator) {
            evaluators.add(evaluator);
            cost += evaluator.cost();
        }

        @Override
        public boolean matches(Element root, Element element) {
            if (element == root)
                return false; // cannot match as the second eval (first parent test) would be above the root

            for (int i = evaluators.size() - 1; i >= 0; --i) {
                if (element == null)
                    return false;
                Evaluator eval = evaluators.get(i);
                if (!eval.matches(root, element))
                    return false;
                element = element.parent();
            }
            return true;
        }

        @Override protected int cost() {
            return cost;
        }

        @Override
        public String toString() {
            return StringUtil.join(evaluators, " > ");
        }
    }

    public static final class PreviousSibling extends StructuralEvaluator {
        public PreviousSibling(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        public boolean matches(Element root, Element element) {
            if (root == element) return false;

            Element sibling = element.firstElementSibling();
            while (sibling != null) {
                if (sibling == element) break;
                if (memoMatches(root, sibling)) return true;
                sibling = sibling.nextElementSibling();
            }

            return false;
        }

        @Override protected int cost() {
            return 3 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format("%s ~ ", evaluator);
        }
    }

    public static final class ImmediatePreviousSibling extends StructuralEvaluator {
        public ImmediatePreviousSibling(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        public boolean matches(Element root, Element element) {
            if (root == element)
                return false;

            Element prev = element.previousElementSibling();
            return prev != null && memoMatches(root, prev);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format("%s + ", evaluator);
        }
    }
}
