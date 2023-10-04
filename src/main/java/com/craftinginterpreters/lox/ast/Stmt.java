package com.craftinginterpreters.lox.ast;

import java.util.Collection;
import com.craftinginterpreters.lox.lexer.Token;

public abstract class Stmt {

    public interface Visitor<R> {
        R visitBlockStmt(Block stmt);
        R visitClassStmt(Class stmt);
        R visitExpressionStmt(Expression stmt);
        R visitFunctionStmt(Function stmt);
        R visitIfStmt(If stmt);
        R visitPrintStmt(Print stmt);
        R visitReturnStmt(Return stmt);
        R visitVarStmt(Var stmt);
        R visitWhileStmt(While stmt);
    }

    public static class Block extends Stmt {
        private final Collection<Stmt> statements;

        public Block(Collection<Stmt> statements) {
            this.statements = statements;
        }

        public Collection<Stmt> getStatements() {
            return this.statements;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitBlockStmt(this);
        }
    }

    public static class Class extends Stmt {
        private final Collection<Stmt.Function> methods;
        private final Token name;
        private final Expr.Variable superclass;

        public Class(Token name, Expr.Variable superclass, Collection<Stmt.Function> methods) {
            this.methods = methods;
            this.name = name;
            this.superclass = superclass;
        }

        public Collection<Stmt.Function> getMethods() {
            return this.methods;
        }

        public Token getName() {
            return this.name;
        }

        public Expr.Variable getSuperclass() {
            return this.superclass;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitClassStmt(this);
        }
    }

    public static class Expression extends Stmt {
        private final Expr expression;

        public Expression(Expr expression) {
            this.expression = expression;
        }

        public Expr getExpression() {
            return this.expression;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitExpressionStmt(this);
        }
    }

    public static class Function extends Stmt {
        private final Collection<Token> params;
        private final Token name;
        private final Collection<Stmt> body;

        public Function(Token name, Collection<Token> params, Collection<Stmt> body) {
            this.params = params;
            this.name = name;
            this.body = body;
        }

        public Collection<Token> getParams() {
            return this.params;
        }

        public Token getName() {
            return this.name;
        }

        public Collection<Stmt> getBody() {
            return this.body;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitFunctionStmt(this);
        }
    }

    public static class If extends Stmt {
        private final Stmt elseBranch;
        private final Stmt thenBranch;
        private final Expr condition;

        public If(Expr condition, Stmt thenBranch, Stmt elseBranch) {
            this.elseBranch = elseBranch;
            this.thenBranch = thenBranch;
            this.condition = condition;
        }

        public Stmt getElseBranch() {
            return this.elseBranch;
        }

        public Stmt getThenBranch() {
            return this.thenBranch;
        }

        public Expr getCondition() {
            return this.condition;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitIfStmt(this);
        }
    }

    public static class Print extends Stmt {
        private final Expr expression;

        public Print(Expr expression) {
            this.expression = expression;
        }

        public Expr getExpression() {
            return this.expression;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitPrintStmt(this);
        }
    }

    public static class Return extends Stmt {
        private final Token keyword;
        private final Expr value;

        public Return(Token keyword, Expr value) {
            this.keyword = keyword;
            this.value = value;
        }

        public Token getKeyword() {
            return this.keyword;
        }

        public Expr getValue() {
            return this.value;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitReturnStmt(this);
        }
    }

    public static class Var extends Stmt {
        private final Token name;
        private final Expr initializer;

        public Var(Token name, Expr initializer) {
            this.name = name;
            this.initializer = initializer;
        }

        public Token getName() {
            return this.name;
        }

        public Expr getInitializer() {
            return this.initializer;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitVarStmt(this);
        }
    }

    public static class While extends Stmt {
        private final Expr condition;
        private final Stmt body;

        public While(Expr condition, Stmt body) {
            this.condition = condition;
            this.body = body;
        }

        public Expr getCondition() {
            return this.condition;
        }

        public Stmt getBody() {
            return this.body;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitWhileStmt(this);
        }
    }

    public abstract <R> R accept(Visitor<R> visitor);
}
