package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.CompilerResolver.VarDef;
import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;

import java.util.*;

public class VariableAllocator implements Stmt.Visitor<Void>, Expr.Visitor<Void> {

    private static final boolean DEBUG = System.getProperty("lox.variableallocator.debug") != null;

    private final CompilerResolver resolver;
    private final Stack<Stmt.Function> functionStack = new Stack<>();
    private final Deque<Map<VarDef, Boolean>> scopes = new ArrayDeque<>();
    private final Map<Token, Map<VarDef, Slot>> slots = new HashMap<>();

    public VariableAllocator(CompilerResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Returns the slot number for the specified variable in the specified
     * function.
     */
    public int slot(Stmt.Function function, VarDef varDef) {
        var slot = slots(function)
                .entrySet()
                .stream()
                .filter(it -> it.getKey().equals(varDef))
                .map(Map.Entry::getValue)
                .findFirst();

        return slot.orElseThrow().number;
    }

    public void resolve(Stmt.Function function) {
        resolveFunction(function);
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

    private void resolveFunction(Stmt.Function function) {
        this.beginScope(function);

        for (Token param : function.getParams()) {
            this.declare(param);
        }

        // Assign slots for variables captured by this function.
        this.resolver.captured(function).stream().filter(it -> !it.isGlobal()).filter(VarDef::isRead).forEach(
                varDef -> this.slots(function).put(varDef, new Slot(function, this.nextSlotNumber(function), true))
        );

        this.resolve(function.getBody());
        this.endScope(function);
    }

    private void beginScope(Stmt.Function function) {
        this.functionStack.push(function);
        this.beginScope();
    }

    private void beginScope() {
        this.scopes.push(new HashMap<>());
    }

    private void endScope() {
        // At the end of each scope, the variable slot can be re-used.
        this.scopes.peek().keySet().forEach(it -> free(functionStack.peek(), it));
        this.scopes.pop();
    }

    private void free(Stmt.Function function, VarDef it) {
        Map<VarDef, Slot> slot = this.slots(function).get(it);

        if (slot != null)
            slot.isUsed = false;

        if (DEBUG) System.out.println("freeing " + it.token().lexeme + " from slot " + slot + " in function " + function.name.lexeme);
    }

    private void endScope(Stmt.Function ignoredFunction) {
        endScope();
        functionStack.pop();
    }

    private void declare(Token name) {
        if (scopes.isEmpty()) return;

        var varDef = resolver.varDef(name);

        if (!varDef.isRead()) return;

        var currentFunction = functionStack.peek();
        boolean isAlreadyDeclared = slots(currentFunction).containsKey(varDef);
        boolean isGlobalScope = scopes.size() == 1;

        if (isAlreadyDeclared && isGlobalScope) {
             // Global scope allows redefinitions so no need to assign a new slot.
            return;
        }

        scopes.peek().put(varDef, false);
        int slot = nextSlotNumber(currentFunction);
        slots(currentFunction).put(varDef, new Slot(currentFunction, slot, true));

        if (DEBUG) System.out.println("assigning " + varDef + " to slot " + slot + " in " + currentFunction.name.lexeme);
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        resolve(expr.value);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        resolve(expr.left);
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        this.resolve(expr.getCallee());
        expr.getArguments().forEach(this::resolve);
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        this.resolve(expr.getObject());
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        this.resolve(expr.getExpression());
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        this.resolve(expr.getLeft());
        this.resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        this.resolve(expr.getObject());
        this.resolve(expr.getValue());
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {
        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {
        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        this.resolve(expr.getRight());
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        return null;
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        this.beginScope();
        this.resolve(stmt.getStatements());

        this.endScope();
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        this.declare(stmt.getName());
        if (stmt.getSuperclass() != null)
            this.resolve(stmt.getSuperclass());

        this.beginScope();
        stmt.getMethods().forEach(this::resolveFunction);

        this.endScope();
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        resolve(stmt.getExpression());
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        this.declare(stmt.getName());
        this.resolveFunction(stmt);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        this.resolve(stmt.getCondition());
        this.resolve(stmt.getThenBranch());

        if (stmt.getElseBranch() != null)
            this.resolve(stmt.getElseBranch());

        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        this.resolve(stmt.getExpression());
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        if (stmt.getValue() != null)
            this.resolve(stmt.getValue());

        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        this.declare(stmt.getName());
        if (stmt.getInitializer() != null)
            this.resolve(stmt.getInitializer());

        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        this.resolve(stmt.getCondition());
        this.resolve(stmt.getBody());
        return null;
    }

    private Map<VarDef, Slot> slots(Stmt.Function function) {
        return this.slots.computeIfAbsent(function.getName(), k -> new WeakHashMap<>());
    }

    private int nextSlotNumber(Stmt.Function function) {
        Map<VarDef, Slot> slots = this.slots(function);

        if (slots != null) {
            var firstFreeSlot = slots.entrySet().stream().filter(entry -> !entry.getValue().isUsed)
                    .min(Comparator.comparingInt(it -> it.getValue().number));

            if (firstFreeSlot.isPresent()) {
                firstFreeSlot.get().getValue().isUsed = true;
                return firstFreeSlot.get().getValue().number;
            } else {
                Optional<Slot> maxSlot = slots.values().stream().max(Comparator.comparingInt(it -> it.number));
                return maxSlot.map(slot -> slot.number).orElse(0) + 1;
            }
        }

        return 0;
    }

    private static class Slot {
        private final Stmt.Function function;
        public final int number;

        private boolean isUsed;

        public Slot(Stmt.Function function, int number, boolean isUsed) {
            this.function = function;
            this.number = number;

            this.isUsed = isUsed;
        }

        public String toString() {
            return this.function.getName().lexeme() + "@" + this.number + (this.isUsed ? " (used)" : " (unused)");
        }
    }
}
