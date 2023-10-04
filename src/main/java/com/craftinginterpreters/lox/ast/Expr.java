package com.craftinginterpreters.lox.ast;

import java.util.Collection;
import com.craftinginterpreters.lox.lexer.Token;

public abstract class Expr {

    public interface Visitor<R> {
        R visitAssignExpr(Assign expr);
        R visitBinaryExpr(Binary expr);
        R visitCallExpr(Call expr);
        R visitGetExpr(Get expr);
        R visitGroupingExpr(Grouping expr);
        R visitLiteralExpr(Literal expr);
        R visitLogicalExpr(Logical expr);
        R visitSetExpr(Set expr);
        R visitSuperExpr(Super expr);
        R visitThisExpr(This expr);
        R visitUnaryExpr(Unary expr);
        R visitVariableExpr(Variable expr);
    }

    public static class Assign extends Expr {
        private final Token name;
        private final Expr value;

        public Assign(Token name, Expr value) {
            this.name = name;
            this.value = value;
        }

        public Token getName() {
            return this.name;
        }

        public Expr getValue() {
            return this.value;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitAssignExpr(this);
        }
    }

    public static class Binary extends Expr {
        private final Token operator;
        private final Expr left;
        private final Expr right;

        public Binary(Expr left, Token operator, Expr right) {
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        public Token getOperator() {
            return this.operator;
        }

        public Expr getLeft() {
            return this.left;
        }

        public Expr getRight() {
            return this.right;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitBinaryExpr(this);
        }
    }

    public static class Call extends Expr {
        private final Collection<Expr> arguments;
        private final Expr callee;
        private final Token paren;

        public Call(Expr callee, Token paren, Collection<Expr> arguments) {
            this.arguments = arguments;
            this.callee = callee;
            this.paren = paren;
        }

        public Collection<Expr> getArguments() {
            return this.arguments;
        }

        public Expr getCallee() {
            return this.callee;
        }

        public Token getParen() {
            return this.paren;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitCallExpr(this);
        }
    }

    public static class Get extends Expr {
        private final Token name;
        private final Expr object;

        public Get(Expr object, Token name) {
            this.name = name;
            this.object = object;
        }

        public Token getName() {
            return this.name;
        }

        public Expr getObject() {
            return this.object;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitGetExpr(this);
        }
    }

    public static class Grouping extends Expr {
        private final Expr expression;

        public Grouping(Expr expression) {
            this.expression = expression;
        }

        public Expr getExpression() {
            return this.expression;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitGroupingExpr(this);
        }
    }

    public static class Literal extends Expr {
        private final Object value;

        public Literal(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return this.value;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitLiteralExpr(this);
        }
    }

    public static class Logical extends Expr {
        private final Token operator;
        private final Expr left;
        private final Expr right;

        public Logical(Expr left, Token operator, Expr right) {
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        public Token getOperator() {
            return this.operator;
        }

        public Expr getLeft() {
            return this.left;
        }

        public Expr getRight() {
            return this.right;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitLogicalExpr(this);
        }
    }

    public static class Set extends Expr {
        private final Token name;
        private final Expr value;
        private final Expr object;

        public Set(Expr object, Token name, Expr value) {
            this.name = name;
            this.value = value;
            this.object = object;
        }

        public Token getName() {
            return this.name;
        }

        public Expr getValue() {
            return this.value;
        }

        public Expr getObject() {
            return this.object;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitSetExpr(this);
        }
    }

    public static class Super extends Expr {
        private final Token method;
        private final Token keyword;

        public Super(Token keyword, Token method) {
            this.method = method;
            this.keyword = keyword;
        }

        public Token getMethod() {
            return this.method;
        }

        public Token getKeyword() {
            return this.keyword;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitSuperExpr(this);
        }
    }

    public static class This extends Expr {
        private final Token keyword;

        public This(Token keyword) {
            this.keyword = keyword;
        }

        public Token getKeyword() {
            return this.keyword;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitThisExpr(this);
        }
    }

    public static class Unary extends Expr {
        private final Token operator;
        private final Expr right;

        public Unary(Token operator, Expr right) {
            this.operator = operator;
            this.right = right;
        }

        public Token getOperator() {
            return this.operator;
        }

        public Expr getRight() {
            return this.right;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitUnaryExpr(this);
        }
    }

    public static class Variable extends Expr {
        private final Token name;

        public Variable(Token name) {
            this.name = name;
        }

        public Token getName() {
            return this.name;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitVariableExpr(this);
        }
    }

    public abstract <R> R accept(Visitor<R> visitor);
}
