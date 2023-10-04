package dev.drtheo.ast;

import dev.drtheo.ast.data.Arg;
import dev.drtheo.ast.util.JavaBuilder;
import dev.drtheo.ast.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GenerateAST {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: generate_ast <output directory> <input files>");
            System.exit(64);
        }

        String output = args[0];
        Files.createDirectories(Paths.get(output));
        for (int i = 1; i < args.length; i++) {
            String inputFile = args[i];
            if (!inputFile.endsWith(".ast"))
                inputFile += ".ast";

            define(inputFile, output);
        }
    }

    private static void define(String inputFile, String output) throws IOException {
        String text = Util.read(inputFile);

        String baseName = inputFile.replace(".ast", "");
        String path = output + "/" + baseName + ".java";

        try (JavaBuilder writer = new JavaBuilder(path, StandardCharsets.UTF_8)) {
            List<String> types = text.lines().filter(line -> {
                if (line.startsWith("package") || line.startsWith("import")) {
                    writer.header(line);
                    return false;
                }

                return line.trim().length() > 0;
            }).toList();

            writer.clazz("public abstract", baseName);
            defineVisitor(writer, baseName, types);

            for (String type : types) {
                String className = type.split(":")[0].trim();
                String fields = type.split(":")[1].trim();
                defineType(writer, baseName, className, fields);
            }

            writer.newline();
            writer.println("public abstract <R> R accept(Visitor<R> visitor)");
            writer.end();
        }
    }

    private static void defineVisitor(JavaBuilder writer, String baseName, List<String> types) {
        writer.interf4ce("public", "Visitor<R>");

        for (String type : types) {
            String typeName = type.split(":")[0].trim();
            writer.println("R visit" + typeName + baseName + "(" +
                    typeName + " " + baseName.toLowerCase() + ")");
        }

        writer.end();
    }

    private static void defineType(JavaBuilder writer, String baseName, String className, String rawFields) {
        writer.clazz("public static", className, "extends " + baseName);

        // Store parameters in fields.
        Set<Arg> args = new HashSet<>();
        for (String field : rawFields.split(", ")) {
            String[] parts = field.split(" ");
            args.add(new Arg(parts[0], parts[1]));
        }

        writeFields(writer, args);
        writeConstructor(writer, className, args, rawFields);
        writeGetters(writer, args);
        writeVisitor(writer, className, baseName);

        writer.end();
    }

    private static void writeFields(JavaBuilder writer, Collection<Arg> args) {
        for (Arg arg : args) {
            writer.println("private final " + arg);
        }

        writer.newline();
    }

    private static void writeConstructor(JavaBuilder writer, String className, Collection<Arg> args, String rawFields) {
        writer.begin("public " + className + "(" + rawFields + ")");

        for (Arg arg : args) {
            writer.println("this." + arg.name() + " = " + arg.name());
        }

        writer.end();
    }

    private static void writeGetters(JavaBuilder writer, Collection<Arg> args) {
        for (Arg arg : args) {
            writer.newline();
            writer.begin("public " + arg.type() + " get" + Util.capitalize(arg.name()) + "()");
            writer.println("return this." + arg.name());
            writer.end();
        }
    }

    private static void writeVisitor(JavaBuilder writer, String className, String baseName) {
        writer.newline();
        writer.raw("@Override");
        writer.begin("public <R> R accept(Visitor<R> visitor)");
        writer.println("return visitor.visit" + className + baseName + "(this)");
        writer.end();
    }
}
