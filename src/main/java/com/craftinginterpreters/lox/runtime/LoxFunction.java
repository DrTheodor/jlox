package com.craftinginterpreters.lox.runtime;

import com.craftinginterpreters.lox.interpreter.Environment;
import com.craftinginterpreters.lox.interpreter.Interpreter;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;
import com.craftinginterpreters.lox.util.Return;

import java.util.List;

public class LoxFunction implements LoxCallable {

    private final Stmt.Function declaration;
    private final Environment closure;

    private final boolean isInitializer;

    public LoxFunction(Stmt.Function declaration, Environment closure, boolean isInitializer) {
        this.declaration = declaration;
        this.closure = closure;

        this.isInitializer = isInitializer;
    }

    public LoxFunction bind(LoxInstance instance) {
        Environment environment = new Environment(closure);
        environment.define("this", instance);

        return new LoxFunction(declaration, environment, isInitializer);
    }

    @Override
    public String toString() {
        return "<fn " + declaration.getName().lexeme() + ">";
    }

    @Override
    public int arity() {
        return this.declaration.getParams().size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Environment environment = new Environment(closure);

        int i = 0;
        for (Token param : declaration.getParams()) {
            environment.define(param.lexeme(), arguments.get(i));
            i++;
        }

        try {
            interpreter.executeBlock(declaration.getBody(), environment);
        } catch (Return returnValue) {
            if (isInitializer)
                return closure.getAt(0, "this");

            return returnValue.getValue();
        }

        if (isInitializer)
            return closure.getAt(0, "this");

        return null;
    }
}
