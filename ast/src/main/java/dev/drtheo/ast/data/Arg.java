package dev.drtheo.ast.data;

public record Arg(String type, String name) {

    @Override
    public String toString() {
        return type + " " + name;
    }
}
