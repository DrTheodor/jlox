package com.craftinginterpreters.lox.parser;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;

import java.util.*;

import static com.craftinginterpreters.lox.lexer.TokenType.*;

public class Parser extends TokenEnumerator {

    private static class ParseError extends RuntimeException { }

    public Parser(List<Token> tokens) {
        super(tokens);
    }

    public Collection<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();

        while (!this.isAtEnd()) {
            statements.add(this.declaration());
        }

        return statements;
    }

    private Expr expression() {
        return this.assignment();
    }

    private Stmt declaration() {
        try {
            if (this.match(CLASS))
                return this.classDeclaration();

            if (this.match(FUN))
                return this.function("function");

            if (this.match(VAR))
                return this.varDeclaration();

            return this.statement();
        } catch (ParseError error) {
            this.synchronize();
            return null;
        }
    }

    private Stmt classDeclaration() {
        Token name = this.consume(IDENTIFIER, "Expect class name.");
        Expr.Variable superclass = null;

        if (this.match(LESS)) {
            this.consume(IDENTIFIER, "Expect superclass name.");
            superclass = new Expr.Variable(this.previous());
        }

        this.consume(LEFT_BRACE, "Expect '{' before class body.");

        // TODO: add a method/func keyword check somewhere here
        List<Stmt.Function> methods = new ArrayList<>();
        while (!this.check(RIGHT_BRACE) && !this.isAtEnd()) {
            methods.add(this.function("method"));
        }

        this.consume(RIGHT_BRACE, "Expect '}' after class body.");
        return new Stmt.Class(name, superclass, methods);
    }

    private Stmt statement() {
        if (this.match(FOR))
            return this.forStatement();

        if (this.match(IF))
            return this.ifStatement();

        if (this.match(PRINT))
            return this.printStatement();

        if (this.match(RETURN))
            return this.returnStatement();

        if (this.match(WHILE))
            return this.whileStatement();

        if (this.match(LEFT_BRACE))
            return new Stmt.Block(this.block());

        return this.expressionStatement();
    }

    private Stmt forStatement() {
        this.consume(LEFT_PAREN, "Expect '(' after 'for'.");

        Stmt initializer = this.match((token) -> switch (token.type()) {
            case SEMICOLON -> null;
            case VAR -> this.varDeclaration();
            default -> this.expressionStatement();
        }, SEMICOLON, VAR);

        Expr condition = null;
        if (!this.check(SEMICOLON)) {
            condition = this.expression();
        }

        this.consume(SEMICOLON, "Expect ';' after loop condition.");

        Expr increment = null;
        if (!this.check(RIGHT_PAREN)) {
            increment = this.expression();
        }

        this.consume(RIGHT_PAREN, "Expect ')' after for clauses.");
        Stmt body = this.statement();

        if (increment != null) {
            body = new Stmt.Block(Arrays.asList(body, new Stmt.Expression(increment)));
        }

        if (condition == null)
            condition = new Expr.Literal(true);

        body = new Stmt.While(condition, body);

        if (initializer != null) {
            body = new Stmt.Block(Arrays.asList(initializer, body));
        }

        return body;
    }

    private Stmt ifStatement() {
        this.consume(LEFT_PAREN, "Expect '(' after 'if'.");
        Expr condition = expression();

        this.consume(RIGHT_PAREN, "Expect ')' after if condition.");

        Stmt thenBranch = this.statement();
        Stmt elseBranch = null;
        if (this.match(ELSE)) {
            elseBranch = this.statement();
        }

        return new Stmt.If(condition, thenBranch, elseBranch);
    }

    private Stmt printStatement() {
        Expr value = this.expression();
        this.consume(SEMICOLON, "Expect ';' after value.");

        return new Stmt.Print(value);
    }

    private Stmt returnStatement() {
        Token keyword = this.previous();
        Expr value = null;

        if (!this.check(SEMICOLON)) {
            value = this.expression();
        }

        this.consume(SEMICOLON, "Expect ';' after return value.");
        return new Stmt.Return(keyword, value);
    }

    private Stmt varDeclaration() {
        Token name = this.consume(IDENTIFIER, "Expect variable name.");

        Expr initializer = null;
        if (this.match(EQUAL)) {
            initializer = this.expression();
        }

        this.consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, initializer);
    }

    private Stmt whileStatement() {
        this.consume(LEFT_PAREN, "Expect '(' after 'while'.");
        Expr condition = this.expression();

        this.consume(RIGHT_PAREN, "Expect ')' after condition.");
        Stmt body = this.statement();

        return new Stmt.While(condition, body);
    }

    private Stmt expressionStatement() {
        Expr expr = this.expression();
        this.consume(SEMICOLON, "Expect ';' after expression.");

        return new Stmt.Expression(expr);
    }

    // TODO: replace `String kind` with an enum or even a bool
    private Stmt.Function function(String kind) {
        Token name = this.consume(IDENTIFIER, "Expect " + kind + " name.");
        this.consume(LEFT_PAREN, "Expect '(' after " + kind + " name.");

        Set<Token> parameters = new HashSet<>();

        if (!this.check(RIGHT_PAREN)) {
            do {
                if (parameters.size() >= 255) {
                    throw error("Can't have more than 255 parameters.");
                }

                parameters.add(this.consume(IDENTIFIER, "Expect parameter name."));
            } while (this.match(COMMA));
        }

        this.consume(RIGHT_PAREN, "Expect ')' after parameters.");
        this.consume(LEFT_BRACE, "Expect '{' before " + kind + " body.");

        return new Stmt.Function(name, parameters, block());
    }

    private Collection<Stmt> block() {
        Collection<Stmt> statements = new ArrayList<>();

        while (!this.check(RIGHT_BRACE) && !this.isAtEnd()) {
            statements.add(this.declaration());
        }

        this.consume(RIGHT_BRACE, "Expect '}' after block.");
        return statements;
    }

    private Expr assignment() {
        Expr expr = this.or();

        if (this.match(EQUAL)) {
            Token equals = this.previous();
            Expr value = this.assignment();

            if (expr instanceof Expr.Variable variable) {
                return new Expr.Assign(variable.getName(), value);
            }

            if (expr instanceof Expr.Get get) {
                return new Expr.Set(get.getObject(), get.getName(), value);
            }

            throw error(equals, "Invalid assignment target.");
        }

        return expr;
    }

    private Expr or() {
        Expr expr = this.and();

        while (this.match(OR)) {
            Token operator = this.previous();
            Expr right = this.and();

            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        Expr expr = this.equality();

        while (this.match(AND)) {
            Token operator = this.previous();
            Expr right = this.equality();

            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr equality() {
        Expr expr = this.comparison();

        while (this.match(BANG_EQUAL, EQUAL_EQUAL)) {
            Token operator = this.previous();
            Expr right = this.comparison();

            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr comparison() {
        Expr expr = this.term();

        while (this.match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            Token operator = this.previous();
            Expr right = this.term();

            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr term() {
        Expr expr = this.factor();

        while (this.match(MINUS, PLUS)) {
            Token operator = this.previous();
            Expr right = this.factor();

            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr factor() {
        Expr expr = this.unary();

        while (this.match(SLASH, STAR)) {
            Token operator = this.previous();
            Expr right = this.unary();

            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr unary() {
        if (this.match(BANG, MINUS)) {
            Token operator = this.previous();
            Expr right = this.unary();

            return new Expr.Unary(operator, right);
        }

        return this.call();
    }

    private Expr finishCall(Expr callee) {
        List<Expr> arguments = new ArrayList<>();

        if (!this.check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 255) {
                    throw error("Can't have more than 255 arguments.");
                }

                arguments.add(this.expression());
            } while (this.match(COMMA));
        }

        Token paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");
        return new Expr.Call(callee, paren, arguments);
    }

    private Expr call() {
        Expr expr = this.primary();

        while (true) {
            if (this.match(LEFT_PAREN)) {
                expr = finishCall(expr);
                continue;
            }

            if (this.match(DOT)) {
                Token name = this.consume(IDENTIFIER, "Expect property name after '.'.");
                expr = new Expr.Get(expr, name);
                continue;
            }

            break;
        }

        return expr;
    }

    private Expr primary() {
        return this.match(token -> switch (token.type()) {
            case FALSE -> new Expr.Literal(false);
            case TRUE -> new Expr.Literal(true);
            case NIL -> new Expr.Literal(null);

            case NUMBER, STRING -> new Expr.Literal(this.previous().literal());

            case THIS -> new Expr.This(this.previous());
            case IDENTIFIER -> new Expr.Variable(this.previous());

            case SUPER -> {
                Token keyword = this.previous();
                this.consume(DOT, "Expect '.' after 'super'.");

                yield new Expr.Super(keyword, this.consume(IDENTIFIER, "Expect superclass method name."));
            }

            case LEFT_PAREN -> {
                Expr expr = this.expression();
                this.consume(RIGHT_PAREN, "Expect ')' after expression.");

                yield new Expr.Grouping(expr);
            }

            default -> throw error("Expect expression.");
        }, FALSE, TRUE, NIL, NUMBER, STRING, THIS, IDENTIFIER, SUPER, LEFT_PAREN);
    }
}
