package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;
import com.craftinginterpreters.lox.util.RuntimeError;

import java.util.*;
import java.util.stream.Collectors;

import static com.craftinginterpreters.lox.Lox.runtimeError;
import static com.craftinginterpreters.lox.lexer.TokenType.MINUS;

public class Optimizer {

    private final CompilerResolver resolver;


    public Optimizer(CompilerResolver resolver) {
        this.resolver = resolver;
    }

    public Stmt.Function execute(Stmt.Function function, int passes) {
        return new Stmt.Function(
                function.getName(),
                function.getParams(),
                execute(function.getBody(), passes)
        );
    }

    public List<Stmt> execute(Collection<Stmt> stmt, int passes) {
        var stmtStream = stmt.stream();
        for (int i = 0; i < passes; i++) {
            var codeSimplifier = new CodeSimplifier();
            stmtStream = stmtStream
                    .map(it -> it.accept(codeSimplifier))
                    .filter(Objects::nonNull);
        }
        return stmtStream.collect(Collectors.toList());
    }

    private class CodeSimplifier implements Stmt.Visitor<Stmt>, Expr.Visitor<Expr> {
        private final Map<Token, Expr> varExprReplacements = new HashMap<>();

        @Override
        public Expr visitAssignExpr(Expr.Assign expr) {
            var value = expr.getValue().accept(this);
            var optionalVarDef = resolver.varDef(expr);
            if (optionalVarDef.isEmpty()) {
                runtimeError(new RuntimeError(expr.getName(), "Undefined variable '" + expr.getName().lexeme() + "'."));
            } else {
                var varDef = optionalVarDef.get();
                if (!varDef.isRead()) {
                    if (value.accept(new SideEffectCounter()) == 0) {
                        return null;
                    } else {
                        return value;
                    }
                }

                // TODO: copy propagation
            }

            return new Expr.Assign(expr.getName(), value);
        }

        @Override
        public Expr visitBinaryExpr(Expr.Binary expr) {
            var left = expr.getLeft();
            var right = expr.getRight();

            switch (expr.getOperator().type()) {
                case PLUS -> {
                    if (left instanceof Expr.Literal a && right instanceof Expr.Literal b) {
                        if (a.getValue() instanceof String s1 && b.getValue() instanceof String s2)
                            return new Expr.Literal(s1 + s2);
                        else if (a.getValue() instanceof Double d1 && b.getValue() instanceof Double d2)
                            return new Expr.Literal(d1 + d2);
                    } else if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 && d1 == 0) {
                        return expr.getRight();
                    } else if (right instanceof Expr.Literal b && b.getValue() instanceof Double d2 && d2 == 0) {
                        return expr.getLeft();
                    }
                }
                case MINUS -> {
                    if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                            a.getValue() instanceof Double d1 && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 - d2);
                    } else if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 && d1 == 0) {
                        return new Expr.Unary(new Token(MINUS, "-", null, expr.getOperator().line()), expr.getRight());
                    } else if (right instanceof Expr.Literal b && b.getValue() instanceof Double d2 && d2 == 0) {
                        return expr.getLeft();
                    }
                }
                case SLASH -> {
                    if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                            a.getValue() instanceof Double d1 && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 / d2);
                    }
                }
                case STAR -> {
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 * d2);
                    }
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 && d1 == 0 ||
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2 && d2 == 0) {
                        return new Expr.Literal(0.0);
                    }
                }
                case GREATER -> {
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 > d2);
                    }
                }
                case GREATER_EQUAL -> {
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 >= d2);
                    }
                }
                case LESS -> {
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 < d2);
                    }
                }
                case LESS_EQUAL -> {
                    if (left instanceof Expr.Literal a && a.getValue() instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.getValue() instanceof Double d2) {
                        return new Expr.Literal(d1 <= d2);
                    }
                }
            }

            return new Expr.Binary(
                    expr.getLeft().accept(this),
                    expr.getOperator(),
                    expr.getRight().accept(this)
            );
        }

        @Override
        public Expr visitCallExpr(Expr.Call expr) {
            return new Expr.Call(
                    expr.getCallee().accept(this),
                    expr.getParen(),
                    expr.getArguments()
                            .stream()
                            .map(it -> it.accept(this))
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Expr visitGetExpr(Expr.Get expr) {
            return new Expr.Get(expr.getObject().accept(this), expr.getName());
        }

        @Override
        public Expr visitGroupingExpr(Expr.Grouping expr) {
            if (expr.getExpression() instanceof Expr.Literal literalExpr) {
                return literalExpr;
            } else if (expr.getExpression() instanceof Expr.Grouping groupingExpr) {
                // Recursively unwrap nested groupings
                return visitGroupingExpr(groupingExpr);
            } else {
                return new Expr.Grouping(expr.getExpression().accept(this));
            }
        }

        @Override
        public Expr visitLiteralExpr(Expr.Literal expr) {
            return expr;
        }

        @Override
        public Expr visitLogicalExpr(Expr.Logical expr) {
            switch (expr.getOperator().type()) {
                case OR -> {
                    if (expr.getLeft() instanceof Expr.Literal l1 && l1.getValue() instanceof Boolean b1 &&
                            expr.getRight() instanceof Expr.Literal l2 && l2.getValue() instanceof Boolean b2) {
                        return new Expr.Literal(b1 || b2);
                    }

                    if (expr.getLeft() instanceof Expr.Literal l1 && (l1.getValue() == null || (l1.getValue() instanceof Boolean b1 && !b1))) {
                        return expr.getRight();
                    }
                }
                case AND -> {
                    if (expr.getLeft() instanceof Expr.Literal l1 && l1.getValue() instanceof Boolean b1 &&
                            expr.getRight() instanceof Expr.Literal l2 && l2.getValue() instanceof Boolean b2) {
                        return new Expr.Literal(b1 && b2);
                    }

                    if (expr.getLeft() instanceof Expr.Literal l1 && (l1.getValue() == null || l1.getValue() instanceof Boolean b1 && !b1)) {
                        return expr.getLeft();
                    }
                }
            }

            return new Expr.Logical(
                    expr.getLeft().accept(this),
                    expr.getOperator(),
                    expr.getRight().accept(this)
            );
        }

        @Override
        public Expr visitSetExpr(Expr.Set expr) {
            return new Expr.Set(
                    expr.getObject().accept(this),
                    expr.getName(),
                    expr.getValue().accept(this)
            );
        }

        @Override
        public Expr visitSuperExpr(Expr.Super expr) {
            return expr;
        }

        @Override
        public Expr visitThisExpr(Expr.This expr) {
            return expr;
        }

        @Override
        public Expr visitUnaryExpr(Expr.Unary expr) {
            return new Expr.Unary(expr.getOperator(), expr.getRight().accept(this));
        }

        @Override
        public Expr visitVariableExpr(Expr.Variable expr) {
            var varDef = resolver.varDef(expr);

            if (varDef.isEmpty()) {
                runtimeError(new RuntimeError(expr.getName(), "Undefined variable '" + expr.getName().lexeme() + "'."));
                return expr;
            } else {
                if (varExprReplacements.containsKey(varDef.get().token())) {
                    var newExpr = varExprReplacements.get(varDef.get().token());

                    if (newExpr instanceof Expr.Literal) {
                        // If variable access was replaced by a literal,
                        // then the variable is read one less time.
                        resolver.decrementReads(varDef.get());
                    }
                }
                return varExprReplacements.getOrDefault(varDef.get().token(), expr);
            }
        }

        @Override
        public Stmt visitBlockStmt(Stmt.Block stmt) {
            return new Stmt.Block(
                    stmt.getStatements()
                            .stream()
                            .map(it -> it.accept(this))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Stmt visitClassStmt(Stmt.Class stmt) {
            var varDef = resolver.varDef(stmt.getName());

            Expr superClass = null;
            if (stmt.getSuperclass() != null) {
                superClass = stmt.getSuperclass().accept(this);

                if (!(superClass instanceof Expr.Variable)) {
                    // For compatibility with Lox test suite, throw a runtime error.
                    runtimeError(new RuntimeError(stmt.getSuperclass().getName(), "Superclass must be a class."));
                    return null;
                }
            }

            if (varDef != null && !varDef.isRead() &&
                    // If superClass is not null, it can cause a side effect of a runtime error
                    // because we don't know until runtime if the variable contains a class.
                    superClass == null) {
                return null;
            }

            return new Stmt.Class(
                    stmt.getName(),
                    stmt.getSuperclass() == null ? null : (Expr.Variable) superClass,
                    stmt.getMethods()
                            .stream()
                            .map(it -> (Stmt.Function) it.accept(this))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Stmt visitExpressionStmt(Stmt.Expression stmt) {
            var expr = stmt.getExpression().accept(this);
            return expr == null ? null : new Stmt.Expression(expr);
        }

        @Override
        public Stmt visitFunctionStmt(Stmt.Function stmt) {
            var varDef = resolver.varDef(stmt.getName());
            if (varDef != null && !varDef.isRead()) {
                return null;
            }

            if (stmt instanceof Compiler.NativeFunction nativeFunction) {
                return nativeFunction;
            }

            return new Stmt.Function(
                    stmt.getName(),
                    stmt.getParams(),
                    stmt.getBody()
                            .stream()
                            .map(it -> it.accept(this))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Stmt visitIfStmt(Stmt.If stmt) {
            if (stmt.getCondition() instanceof Expr.Literal l) {
                if (l.getValue() == null) {
                    if (stmt.getElseBranch() != null)
                        return stmt.getElseBranch().accept(this);
                    else
                        return null;
                } else if (l.getValue() instanceof Boolean b) {
                    if (b) return stmt.getThenBranch().accept(this);
                    else if (stmt.getElseBranch() != null) return stmt.getElseBranch().accept(this);
                    else return null;
                } else {
                    return stmt.getThenBranch().accept(this);
                }
            }

            return new Stmt.If(
                    stmt.getCondition().accept(this),
                    stmt.getThenBranch().accept(this),
                    stmt.getElseBranch() == null ? null : stmt.getElseBranch().accept(this)
            );
        }

        @Override
        public Stmt visitPrintStmt(Stmt.Print stmt) {
            return new Stmt.Print(stmt.getExpression().accept(this));
        }

        @Override
        public Stmt visitReturnStmt(Stmt.Return stmt) {
            return stmt.getValue() != null ? new Stmt.Return(stmt.getKeyword(), stmt.getValue().accept(this)) : new Stmt.Return(stmt.getKeyword(), null);
        }

        @Override
        public Stmt visitVarStmt(Stmt.Var stmt) {
            var varDef = resolver.varDef(stmt.getName());

            if (varDef != null && !varDef.isRead()) {
                // The variable is never read but if it has an initializer,
                // there could be side effects.
                if (stmt.getInitializer() != null) {
                    if (stmt.getInitializer().accept(new SideEffectCounter()) == 0) {
                        return null;
                    } else {
                        // potential side effects so keep the initializer
                        return new Stmt.Expression(stmt.getInitializer());
                    }
                } else {
                    return null;
                }
            }

            if (stmt.getInitializer() != null) {
                var expr = stmt.getInitializer().accept(this);
                if (expr instanceof Expr.Literal && varDef.isFinal()) {
                    varExprReplacements.put(varDef.token(), expr);
                    return null;
                }
                return new Stmt.Var(stmt.getName(), expr);
            }

            return new Stmt.Var(stmt.getName(), null);
        }

        @Override
        public Stmt visitWhileStmt(Stmt.While stmt) {
            return new Stmt.While(
                    stmt.getCondition().accept(this),
                    stmt.getBody().accept(this)
            );
        }
    }
}
