package com.craftinginterpreters.lox.parser;

import com.craftinginterpreters.lox.Lox;
import com.craftinginterpreters.lox.lexer.Token;
import com.craftinginterpreters.lox.lexer.TokenType;

import java.util.List;
import java.util.function.Function;

import static com.craftinginterpreters.lox.lexer.TokenType.EOF;
import static com.craftinginterpreters.lox.lexer.TokenType.SEMICOLON;

public class TokenEnumerator {

    protected final List<Token> tokens;
    private int current = 0;

    public TokenEnumerator(List<Token> tokens) {
        this.tokens = tokens;
    }

    protected boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (this.check(type)) {
                this.advance();
                return true;
            }
        }

        return false;
    }

    protected <R> R match(Function<Token, R> consumer, TokenType... types) {
        Token token = this.peek();
        this.match(types);

        return consumer.apply(token);
    }

    protected Token consume(TokenType type, String message) {
        if (this.check(type))
            return this.advance();

        throw error(message);
    }

    protected boolean check(TokenType type) {
        if (this.isAtEnd())
            return false;

        return this.peek().type() == type;
    }

    protected Token advance() {
        if (!this.isAtEnd())
            this.current++;

        return this.previous();
    }

    protected boolean isAtEnd() {
        return this.peek().type() == EOF;
    }

    protected Token peek() {
        return this.tokens.get(this.current);
    }

    protected Token previous() {
        return this.tokens.get(this.current - 1);
    }

    protected void synchronize() {
        this.advance();

        while (!this.isAtEnd()) {
            if (this.previous().type() == SEMICOLON)
                return;

            switch (this.peek().type()) {
                case CLASS, FUN, VAR, FOR, IF, WHILE, PRINT, RETURN -> {
                    return;
                }
            }

            this.advance();
        }
    }

    protected ParseError error(String message) {
        return error(this.peek(), message);
    }

    protected static ParseError error(Token token, String message) {
        Lox.error(token, message);
        return new ParseError();
    }

    protected static class ParseError extends RuntimeException { }
}
