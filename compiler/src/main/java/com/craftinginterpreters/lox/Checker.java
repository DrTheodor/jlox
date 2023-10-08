package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.craftinginterpreters.lox.Lox.error;

public class Checker implements Stmt.Visitor<Void>, Expr.Visitor<Void> {

    private ClassType currentClassType = ClassType.NONE;
    private FunctionType currentFunctionType = FunctionType.NONE;

    public void execute(Collection<Stmt> statements) {
        statements.forEach(it -> it.accept(this));
    }

    private void checkFunction(Stmt.Function function, FunctionType type) {
        FunctionType enclosingFunctionType = currentFunctionType;
        this.currentFunctionType = type;

        function.getBody().forEach(it -> it.accept(this));
        this.currentFunctionType = enclosingFunctionType;
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        expr.getValue().accept(this);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        expr.getLeft().accept(this);
        expr.getRight().accept(this);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        expr.getCallee().accept(this);
        expr.getArguments().forEach(it -> it.accept(this));
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        expr.getObject().accept(this);
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        expr.getExpression().accept(this);
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        expr.getLeft().accept(this);
        expr.getRight().accept(this);
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        expr.getValue().accept(this);
        expr.getObject().accept(this);
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {
        if (this.currentClassType == ClassType.NONE) {
            error(expr.getKeyword(), "Can't use 'super' outside of a class.");
            return null;
        }

        if (this.currentClassType != ClassType.SUBCLASS) {
            error(expr.getKeyword(), "Can't use 'super' in a class with no superclass.");
            return null;
        }

        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {
        if (this.currentClassType == ClassType.NONE) {
            error(expr.getKeyword(), "Can't use 'this' outside of a class.");
        }

        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        expr.getRight().accept(this);
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        return null;
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        stmt.getStatements().forEach(it -> it.accept(this));
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        ClassType enclosingClass = this.currentClassType;
        this.currentClassType = ClassType.CLASS;

        if (stmt.getSuperclass() != null) {
            this.currentClassType = ClassType.SUBCLASS;
            if (Objects.equals(stmt.getName().lexeme(), stmt.getSuperclass().getName().lexeme())) {
                error(stmt.getSuperclass().getName(), "A class can't inherit from itself.");
            }

            stmt.getSuperclass().accept(this);
        }

        stmt.getMethods().forEach(it -> this.checkFunction(it, it.getName().lexeme().equals("init")
                ? FunctionType.INIT : FunctionType.METHOD)
        );

        this.currentClassType = enclosingClass;
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        stmt.getExpression().accept(this);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        this.checkFunction(stmt, FunctionType.FUNCTION);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        stmt.getCondition().accept(this);
        stmt.getThenBranch().accept(this);

        if (stmt.getElseBranch() != null)
            stmt.getElseBranch().accept(this);

        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        stmt.getExpression().accept(this);
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return returnStmt) {
        if (returnStmt.getValue() != null) {
            if (this.currentFunctionType == FunctionType.NONE) {
                error(returnStmt.getKeyword(), "Can't return from top-level code.");
                return null;
            }

            if (this.currentFunctionType == FunctionType.INIT) {
                error(returnStmt.getKeyword(), "Can't return a value from an initializer.");
                return null;
            }

            returnStmt.getValue().accept(this);
        }

        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        if (stmt.getInitializer() != null)
            stmt.getInitializer().accept(this);

        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        stmt.getCondition().accept(this);
        stmt.getBody().accept(this);

        return null;
    }

    private enum ClassType {
        NONE,
        CLASS,
        SUBCLASS
    }

    private enum FunctionType {
        NONE,
        FUNCTION,
        METHOD,
        INIT
    }
}
