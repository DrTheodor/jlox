package com.craftinginterpreters.lox.runtime;

import com.craftinginterpreters.lox.interpreter.Interpreter;
import java.util.List;

public interface LoxCallable {
    int arity();

    Object call(Interpreter interpreter, List<Object> arguments);
}
