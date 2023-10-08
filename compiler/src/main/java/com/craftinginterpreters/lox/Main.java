package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Lexer;
import com.craftinginterpreters.lox.parser.Parser;
import lox.LoxException;
import proguard.classfile.ClassPool;
import proguard.classfile.util.ClassPoolClassLoader;
import proguard.io.util.IOUtil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import static com.craftinginterpreters.lox.Lox.hadError;
import static com.craftinginterpreters.lox.Lox.hadRuntimeError;
import static com.craftinginterpreters.lox.LoxConstants.LOX_MAIN_CLASS;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 2) {
            System.out.println("Usage: jlox [script]");
            System.exit(64);
            return;
        }

        if (args.length == 0) {
            Lox.main(args);
            return;
        }

        if (System.getProperty("profiling", "").equals("1"))
            Thread.sleep(10000);

        long start = System.currentTimeMillis();
        ClassPool classPool = compileFile(args[0]);

        if (hadRuntimeError)
            System.exit(70);

        if (hadError || classPool == null)
            System.exit(65);

        if (args.length == 1) {
            runClassPool(classPool, args);
        } else {
            IOUtil.writeJar(classPool, args[1], LOX_MAIN_CLASS);
        }

        System.out.println(System.currentTimeMillis() - start);
    }

    private static void runClassPool(ClassPool programClassPool, String[] args) throws RuntimeException {
        ClassLoader classLoader = new ClassPoolClassLoader(programClassPool);

        try {
            Class<?> main = classLoader.loadClass(LOX_MAIN_CLASS);
            Method method = main.getDeclaredMethod("main", String[].class);

            method.invoke(null, (Object) args);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static ClassPool compileFile(String path) throws IOException {
        return compile(Files.readString(Paths.get(path)));
    }

    public static ClassPool compile(String source) {
        try {
            Lexer scanner = new Lexer(source);
            Parser parser = new Parser(scanner.scanTokens());

            Collection<Stmt> statements = parser.parse();

            if (hadError)
                return null;

            new Checker().execute(statements);

            if (hadError)
                return null;

            ClassPool classPool = new Compiler().compile(statements);

            if (hadError)
                return null;

            return classPool;
        } catch (LoxException e) {
            System.err.println(e.getMessage());
            hadRuntimeError = true;
            return null;
        }
    }
}