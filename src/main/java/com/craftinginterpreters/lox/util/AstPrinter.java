package com.craftinginterpreters.lox.util;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;

import java.util.List;

public class AstPrinter implements Expr.Visitor<String>, Stmt.Visitor<String> {

    public String print(Expr expr) {
        return expr.accept(this);
    }

    public String print(Stmt stmt) {
        return stmt.accept(this);
    }

    @Override
    public String visitBlockStmt(Stmt.Block stmt) {
        StringBuilder builder = new StringBuilder();
        builder.append("(block ");

        for (Stmt statement : stmt.getStatements()) {
            builder.append(statement.accept(this));
        }

        builder.append(")");
        return builder.toString();
    }

    @Override
    public String visitClassStmt(Stmt.Class stmt) {
        StringBuilder builder = new StringBuilder();
        builder.append("(class ").append(stmt.getName().lexeme());

        if (stmt.getSuperclass() != null) {
            builder.append(" < ").append(print(stmt.getSuperclass()));
        }

        for (Stmt.Function method : stmt.getMethods()) {
            builder.append(" ").append(print(method));
        }

        builder.append(")");
        return builder.toString();
    }

    @Override
    public String visitExpressionStmt(Stmt.Expression stmt) {
        return parenthesize(";", stmt.getExpression());
    }

    @Override
    public String visitFunctionStmt(Stmt.Function stmt) {
        StringBuilder builder = new StringBuilder().append("(fun ")
                .append(stmt.getName().lexeme()).append("(");

        Token first = null;
        for (Token param : stmt.getParams()) {
            if (first == null)
                first = param;

            if (param != first)
                builder.append(" ");

            builder.append(param.lexeme());
        }

        builder.append(") ");
        for (Stmt body : stmt.getBody()) {
            builder.append(body.accept(this));
        }

        builder.append(")");
        return builder.toString();
    }

    @Override
    public String visitIfStmt(Stmt.If stmt) {
        if (stmt.getElseBranch() == null) {
            return parenthesize2("if", stmt.getCondition(), stmt.getThenBranch());
        }

        return parenthesize2("if-else", stmt.getCondition(), stmt.getThenBranch(), stmt.getElseBranch());
    }

    @Override
    public String visitPrintStmt(Stmt.Print stmt) {
        return parenthesize("print", stmt.getExpression());
    }

    @Override
    public String visitReturnStmt(Stmt.Return stmt) {
        if (stmt.getValue() == null)
            return "(return)";

        return parenthesize("return", stmt.getValue());
    }

    @Override
    public String visitVarStmt(Stmt.Var stmt) {
        if (stmt.getInitializer() == null) {
            return parenthesize2("var", stmt.getName());
        }

        return parenthesize2("var", stmt.getName(), "=", stmt.getInitializer());
    }

    @Override
    public String visitWhileStmt(Stmt.While stmt) {
        return parenthesize2("while", stmt.getCondition(), stmt.getBody());
    }

    @Override
    public String visitAssignExpr(Expr.Assign expr) {
        return parenthesize2("=", expr.getName().lexeme(), expr.getValue());
    }

    @Override
    public String visitBinaryExpr(Expr.Binary expr) {
        return parenthesize(expr.getOperator().lexeme(), expr.getLeft(), expr.getRight());
    }

    @Override
    public String visitCallExpr(Expr.Call expr) {
        return parenthesize2("call", expr.getCallee(), expr.getArguments());
    }

    @Override
    public String visitGetExpr(Expr.Get expr) {
        return parenthesize2(".", expr.getObject(), expr.getName().lexeme());
    }

    @Override
    public String visitGroupingExpr(Expr.Grouping expr) {
        return parenthesize("group", expr.getExpression());
    }

    @Override
    public String visitLiteralExpr(Expr.Literal expr) {
        if (expr.getValue() == null)
            return "nil";

        return expr.getValue().toString();
    }

    @Override
    public String visitLogicalExpr(Expr.Logical expr) {
        return parenthesize(expr.getOperator().lexeme(), expr.getLeft(), expr.getRight());
    }

    @Override
    public String visitSetExpr(Expr.Set expr) {
        return parenthesize2("=", expr.getObject(), expr.getName().lexeme(), expr.getValue());
    }

    @Override
    public String visitSuperExpr(Expr.Super expr) {
        return parenthesize2("super", expr.getMethod());
    }

    @Override
    public String visitThisExpr(Expr.This expr) {
        return "this";
    }

    @Override
    public String visitUnaryExpr(Expr.Unary expr) {
        return parenthesize(expr.getOperator().lexeme(), expr.getRight());
    }

    @Override
    public String visitVariableExpr(Expr.Variable expr) {
        return expr.getName().lexeme();
    }

    private String parenthesize(String name, Expr... exprs) {
        StringBuilder builder = new StringBuilder();

        builder.append("(").append(name);
        for (Expr expr : exprs) {
            builder.append(" ");
            builder.append(expr.accept(this));
        }

        return builder.append(")").toString();
    }

    // Note: AstPrinting other types of syntax trees is not shown in the
    // book, but this is provided here as a reference for those reading
    // the full code.
    private String parenthesize2(String name, Object... parts) {
        StringBuilder builder = new StringBuilder().append("(").append(name);
        transform(builder, parts);

        return builder.append(")").toString();
    }

    private void transform(StringBuilder builder, Object... parts) {
        for (Object part : parts) {
            builder.append(" ");
            if (part instanceof Expr expr) {
                builder.append(expr.accept(this));
            } else if (part instanceof Stmt stmt) {
                builder.append(stmt.accept(this));
            } else if (part instanceof Token token) {
                builder.append(token.lexeme());
            } else if (part instanceof List<?> list) {
                transform(builder, list.toArray());
            } else {
                builder.append(part);
            }
        }
    }
}
