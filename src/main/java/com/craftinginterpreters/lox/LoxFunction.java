//> Functions lox-function
package com.craftinginterpreters.lox;

import java.util.List;

public class LoxFunction implements LoxCallable {

    private final Stmt.Function declaration;
    private final Environment closure;

    private final boolean isInitializer;

    public LoxFunction(Stmt.Function declaration, Environment closure, boolean isInitializer) {
        this.isInitializer = isInitializer;
        this.closure = closure;
        this.declaration = declaration;
    }

    public LoxFunction bind(LoxInstance instance) {
        Environment environment = new Environment(closure);
        environment.define("this", instance);

        return new LoxFunction(declaration, environment, isInitializer);
    }

    @Override
    public String toString() {
        return "<fn " + declaration.name.lexeme() + ">";
    }

    @Override
    public int arity() {
        return this.declaration.params.size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
        Environment environment = new Environment(closure);

        for (int i = 0; i < declaration.params.size(); i++) {
            environment.define(declaration.params.get(i).lexeme(), arguments.get(i));
        }

        try {
            interpreter.executeBlock(declaration.body, environment);
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
