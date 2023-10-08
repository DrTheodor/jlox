package com.craftinginterpreters.lox.interpreter;

import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.util.RuntimeError;
import com.craftinginterpreters.lox.lexer.Token;
import com.craftinginterpreters.lox.util.Value;

import java.util.HashMap;
import java.util.Map;

public class Environment {

    private final Environment enclosing;
    private final Map<String, Value<?>> values = new HashMap<>();

    public Environment() {
        enclosing = null;
    }

    public Environment(Environment enclosing) {
        this.enclosing = enclosing;
    }

    public Object get(Token name) {
        if (values.containsKey(name.lexeme()))
            return values.get(name.lexeme()).get();

        if (enclosing != null)
            return enclosing.get(name);

        throw new RuntimeError(name, "Undefined variable '" + name.lexeme() + "'.");
    }

    public void assign(Token name, Object value) {
        if (values.containsKey(name.lexeme())) {
            Value<?> val = values.get(name.lexeme());

            try {
                val.set(value);
            } catch (IllegalArgumentException e) {
                throw new RuntimeError(name, "Expected type '" + val.getType().getName() + "' but got '" + value.getClass().getName() + "'!");
            }

            return;
        }

        if (enclosing != null) {
            enclosing.assign(name, value);
            return;
        }

        throw new RuntimeError(name, "Undefined variable '" + name.lexeme() + "'.");
    }

    public void define(String name, Object value) {
        values.put(name, new Value<>(value));
    }

    public void define(Stmt.Var var, Object value) {
        this.define(var.getName().lexeme(), value);
    }

    public Environment ancestor(int distance) {
        Environment environment = this;
        for (int i = 0; i < distance; i++) {
            environment = environment.enclosing;
        }

        return environment;
    }

    public Object getAt(int distance, String name) {
        return ancestor(distance).values.get(name).get();
    }

    public void assignAt(int distance, Token name, Object value) {
        Environment ancestor = ancestor(distance);
        if (ancestor.values.containsKey(name.lexeme())) {
            ancestor.values.get(name.lexeme()).set(value);
            return;
        }

        ancestor.values.put(name.lexeme(), new Value<>(value));
    }

    public Environment getEnclosing() {
        return enclosing;
    }

    @Override
    public String toString() {
        String result = values.toString();

        if (enclosing != null) {
            result += " -> " + enclosing;
        }

        return result;
    }
}
