package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;


public class StackSizeComputer implements Stmt.Visitor<Integer>, Expr.Visitor<Integer> {
    @Override
    public Integer visitAssignExpr(Expr.Assign expr) {
        return compute(expr.getValue().accept(this), 1, 1);
    }

    @Override
    public Integer visitBinaryExpr(Expr.Binary expr) {
        return compute(expr.getLeft().accept(this) + expr.getRight().accept(this), 2, 1);
    }

    @Override
    public Integer visitCallExpr(Expr.Call expr) {
        return compute(expr.getArguments().stream().map(it -> it.accept(this))
                        .mapToInt(Integer::intValue).sum() + expr.getCallee().accept(this),
                expr.getArguments().size() + 1, 1
        );
    }

    @Override
    public Integer visitGetExpr(Expr.Get expr) {
        return compute(expr.getObject().accept(this), 1, 1);
    }

    @Override
    public Integer visitGroupingExpr(Expr.Grouping expr) {
        return compute(expr.getExpression().accept(this), 0, 0);
    }

    @Override
    public Integer visitLiteralExpr(Expr.Literal expr) {
        return compute(0, 0, 1);
    }

    @Override
    public Integer visitLogicalExpr(Expr.Logical expr) {
        return compute(expr.getLeft().accept(this) + expr.getRight().accept(this), 2, 1);
    }

    @Override
    public Integer visitSetExpr(Expr.Set expr) {
        return compute(expr.getValue().accept(this), expr.getObject().accept(this),1);
    }

    @Override
    public Integer visitSuperExpr(Expr.Super expr) {
        return compute(0, 0, 1);
    }

    @Override
    public Integer visitThisExpr(Expr.This expr) {
        return compute(0, 0, 1);
    }

    @Override
    public Integer visitUnaryExpr(Expr.Unary expr) {
        return compute(expr.getRight().accept(this), 1, 1);
    }

    @Override
    public Integer visitVariableExpr(Expr.Variable expr) {
        return compute(0, 0, 1);
    }

    @Override
    public Integer visitBlockStmt(Stmt.Block stmt) {
        return this.compute(stmt.getStatements().stream().map(it ->
                        it.accept(this)
                ).mapToInt(Integer::intValue).sum(), 0, 0
        );
    }

    @Override
    public Integer visitClassStmt(Stmt.Class stmt) {
        return 0;
    }

    @Override
    public Integer visitExpressionStmt(Stmt.Expression stmt) {
        return this.compute(stmt.getExpression().accept(this), 0, 0);
    }

    @Override
    public Integer visitFunctionStmt(Stmt.Function stmt) {
        return 0;
    }

    @Override
    public Integer visitIfStmt(Stmt.If stmt) {
        return compute(
            stmt.getCondition().accept(this) + stmt.getThenBranch().accept(this)
                    + (stmt.getElseBranch() != null ? stmt.getElseBranch().accept(this) : 0),
                1, 0
        );
    }

    @Override
    public Integer visitPrintStmt(Stmt.Print stmt) {
        return compute(stmt.getExpression().accept(this), 1, 0);
    }

    @Override
    public Integer visitReturnStmt(Stmt.Return stmt) {
        return 0;
    }

    @Override
    public Integer visitVarStmt(Stmt.Var stmt) {
        return 0;
    }

    @Override
    public Integer visitWhileStmt(Stmt.While stmt) {
        return 0;
    }

    private int compute(int before, int consumes, int produces) {
        return before - consumes + produces;
    }
}
