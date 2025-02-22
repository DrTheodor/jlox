package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

import static com.craftinginterpreters.lox.Lox.error;
import static com.craftinginterpreters.lox.LoxConstants.LOX_MAIN_CLASS;
import static com.craftinginterpreters.lox.lexer.TokenType.SUPER;
import static com.craftinginterpreters.lox.lexer.TokenType.THIS;

public class CompilerResolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
    public static boolean DEBUG = System.getProperty("jlox.resolver.debug") != null;

    private final Map<Token, VarDef> variables = new WeakHashMap<>();
    private final Map<Token, VarDef> varUse = new WeakHashMap<>();
    private final Map<Token, Integer> writes = new WeakHashMap<>();
    private final Map<Token, Integer> reads = new WeakHashMap<>();
    private final Stack<Map<VarDef, Boolean>> scopes = new Stack<>();
    private final Stack<Stmt.Function> functionStack = new Stack<>();
    private final Stack<Stmt.Class> classStack = new Stack<>();
    private final Map<Token, Set<VarDef>> captured = new WeakHashMap<>();
    private final Map<Token, String> javaClassNames = new WeakHashMap<>();
    private final Map<Token, String> javaFieldNames = new WeakHashMap<>();
    private final Set<UnresolvedLocal> unresolved = new HashSet<>();

    public void resolve(Stmt.Function main) {
        resolveFunction(main);

        if (DEBUG) {
            System.out.println("variables: " + variables.values());
            System.out.println("globals: " + variables.values().stream().filter(VarDef::isGlobal).collect(Collectors.toSet()));
            System.out.println("captured: " + variables.values().stream().filter(VarDef::isCaptured).collect(Collectors.toSet()));
            System.out.println("lateinit: " + variables.values().stream().filter(VarDef::isLateInit).collect(Collectors.toSet()));
            System.out.println("final: " + variables.values().stream().filter(VarDef::isFinal).collect(Collectors.toSet()));
            System.out.println("unread: " + variables.values().stream().filter(varDef -> !varDef.isRead()).collect(Collectors.toSet()));
            System.out.println("unresolved: " + unresolved);
            System.out.println("varUse: " + varUse);
            System.out.println("writes: " + writes);
            System.out.println("reads: " + reads);
        }

        // Cannot throw errors for unresolved here, since Lox permits unreachable, unresolved variables.
    }

    private void resolve(Collection<Stmt> stmts) {
        stmts.forEach(this::resolve);
    }

    private void resolve(Stmt stmt) {
        stmt.accept(this);
    }

    private void resolve(Expr expr) {
        expr.accept(this);
    }

    private void resolveMethod(Stmt.Class classStmt, Stmt.Function method) {
        javaFieldName(method.getName(), method.getName().lexeme());
        resolveFunction(method, classStmt.getName().lexeme());
    }

    private void resolveFunction(Stmt.Function function) {
        resolveFunction(function, "");
    }

    private void resolveFunction(Stmt.Function function, String namePrefix) {
        javaClassName(function.getName(), namePrefix);
        beginScope(function);

        for (Token param : function.getParams()) {
            var varDef = declare(param, ParameterVarDef.class);
            define(varDef);
        }

        resolve(function.getBody());
        endScope(function);
    }

    /**
     * Given a variable access expression (VarExpr, AssignExpr, SuperExpr, ThisExpr)
     * find the matching definition in the closest scope. For example:
     * <code>
     * 1: var a = 1;
     * 2: {
     * 3:    {
     * 4:        var a = 2;
     * 5:        print a; // resolves to a@line4
     * 6:    }
     * 7:    print a; // resolves to a@line1
     * 8: }
     * </code>
     * <p>
     * It's possible that the definition is outside a given function, in which case
     * the variable is *captured* by the function. In this case, the depth
     * between the definition function where the variable is used and the function where
     * the variable is defined is recorded; the compiler will later use the depth information
     * to get the enclosing function at that depth. For example:
     * <p>
     * 0: // main
     * 1: var a = 1; // depth 2
     * 2: fun foo() { // depth 1
     * 3:    fun bar() { // depth 0
     * 4:        print a; // resolves to a@line1 with depth 2
     * 5:    }
     * 6: }
     * <p>
     * class {
     *     foo() { // depth 1
     *         bar() { // depth 0
     *             print this; // resolves to class with depth 1
     *         }
     *     }
     * }
     */
    private Optional<VarDef> resolveLocal(Expr varAccess, Token name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            var varDef = scopes.get(i).keySet().stream().filter(key -> key.token.lexeme().equals(name.lexeme())).findFirst();
            if (varDef.isPresent()) {
                int depth = functionStack.size() - functionStack.indexOf(varDef.get().function) - 1 /* to account for current function */;
                if (depth != 0) {
                    if (varAccess instanceof Expr.This || varAccess instanceof Expr.Super) {
                        captureThisOrSuper(functionStack.peek(), varDef.get(), depth - 1);
                    } else {
                        capture(functionStack.peek(), varDef.get(), depth);
                    }
                }
                varUse.put(name, varDef.get());
                if (DEBUG) System.out.println(name.lexeme() + "@line" + name.line() + " -> " + varDef.get() + "@line" + varDef.get().token().line());
                return varDef;
            }
        }

        if (DEBUG)
            System.out.println(varAccess + " undefined");

        unresolved.add(new UnresolvedLocal(functionStack.peek(), functionStack.size(), varAccess, name));
        return Optional.empty();
    }

    private void beginScope(Stmt.Function function) {
        functionStack.push(function);
        beginScope();
    }

    private void beginScope(Stmt.Class classStmt) {
        classStack.push(classStmt);
        beginScope();
    }

    private void beginScope() {
        scopes.push(new HashMap<>());
    }

    private void endScope() {
        scopes.pop();
    }

    private void endScope(Stmt.Function ignoredFunction) {
        endScope();
        functionStack.pop();
    }

    private void endScope(Stmt.Class ignoredClass) {
        endScope();
        classStack.pop();
    }

    private VarDef declare(Token name) {
        return declare(name, VarDef.class);
    }

    private <T extends VarDef> T declare(Token name, Class<T> type) {
        if (scopes.isEmpty()) return null;

        var scope = scopes.peek();
        var isGlobalScope = scopes.size() == 1;
        var currentFunction = functionStack.peek();
        var existingVarDef = scope.keySet().stream().filter(it -> it.token().lexeme().equals(name.lexeme())).findFirst();

        if (existingVarDef.isPresent()) {
            if (!isGlobalScope) error(name, "Already a variable with this name in this scope.");
            writes.merge(existingVarDef.get().token(), 1, Integer::sum);
        }

        T varDef;
        try {
            // GlobalVar is not the same as global scope -
            // there can be multiple scopes in the top-level function.
            // GlobalVar means that the var is declared in any scope that
            // is in the top-level function.
            var isGlobalVar = javaClassName(currentFunction).equals(LOX_MAIN_CLASS);

            varDef = type.getConstructor(this.getClass(), Token.class, Stmt.Function.class, Boolean.class)
                         .newInstance(this, existingVarDef.map(VarDef::token).orElse(name), currentFunction, isGlobalVar);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }

        variables.put(name, varDef);
        scope.put(varDef, false);

        if (isGlobalScope) {
            var resolved = new HashSet<UnresolvedLocal>();
            unresolved.stream().filter(it -> it.name.lexeme().equals(name.lexeme())).forEach(it -> {
                varUse.put(it.name, varDef);

                // Update reads that occurred before declaration
                reads.merge(varDef.token(), 1, Integer::sum);
                reads.remove(it.name);

                capture(it.function, varDef, it.depth);
                resolved.add(it);
                varDef.isLateInit = true;
            });
            unresolved.removeAll(resolved);
        }

        javaFieldName(varDef.token, varDef.token.lexeme());

        return varDef;
    }

    private void define(VarDef varDef) {
        if (scopes.isEmpty())
            return;

        scopes.peek().put(varDef, true);
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        resolve(expr.getValue());
        var varDef = resolveLocal(expr, expr.getName());
        varDef.ifPresent(it -> writes.merge(it.token, 1, Integer::sum));
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        resolve(expr.getLeft());
        resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        resolve(expr.getCallee());
        expr.getArguments().forEach(this::resolve);
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        resolve(expr.getObject());
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        resolve(expr.getExpression());
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        resolve(expr.getLeft());
        resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        resolve(expr.getObject());
        resolve(expr.getValue());
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {
        resolveLocal(expr, expr.getKeyword());
        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {
        resolveLocal(expr, expr.getKeyword());
        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        var isGlobalScope = scopes.size() == 1;
        var isAlreadyDeclared = scopes
            .peek()
            .keySet()
            .stream()
            .anyMatch(it -> it.token.lexeme().equals(expr.getName().lexeme()));

        if (!isGlobalScope && isAlreadyDeclared) {
            scopes
                .peek()
                .entrySet()
                .stream()
                .filter(it -> it.getKey().token.lexeme().equals(expr.getName().lexeme()))
                .map(Map.Entry::getValue)
                .findAny()
                .ifPresent(variableIsDefined -> {
                    // Declared and not yet defined - it must be its own initializer!
                    if (!variableIsDefined)
                        error(expr.getName(), "Can't read local variable in its own initializer.");
                });
        }

        var varDef = resolveLocal(expr, expr.getName());
        varDef.ifPresentOrElse(
            it -> reads.merge(it.token, 1, Integer::sum),
            () -> reads.put(expr.getName(), 1)
        );
        return null;
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        beginScope();
        resolve(stmt.getStatements());

        endScope();
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        var varDef = declare(stmt.getName(), ClassVarDef.class);
        define(varDef);

        javaClassName(stmt.getName(), "");

        if (stmt.getSuperclass() != null)
            resolve(stmt.getSuperclass());

        beginScope(stmt);

        define(
            new ThisVarDef(
            new Token(THIS, "this", null, stmt.getName().line()), functionStack.peek(), false)
        );

        if (stmt.getSuperclass() != null)
            define(
                new SuperVarDef(
                new Token(SUPER, "super", null, stmt.getName().line()), functionStack.peek(), false)
            );

        stmt.getMethods().forEach(method -> resolveMethod(stmt, method));
        endScope(stmt);
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        resolve(stmt.getExpression());
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        var varDef = declare(stmt.getName(), FunctionVarDef.class);
        define(varDef);
        resolveFunction(stmt);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        resolve(stmt.getCondition());
        resolve(stmt.getThenBranch());

        if (stmt.getElseBranch() != null)
            resolve(stmt.getElseBranch());

        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        resolve(stmt.getExpression());
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        if (stmt.getValue() != null)
            resolve(stmt.getValue());

        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        var varDef = declare(stmt.getName());
        if (stmt.getInitializer() != null) {
            resolve(stmt.getInitializer());

            if (varDef != null)
                writes.put(varDef.token, 1);
        }

        define(varDef);
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        resolve(stmt.getCondition());
        resolve(stmt.getBody());
        return null;
    }

    public sealed class VarDef permits ClassVarDef, FunctionVarDef, ParameterVarDef, SuperVarDef, ThisVarDef {

        protected final Token token;
        protected final Stmt.Function function;
        protected final boolean isGlobal;
        protected boolean isLateInit = false;
        private final Map<Token, Integer> captureDepth = new HashMap<>();

        public VarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            this.token = token;
            this.function = function;
            this.isGlobal = isGlobal;
        }

        @Override
        public String toString() {
            return token.lexeme() + "@" + this.function.getName().lexeme();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || this.getClass() != o.getClass())
                return false;

            VarDef varDef = (VarDef) o;
            return isGlobal == varDef.isGlobal && isLateInit == varDef.isLateInit && token.equals(varDef.token);
        }

        @Override
        public int hashCode() {
            return Objects.hash(token, isGlobal, isLateInit);
        }

        public Token token() {
            return token;
        }

        public Stmt.Function function() {
            return function;
        }

        public boolean isCaptured() {
            return captured.values().stream().flatMap(Collection::stream).anyMatch(it -> it == this);
        }

        public int distanceTo(Stmt.Function function) {
            return captureDepth.getOrDefault(function.getName(), 0);
        }

        public boolean isGlobal() {
            return isGlobal;
        }

        public boolean isLateInit() {
            return isLateInit;
        }

        public boolean isFinal() {
            return writes.getOrDefault(token, 0) <= 1;
        }

        public boolean isRead() {
            return function instanceof Compiler.NativeFunction || // Assume native function parameters are always read
                reads.getOrDefault(token, 0) > 0;
        }

        public String getJavaFieldName() {
            return javaFieldNames.get(token);
        }
    }

    public final class ClassVarDef extends VarDef {
        public ClassVarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            super(token, function, isGlobal);
        }
    }

    public final class FunctionVarDef extends VarDef {
        public FunctionVarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            super(token, function, isGlobal);
        }
    }

    public final class ParameterVarDef extends VarDef {
        public ParameterVarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            super(token, function, isGlobal);
        }
    }

    public final class SuperVarDef extends VarDef {
        public SuperVarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            super(token, function, isGlobal);
        }
    }

    public final class ThisVarDef extends VarDef {
        public ThisVarDef(Token token, Stmt.Function function, Boolean isGlobal) {
            super(token, function, isGlobal);
        }
    }

    public record UnresolvedLocal(Stmt.Function function, int depth, Expr varAccess, Token name) { }

    private void javaClassName(Token token, String prefix) {
        prefix = prefix + functionStack
            .stream()
            .skip(1)
            .map(this::javaClassName)
            .collect(Collectors.joining("$"));

        var newName = prefix.isBlank() ? token.lexeme() : prefix + "$" + token.lexeme();
        javaClassNames.put(token, newName + (javaClassNames.values().stream().anyMatch(it -> it.equals(newName)) ? "$" : ""));
    }

    public String javaClassName(Stmt.Class classStmt) {
        return javaClassNames.get(classStmt.getName());
    }

    public String javaClassName(Stmt.Function function) {
        return javaClassNames.get(function.getName());
    }

    private void javaFieldName(Token token, String name) {
        javaFieldNames.put(token, name + "#" + token.hashCode());
    }

    public String javaFieldName(Stmt.Function function) {
        return javaFieldNames.get(function.getName());
    }

    private void captureThisOrSuper(Stmt.Function function, VarDef thisOrSuperDef, int depth) {
        // There's no actual variables, but the depth will be used
        // to get the enclosing instance at the correct distance.
        thisOrSuperDef.captureDepth.put(function.getName(), depth);
    }

    private void capture(Stmt.Function function, VarDef varDef, int depth) {
        var captured = captured(function);

        if (!captured.contains(varDef)) {
            captured.add(varDef);
            varDef.captureDepth.put(function.getName(), depth);

            if (DEBUG)
                System.out.println("capture " + varDef + " in " + function.getName().lexeme() + " at depth " + depth);

            if (varDef instanceof ClassVarDef &&
                classStack.stream().anyMatch(it -> it.getName().equals(varDef.token()))) {
                // Capturing a self-referencing class
                // variable means it is used before it's initialized.
                // For example: test/class/reference_self.lox
                varDef.isLateInit = true;
            }
        }
    }

    public void decrementReads(VarDef varDef) {
        var current = reads.get(varDef.token());
        if (current == 1) reads.remove(varDef.token());
        else reads.replace(varDef.token(), current - 1);
    }

    @NotNull
    public Set<VarDef> captured(Stmt.Function function) {
        return captured.computeIfAbsent(function.getName(), k -> new HashSet<>());
    }

    @NotNull
    public Set<VarDef> variables(Stmt.Function function) {
        return variables.values()
                        .stream()
                        .filter(it -> it.function.getName().equals(function.getName()))
                        .collect(Collectors.toSet());
    }

    @NotNull
    public Set<VarDef> globals() {
        return variables.values()
                        .stream()
                        .filter(VarDef::isGlobal)
                        .collect(Collectors.toSet());
    }

    @Nullable
    public VarDef varDef(Token token) {
        return variables.get(token);
    }

    @NotNull
    public Optional<VarDef> varDef(Expr varAccess) {
        if (varAccess instanceof Expr.Variable v) {
            return Optional.ofNullable(varUse.get(v.getName()));
        } else if (varAccess instanceof Expr.Assign v) {
            return Optional.ofNullable(varUse.get(v.getName()));
        } else if (varAccess instanceof Expr.Super v) {
            return Optional.ofNullable(varUse.get(v.getKeyword()));
        } else if (varAccess instanceof Expr.This v) {
            return Optional.ofNullable(varUse.get(v.getKeyword()));
        } else {
            throw new IllegalArgumentException("Invalid varAccess: " + varAccess);
        }
    }
}
