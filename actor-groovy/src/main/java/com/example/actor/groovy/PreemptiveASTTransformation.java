package com.example.actor.groovy;

import com.example.actor.ReductionBudget;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.util.List;

/** Injects a low-allocation reduction check into annotated Groovy code. */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public final class PreemptiveASTTransformation extends AbstractASTTransformation {
    private static final Object INSTRUMENTED = PreemptiveASTTransformation.class.getName() + ".instrumented";

    @Override
    public void visit(org.codehaus.groovy.ast.ASTNode[] nodes, SourceUnit source) {
        if (nodes == null || nodes.length < 2 || !(nodes[0] instanceof org.codehaus.groovy.ast.AnnotationNode annotation)
                || !(nodes[1] instanceof AnnotatedNode target)) {
            return;
        }
        int budget = budget(annotation);
        if (target instanceof MethodNode method) {
            instrument(method, budget);
        } else if (target instanceof ClassNode type) {
            for (MethodNode method : type.getMethods()) {
                instrument(method, budget);
            }
        }
    }

    private void instrument(MethodNode method, int budget) {
        if (method.getNodeMetaData(INSTRUMENTED) != null || method.isSynthetic() || method.getCode() == null) {
            return;
        }
        method.setNodeMetaData(INSTRUMENTED, Boolean.TRUE);
        Statement tick = tickStatement(budget);
        if (method.getCode() instanceof BlockStatement block) {
            block.getStatements().add(0, tick);
        }
        new LoopVisitor(budget).visitMethod(method);
    }

    private static int budget(org.codehaus.groovy.ast.AnnotationNode annotation) {
        Expression expression = annotation.getMember("budget");
        if (expression instanceof ConstantExpression constant && constant.getValue() instanceof Number number) {
            int value = number.intValue();
            if (value >= 2 && Integer.bitCount(value) == 1) {
                return value;
            }
        }
        throw new IllegalArgumentException("@Preemptive budget must be a power of two >= 2");
    }

    private static Statement tickStatement(int budget) {
        MethodCallExpression call = new MethodCallExpression(
                new ClassExpression(ClassHelper.make(ReductionBudget.class)),
                "tickCurrent",
                new ArgumentListExpression(new ConstantExpression(budget)));
        return new ExpressionStatement(call);
    }

    private static boolean isTick(Statement statement) {
        return statement instanceof ExpressionStatement expressionStatement
                && expressionStatement.getExpression() instanceof MethodCallExpression call
                && "tickCurrent".equals(call.getMethodAsString());
    }

    private static final class LoopVisitor extends ClassCodeVisitorSupport {
        private final int budget;

        private LoopVisitor(int budget) {
            this.budget = budget;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitForLoop(ForStatement loop) {
            loop.setLoopBlock(withTick(loop.getLoopBlock(), budget));
            super.visitForLoop(loop);
        }

        @Override
        public void visitWhileLoop(WhileStatement loop) {
            loop.setLoopBlock(withTick(loop.getLoopBlock(), budget));
            super.visitWhileLoop(loop);
        }

        @Override
        public void visitDoWhileLoop(DoWhileStatement loop) {
            loop.setLoopBlock(withTick(loop.getLoopBlock(), budget));
            super.visitDoWhileLoop(loop);
        }

        private static Statement withTick(Statement original, int budget) {
            if (original instanceof BlockStatement block) {
                List<Statement> statements = block.getStatements();
                if (statements.isEmpty() || !isTick(statements.get(0))) {
                    statements.add(0, tickStatement(budget));
                }
                return block;
            }
            BlockStatement replacement = new BlockStatement();
            replacement.addStatement(tickStatement(budget));
            replacement.addStatement(original);
            return replacement;
        }
    }
}
