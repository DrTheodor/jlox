//> Scanning lox-class
package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.interpreter.Interpreter;
import com.craftinginterpreters.lox.interpreter.Resolver;
import com.craftinginterpreters.lox.lexer.Lexer;
import com.craftinginterpreters.lox.lexer.Token;
import com.craftinginterpreters.lox.lexer.TokenType;
import com.craftinginterpreters.lox.parser.Parser;
import com.craftinginterpreters.lox.util.RuntimeError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

public class Lox {

    private static final Interpreter interpreter = new Interpreter();

    static boolean hadError = false;
    static boolean hadRuntimeError = false;

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            System.out.println("Usage: jlox [script]");
            System.exit(64);
        } else if (args.length == 1) {
            runFile(args[0]);
        } else {
            runPrompt();
        }
    }

    private static void runFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        run(new String(bytes, Charset.defaultCharset()));

        if (hadError)
            System.exit(65);

        if (hadRuntimeError)
            System.exit(70);
    }

    private static void runPrompt() throws IOException {
        InputStreamReader input = new InputStreamReader(System.in);
        BufferedReader reader = new BufferedReader(input);

        for (;;) {
            System.out.print("> ");
            String line = reader.readLine();
            if (line == null) break;
            run(line);

            hadError = false;
        }
    }

    private static void run(String source) {
        Lexer scanner = new Lexer(source);
        List<Token> tokens = scanner.scanTokens();

        Parser parser = new Parser(tokens);
        Collection<Stmt> statements = parser.parse();

        if (hadError)
            return;

        Resolver resolver = new Resolver(interpreter);
        resolver.resolve(statements);

        if (hadError)
            return;

        interpreter.interpret(statements);
    }

    private static void report(int line, String where, String message) {
        System.err.println("[line " + line + "] Error" + where + ": " + message);
        hadError = true;
    }

    public static void error(int line, String message) {
        report(line, "", message);
    }

    public static void error(Token token, String message) {
        if (token.type() == TokenType.EOF) {
            report(token.line(), " at end", message);
        } else {
            report(token.line(), " at '" + token.lexeme() + "'", message);
        }
    }

    public static void runtimeError(RuntimeError error) {
        System.err.println(error.getMessage() + "\n[line " + error.getToken().line() + "]");
        hadRuntimeError = true;
    }
}
