package com.craftinginterpreters.lox.util;

public class Value<T> {

    private final Class<?> clazz;
    private T object;

    public Value(T object) {
        this.object = object;
        this.clazz = object.getClass();
    }

    public T get() {
        return object;
    }

    public void set(Object object) {
        if (object.getClass().isInstance(this.clazz))
            this.object = (T) object;

        throw new IllegalArgumentException();
    }

    public Class<?> getType() {
        return clazz;
    }
}
