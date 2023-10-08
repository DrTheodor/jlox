package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;

/**
 * Counts how many function calls there are.
 * <p>
 * Used to know if a BootstrapMethod attribute should be added to the class.
 */
public class FunctionCallCounter implements Expr.Visitor<Integer>, Stmt.Visitor<Integer> {

    public int count(Stmt.Function functionStmt) {
        return functionStmt.getBody()
                .stream()
                .mapToInt(it -> it.accept(this))
                .sum();
    }

    @Override
    public Integer visitAssignExpr(Expr.Assign expr) {
        return expr.getValue().accept(this);
    }

    @Override
    public Integer visitBinaryExpr(Expr.Binary expr) {
        return expr.getLeft().accept(this) + expr.getRight().accept(this);
    }

    @Override
    public Integer visitCallExpr(Expr.Call expr) {
        return expr.getArguments()
                .stream()
                .mapToInt(it -> it.accept(this))
                .sum()
                + expr.getCallee().accept(this)
                + 1;
    }

    @Override
    public Integer visitGetExpr(Expr.Get expr) {
        return expr.getObject().accept(this);
    }

    @Override
    public Integer visitGroupingExpr(Expr.Grouping expr) {
        return expr.getExpression().accept(this);
    }

    @Override
    public Integer visitLiteralExpr(Expr.Literal expr) {
        return 0;
    }

    @Override
    public Integer visitLogicalExpr(Expr.Logical expr) {
        return expr.getLeft().accept(this)
                + expr.getRight().accept(this);
    }

    @Override
    public Integer visitSetExpr(Expr.Set expr) {
        return expr.getObject().accept(this)
                + expr.getValue().accept(this);
    }

    @Override
    public Integer visitSuperExpr(Expr.Super expr) {
        return 0;
    }

    @Override
    public Integer visitThisExpr(Expr.This expr) {
        return 0;
    }

    @Override
    public Integer visitUnaryExpr(Expr.Unary expr) {
        return expr.getRight().accept(this);
    }

    @Override
    public Integer visitVariableExpr(Expr.Variable expr) {
        return 0;
    }

    @Override
    public Integer visitBlockStmt(Stmt.Block stmt) {
        return stmt.getStatements()
                .stream()
                .mapToInt(it -> it.accept(this))
                .sum();
    }

    @Override
    public Integer visitClassStmt(Stmt.Class stmt) {
        return 0;
    }

    @Override
    public Integer visitExpressionStmt(Stmt.Expression stmt) {
        return stmt.getExpression().accept(this);
    }

    @Override
    public Integer visitFunctionStmt(Stmt.Function stmt) {
        return 0;
    }

    @Override
    public Integer visitIfStmt(Stmt.If stmt) {
        return stmt.getCondition().accept(this)
                + stmt.getThenBranch().accept(this)
                + (stmt.getElseBranch() != null ? stmt.getElseBranch().accept(this) : 0);
    }

    @Override
    public Integer visitPrintStmt(Stmt.Print stmt) {
        return stmt.getExpression().accept(this);
    }

    @Override
    public Integer visitReturnStmt(Stmt.Return stmt) {
        return stmt.getValue() != null ? stmt.getValue().accept(this) : 0;
    }

    @Override
    public Integer visitVarStmt(Stmt.Var stmt) {
        return stmt.getInitializer() != null ? stmt.getInitializer().accept(this) : 0;
    }

    @Override
    public Integer visitWhileStmt(Stmt.While stmt) {
        return stmt.getCondition().accept(this) + stmt.getBody().accept(this);
    }
}
