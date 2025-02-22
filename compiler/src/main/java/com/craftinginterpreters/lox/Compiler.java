package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.CompilerResolver.VarDef;
import com.craftinginterpreters.lox.ast.Expr;
import com.craftinginterpreters.lox.ast.Stmt;
import com.craftinginterpreters.lox.lexer.Token;
import lox.LoxNative;
import org.jetbrains.annotations.Nullable;
import proguard.classfile.ClassPool;
import proguard.classfile.ProgramClass;
import proguard.classfile.ProgramMethod;
import proguard.classfile.attribute.Attribute;
import proguard.classfile.attribute.BootstrapMethodInfo;
import proguard.classfile.attribute.visitor.AllAttributeVisitor;
import proguard.classfile.attribute.visitor.AttributeNameFilter;
import proguard.classfile.attribute.visitor.MultiAttributeVisitor;
import proguard.classfile.editor.BootstrapMethodsAttributeAdder;
import proguard.classfile.editor.ClassBuilder;
import proguard.classfile.editor.CompactCodeAttributeComposer;
import proguard.classfile.editor.CompactCodeAttributeComposer.Label;
import proguard.classfile.editor.ConstantPoolEditor;
import proguard.classfile.editor.LineNumberTableAttributeTrimmer;
import proguard.classfile.io.ProgramClassReader;
import proguard.classfile.visitor.AllMethodVisitor;
import proguard.classfile.visitor.ClassPrinter;
import proguard.classfile.visitor.ClassVersionFilter;
import proguard.preverify.CodePreverifier;

import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.craftinginterpreters.lox.Lox.hadError;
import static com.craftinginterpreters.lox.Lox.hadRuntimeError;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CALLABLE;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CAPTURED;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CLASS;
import static com.craftinginterpreters.lox.LoxConstants.LOX_FUNCTION;
import static com.craftinginterpreters.lox.LoxConstants.LOX_INSTANCE;
import static com.craftinginterpreters.lox.LoxConstants.LOX_INVOKER;
import static com.craftinginterpreters.lox.LoxConstants.LOX_MAIN_CLASS;
import static com.craftinginterpreters.lox.LoxConstants.LOX_METHOD;
import static com.craftinginterpreters.lox.LoxConstants.LOX_NATIVE;
import static com.craftinginterpreters.lox.lexer.TokenType.FUN;
import static com.craftinginterpreters.lox.lexer.TokenType.IDENTIFIER;
import static java.util.Collections.emptyList;
import static proguard.classfile.AccessConstants.FINAL;
import static proguard.classfile.AccessConstants.PRIVATE;
import static proguard.classfile.AccessConstants.PUBLIC;
import static proguard.classfile.AccessConstants.STATIC;
import static proguard.classfile.AccessConstants.VARARGS;
import static proguard.classfile.VersionConstants.CLASS_VERSION_1_8;
import static proguard.classfile.constant.MethodHandleConstant.REF_INVOKE_STATIC;
import static proguard.classfile.util.ClassUtil.internalClassName;

public class Compiler {

    private static final boolean DEBUG = System.getProperty("jlox.compiler.debug") != null;

    private final ClassPool programClassPool = new ClassPool();
    private final CompilerResolver resolver = new CompilerResolver();
    private final VariableAllocator allocator = new VariableAllocator(this.resolver);

    public @Nullable ClassPool compile(Collection<Stmt> program) {
        Compiler.addClass(
            programClassPool,
            lox.LoxCallable.class,
            lox.LoxCaptured.class,
            lox.LoxClass.class,
            lox.LoxException.class,
            lox.LoxFunction.class,
            lox.LoxInstance.class,
            lox.LoxInvoker.class,
            lox.LoxMethod.class,
            lox.LoxNative.class
        );

        Stmt.Function mainFunction = new Stmt.Function(
                new Token(FUN, LOX_MAIN_CLASS, null, 0),
                Collections.emptyList(), this.prependNative(program)
        );

        this.resolver.resolve(mainFunction);

        if (hadError || hadRuntimeError)
            return null;

        mainFunction = new Optimizer(this.resolver).execute(mainFunction, 3);

        if (hadError || hadRuntimeError)
            return null;

        this.allocator.resolve(mainFunction);

        ProgramClass mainMethodClass = new FunctionCompiler().compile(mainFunction);

        new ClassBuilder(mainMethodClass)
            .addMethod(PUBLIC | STATIC, "main", "([Ljava/lang/String;)V", 65_535, composer -> {
                LoxComposer loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                var error = loxComposer.createLabel();
                //noinspection unchecked
                loxComposer
                    .try_(it -> it.new_(it.getTargetClass().getName()).dup().aconst_null()
                            .invokespecial(it.getTargetClass().getName(), "<init>",
                                    "(L" + LOX_CALLABLE + ";)V"
                            ).aconst_null().invokeinterface(LOX_CALLABLE, "invoke",
                                    "([Ljava/lang/Object;)Ljava/lang/Object;"
                            ).pop().return_(), __ -> __
                    .catch_("java/lang/StackOverflowError", it -> {
                        if (!DEBUG)
                            it.pop();

                        if (DEBUG)
                            it.invokevirtual("java/lang/Throwable", "printStackTrace", "()V");

                        return it.getstatic("java/lang/System", "err", "Ljava/io/PrintStream;")
                                .ldc("Stack overflow.").invokevirtual("java/io/PrintStream",
                                        "println", "(Ljava/lang/Object;)V"
                                ).goto_(error);
                    }).catchAll(it -> {
                        if (DEBUG)
                            it.dup();

                        it.getstatic("java/lang/System", "err", "Ljava/io/PrintStream;").swap()
                          .invokevirtual("java/lang/Throwable", "getMessage", "()Ljava/lang/String;")
                          .invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");

                        if (DEBUG)
                            it.invokevirtual("java/lang/Throwable", "printStackTrace", "()V");

                        return it.goto_(error);
                    }))
                    .label(error).iconst(70).invokestatic("java/lang/System",
                                "exit", "(I)V").return_();
                });

        this.programClassPool.addClass(mainMethodClass);

        ClassPool classPool = this.preverify(this.programClassPool);
        if (DEBUG) classPool.classesAccept("!lox/**", new ClassPrinter());
        return classPool;
    }

    private ClassPool preverify(ClassPool programClassPool) {
        programClassPool.classesAccept(clazz -> {
            try {
                clazz.accept(new ClassVersionFilter(CLASS_VERSION_1_8,
                    new AllMethodVisitor(new AllAttributeVisitor(new AttributeNameFilter(
                            Attribute.CODE, new MultiAttributeVisitor(new CodePreverifier(false),
                            new AllAttributeVisitor(new LineNumberTableAttributeTrimmer()))))))
                ); // TODO: see local_mutual_recursion.loxisEven; unreachable code is removed by CodePreverifier and the line numbers are not updated
            } catch (Exception e) {
                clazz.accept(new ClassPrinter());
                throw e;
            }
        });

        return programClassPool;
    }

    private class FunctionCompiler implements Stmt.Visitor<LoxComposer>, Expr.Visitor<LoxComposer> {

        private LoxComposer composer;
        private Stmt.Function currentFunction;
        private Stmt.Class currentClass;

        public ProgramClass compile(Stmt.Function functionStmt) {
            return compile(null, functionStmt);
        }

        private ProgramClass compile(Stmt.Class classStmt, Stmt.Function functionStmt) {
            this.currentFunction = functionStmt;
            this.currentClass = classStmt;

            ProgramClass programClass = this.createFunctionClass(classStmt, functionStmt);
            ProgramMethod invokeMethod = (ProgramMethod) programClass.findMethod("invoke", null);

            this.composer = new LoxComposer(new CompactCodeAttributeComposer(programClass), programClassPool, resolver, allocator);
            this.composer.beginCodeFragment(65_535);

            var params = functionStmt.getParams()
                .stream()
                .map(resolver::varDef)
                .toList();

            if (functionStmt instanceof NativeFunction) {
                if (!params.isEmpty()) {
                    this.composer.aload_1().unpack(params.size());
                }

                this.composer.invokestatic(LOX_NATIVE, functionStmt.getName().lexeme(), "(Ljava/lang/Object;"
                                .repeat(functionStmt.getParams().size()) + ")Ljava/lang/Object;").areturn();
            } else {
                if (params.stream().anyMatch(VarDef::isRead)) {
                    this.composer.aload_1();
                    for (int i = 0; i < params.size(); i++) {
                        if (params.get(i).isRead()) {
                            this.composer
                                .dup() // the param array
                                .pushInt(i)
                                .aaload()
                                .declare(params.get(i));
                        }
                    }

                    this.composer.pop();
                }

                resolver
                    .captured(functionStmt)
                    .stream()
                    .filter(it -> !it.isGlobal())
                    .filter(VarDef::isRead)
                    .forEach(captured -> composer
                        .aload_0()
                        .getfield(composer.getTargetClass().getName(), captured.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                        .astore(allocator.slot(functionStmt, captured))
                    );

                functionStmt.getBody().forEach(
                    stmt -> stmt.accept(this)
                );

                if (functionStmt.getBody().stream().noneMatch(stmt -> stmt instanceof Stmt.Return)) {
                    if (classStmt != null && functionStmt.getName().lexeme().equals("init")) {
                        composer
                            .aload_0()
                            .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";")
                            .areturn();
                    } else {
                        composer
                            .aconst_null()
                            .areturn();
                    }
                }
            }
            composer.endCodeFragment();
            try {
                composer.addCodeAttribute(programClass, invokeMethod);
            } catch (Exception e) {
                composer.getCodeAttribute().accept(programClass, invokeMethod, new ClassPrinter());
                throw e;
            }

            return programClass;
        }

        private ProgramClass createFunctionClass(Stmt.Class classStmt, Stmt.Function function) {
            boolean isMain = resolver.javaClassName(function).equals(LOX_MAIN_CLASS);
            boolean isMethod = classStmt != null;
            var classBuilder = new ClassBuilder(
                CLASS_VERSION_1_8,
                PUBLIC,
                resolver.javaClassName(function),
                isMethod ? LOX_METHOD : LOX_FUNCTION
            )
            .addMethod(PUBLIC, "getName", "()Ljava/lang/String;", 10, composer -> composer
                .ldc(function.getName().lexeme())
                .areturn())
            .addMethod(PUBLIC, "arity", "()I", 10, composer -> composer
                .pushInt(function.getParams().size())
                .ireturn())
            .addMethod(PUBLIC | VARARGS, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;");

            var variables = resolver.variables(function);
            var variablesCapturedByFunction = resolver.captured(function).stream().filter(VarDef::isRead).toList();
            var capturedVariablesDeclaredInFunction = variables.stream().filter(VarDef::isCaptured).filter(VarDef::isRead);
            var lateInitVars = variables.stream().filter(VarDef::isLateInit).toList();

            if (isMain) {
                // main can't capture variables, but variables declared in main can be captured.
                capturedVariablesDeclaredInFunction.forEach(global -> classBuilder.
                    addField(PUBLIC | STATIC, global.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                );
            } else {
                Stream.concat(variablesCapturedByFunction.stream(), capturedVariablesDeclaredInFunction)
                    .distinct()
                    .forEach(captured -> classBuilder
                        .addField(PUBLIC, captured.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                    );
            }

            Function<LoxComposer, LoxComposer> captureComposer = composer -> {
                if (!variablesCapturedByFunction.isEmpty()) {
                    composer.aload_0();
                    variablesCapturedByFunction.forEach(varDef -> {
                        if (varDef.isGlobal()) {
                            composer
                                .dup()
                                .getstatic(LOX_MAIN_CLASS, varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                                .putfield(resolver.javaClassName(function), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        } else {
                            composer
                                .dup()
                                .aload_0();

                            int distance = varDef.distanceTo(function);
                            if (distance > 0) {
                                composer
                                    .iconst(distance)
                                    .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE + ";")
                                    .checkcast(resolver.javaClassName(varDef.function()));
                            }

                            composer
                                .getfield(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                                .putfield(resolver.javaClassName(function), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        }
                    });
                    composer.pop();
                }
                return composer;
            };

            if (isMain) {
                if (!lateInitVars.isEmpty()) {
                    classBuilder.addMethod(PRIVATE | STATIC, "<clinit>", "()V", 65535, composer -> {
                        var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                        lateInitVars.forEach(varDef -> loxComposer
                            .aconst_null()
                            .box(varDef)
                            .putstatic(LOX_MAIN_CLASS, varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";"));
                        composer.return_();
                    });
                }
            }

            classBuilder
                .addMethod(PUBLIC, "<init>", "(L" + (isMethod ? LOX_CLASS : LOX_CALLABLE) + ";)V", 100, composer -> {
                    var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                    loxComposer
                        .aload_0()
                        .aload_1()
                        .invokespecial(isMethod ? LOX_METHOD : LOX_FUNCTION, "<init>", "(L" + (isMethod ? LOX_CLASS : LOX_CALLABLE) + ";)V");

                    if (!isMain) {
                        lateInitVars.forEach(varDef -> loxComposer
                            .aload_0()
                            .aconst_null()
                            .box(varDef)
                            .putfield(loxComposer.getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";"));
                    }

                    // Capturing a method's variables can be done here, since we don't need
                    // store the method before capturing as a method itself cannot be captured.
                    if (isMethod) captureComposer.apply(loxComposer);

                    loxComposer.return_();
                });

            if (!isMethod && !variablesCapturedByFunction.isEmpty()) {
                classBuilder.addMethod(PUBLIC, "capture", "()V", 65_535, composer -> captureComposer
                    .apply(new LoxComposer(composer, programClassPool, resolver, allocator))
                    .return_());
            }

            if (function instanceof NativeFunction) {
                // For compatibility with Lox all native functions print `<native fn>`.
                classBuilder.addMethod(
                        PUBLIC, "toString", "()Ljava/lang/String;", 10, composer -> composer
                                .ldc("<native fn>")
                                .areturn()
                );
            }

            var programClass = classBuilder.getProgramClass();

            if (new FunctionCallCounter().count(function) > 0) {
                addBootstrapMethod(programClass);
            }

            programClassPool.addClass(programClass);

            return programClass;
        }

        private ProgramClass createClass(Stmt.Class classStmt) {
            ClassBuilder classBuilder = new ClassBuilder(
                CLASS_VERSION_1_8,
                PUBLIC,
                resolver.javaClassName(classStmt),
                LOX_CLASS
            );

            classBuilder
                .addMethod(PUBLIC, "<init>", "(L" + LOX_CALLABLE + ";" + (classStmt.getSuperclass() != null ? "L" + LOX_CLASS + ";" : "") + ")V", 65_535, composer -> new LoxComposer(composer, programClassPool, resolver, allocator)
                    .aload_0()
                    .aload_1()
                    .also(__ -> classStmt.getSuperclass() != null ? __.aload_2() : __.aconst_null())
                    .invokespecial(LOX_CLASS, "<init>", "(L" + LOX_CALLABLE + ";L" + LOX_CLASS + ";)V")
                    .also(methodInitializer -> {
                        for (var method : classStmt.getMethods()) {
                            classBuilder.addField(PRIVATE | FINAL, resolver.javaFieldName(method), "L" + LOX_METHOD + ";");
                            var methodClazz = new FunctionCompiler().compile(classStmt, method);

                            methodInitializer
                                .line(method.getName().line())
                                .aload_0()
                                .new_(methodClazz)
                                .dup()
                                .aload_0()
                                .invokespecial(methodClazz.getName(), "<init>", "(L" + LOX_CLASS + ";)V")
                                .putfield(methodInitializer.getTargetClass().getName(), resolver.javaFieldName(method), "L" + LOX_METHOD + ";");
                        }
                        return methodInitializer;
                    })
                    .return_())
                .addMethod(PUBLIC, "findMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";", 500, composer -> new LoxComposer(composer, programClassPool, resolver, allocator)
                    .aload_1()
                    .switch_(2, switchBuilder -> {
                        classStmt.getMethods().forEach(method -> switchBuilder.case_(
                            method.getName().lexeme(),
                            caseComposer -> caseComposer
                                .aload_0()
                                .getfield(classBuilder.getProgramClass().getName(), resolver.javaFieldName(method), "L" + LOX_METHOD + ";")
                                .areturn()
                        ));
                        return switchBuilder.default_(defaultComposer -> {
                            if (classStmt.getSuperclass() == null) {
                                return defaultComposer
                                        .aconst_null()
                                        .areturn();
                            } else {
                                return defaultComposer
                                        .aload_0()
                                        .invokevirtual(classBuilder.getProgramClass().getName(), "getSuperClass", "()L" + LOX_CLASS + ";")
                                        .aload_1()
                                        .invokevirtual(LOX_CLASS, "findMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";")
                                        .areturn();
                            }
                        });
                    }))
                .addMethod(PUBLIC, "getName", "()Ljava/lang/String;", 10, composer -> composer
                    .ldc(classStmt.getName().lexeme())
                    .areturn());

            var clazz = classBuilder.getProgramClass();
            programClassPool.addClass(clazz);
            return clazz;
        }

        @Override
        public LoxComposer visitBlockStmt(Stmt.Block blockStmt) {
            blockStmt.getStatements().forEach(stmt -> stmt.accept(this));
            return composer;
        }

        @Override
        public LoxComposer visitClassStmt(Stmt.Class classStmt) {
            var clazz = createClass(classStmt);

            composer
                .new_(clazz)
                .dup()
                .aload_0();

            if (classStmt.getSuperclass() != null) {
                classStmt.getSuperclass().accept(this);
                var isClass = composer.createLabel();
                composer
                    .line(classStmt.getSuperclass().getName().line())
                    .dup()
                    .instanceof_(LOX_CLASS)
                    .ifne(isClass)
                    .pop()
                    .loxthrow("Superclass must be a class.")

                    .label(isClass)
                    .checkcast(LOX_CLASS)
                    .invokespecial(clazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";L" + LOX_CLASS + ";)V");
            } else {
                composer.invokespecial(clazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";)V");
            }

            return composer
                .line(classStmt.getName().line())
                .declare(resolver.varDef(classStmt.getName()));
        }

        @Override
        public LoxComposer visitExpressionStmt(Stmt.Expression expressionStmt) {
            expressionStmt.getExpression().accept(this);
            var expectedStackSize = expressionStmt.getExpression().accept(new StackSizeComputer());
            for (int i = 0; i < expectedStackSize; i++) composer.pop();
            return composer;
        }

        @Override
        public LoxComposer visitFunctionStmt(Stmt.Function functionStmt) {
            var functionClazz = new FunctionCompiler().compile(functionStmt);

            boolean capturesAnyVariables = resolver
                .captured(functionStmt)
                .stream()
                .anyMatch(VarDef::isRead);

            composer
                .new_(functionClazz)
                .also(loxComposer -> capturesAnyVariables ? loxComposer.dup().dup() : loxComposer.dup())
                .aload_0()
                .invokespecial(functionClazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";)V");

            composer
                .line(functionStmt.getName().line())
                .declare(resolver.varDef(functionStmt.getName()));

            if (capturesAnyVariables)
                composer.invokevirtual(functionClazz.getName(), "capture", "()V");

            return composer;
        }

        @Override
        public LoxComposer visitIfStmt(Stmt.If stmt) {
            var endLabel = composer.createLabel();
            var elseBranch = composer.createLabel();
            return stmt.getCondition().accept(this)
                    .ifnottruthy(elseBranch)
                    .also(composer -> stmt.getThenBranch().accept(this))
                    .goto_(endLabel)
                    .label(elseBranch)
                    .also(composer -> stmt.getElseBranch() != null ? stmt.getElseBranch().accept(this) : composer)
                    .label(endLabel);
        }

        @Override
        public LoxComposer visitPrintStmt(Stmt.Print stmt) {
            return composer
                .also(composer -> stmt.getExpression().accept(this))
                .outline(programClassPool, LOX_MAIN_CLASS, "println", "(Ljava/lang/Object;)V", composer -> {
                    var nonNull = composer.createLabel();
                    var isObject = composer.createLabel();
                    var end = composer.createLabel();
                    // Stringify before printing.
                    composer
                        .dup()
                        // O, O
                        .ifnonnull(nonNull)
                        // O
                        .pop()
                        //
                        .ldc("nil")
                        // "nil"
                        .goto_(end)

                        .label(nonNull)
                        // O
                        .dup()
                        // O, O
                        .instanceof_("java/lang/Double", null)
                        // O, I
                        .ifeq(isObject)
                        // O
                        .invokevirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        // O.toString
                        .dup()
                        // O.toString, O.toString
                        .ldc(".0")
                        // O.toString, O.toString, ".0"
                        .invokevirtual("java/lang/String", "endsWith", "(Ljava/lang/String;)Z")
                        // O.toString, Z
                        .ifeq(end)
                        // O.toString
                        .dup()
                        // O.toString, O.toString
                        .iconst_0()
                        // O.toString, O.toString, 0
                        .swap()
                        // O.toString, 0, O.toString
                        .invokevirtual("java/lang/String", "length", "()I")
                        // O.toString, 0, O.toString.length
                        .iconst_2()
                        // O.toString, 0, O.toString.length, 2
                        .isub()
                        // O.toString, 0, O.toString.length - 2
                        .invokevirtual("java/lang/String", "substring", "(II)Ljava/lang/String;")
                        // S
                        .goto_(end)

                        .label(isObject)
                        // O
                        .invokevirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        // S
                        .label(end)
                        .getstatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                        .swap()
                        .invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");
                    }
                );
        }

        @Override
        public LoxComposer visitReturnStmt(Stmt.Return stmt) {
            if (stmt.getValue() != null)
                return stmt.getValue().accept(this)
                        .line(stmt.getKeyword().line())
                        .areturn();
            else if (currentClass != null && currentFunction.getName().lexeme().equals("init"))
                return composer
                        .aload_0()
                        .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";")
                        .line(stmt.getKeyword().line())
                        .areturn();
            else
                return composer
                        .aconst_null()
                        .line(stmt.getKeyword().line())
                        .areturn();
        }

        @Override
        public LoxComposer visitVarStmt(Stmt.Var stmt) {
            if (stmt.getInitializer() != null) stmt.getInitializer().accept(this);
            else composer.aconst_null();

            return composer
                    .line(stmt.getName().line())
                    .declare(resolver.varDef(stmt.getName()));
        }

        @Override
        public LoxComposer visitWhileStmt(Stmt.While stmt) {
            var condition = composer.createLabel();
            var body = composer.createLabel();
            var end = composer.createLabel();

            return composer
                .label(condition)
                .also(composer -> stmt.getCondition().accept(this))
                .ifnottruthy(end)
                .label(body)
                .also(composer -> stmt.getBody().accept(this))
                .goto_(condition)
                .label(end);
        }

        @Override
        public LoxComposer visitAssignExpr(Expr.Assign expr) {
            composer.line(expr.getName().line());
            resolver.varDef(expr).ifPresentOrElse(
                varDef -> expr.getValue()
                    .accept(this)
                    .dup()
                    .line(expr.getName().line())
                    .store(currentFunction, varDef.token()),
                () -> composer.loxthrow("Undefined variable '" + expr.getName().lexeme() + "'.")
            );
            return composer;
        }

        @Override
        public LoxComposer visitBinaryExpr(Expr.Binary expr) {
            switch (expr.getOperator().type()) {
                // These don't require number operands.
                case EQUAL_EQUAL, BANG_EQUAL, PLUS -> {
                    expr.getLeft().accept(this);
                    expr.getRight().accept(this);
                }
                // These require 2 number operands.
                case MINUS, SLASH, STAR, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL -> {
                     expr.getLeft().accept(this)
                    .line(expr.getOperator().line())
                    .unbox("java/lang/Double", "Operands must be numbers.");
                     expr.getRight().accept(this)
                    .unbox("java/lang/Double", "Operands must be numbers.");
                }
            }

            composer.line(expr.getOperator().line());

            BiFunction<String, Function<LoxComposer, LoxComposer>, LoxComposer> binaryNumberOp = (resultType, op) ->
                     op.apply(composer).box(resultType);

            Function<BiFunction<LoxComposer, Label, LoxComposer>, LoxComposer> comparisonOp = op -> binaryNumberOp.apply("java/lang/Boolean", composer -> {
                var falseBranch = composer.createLabel();
                var end = composer.createLabel();
                return op.apply(composer, falseBranch)
                         .iconst_1()
                         .goto_(end)
                         .label(falseBranch)
                         .iconst_0()
                         .label(end);
            });

            return switch (expr.getOperator().type()) {
                case EQUAL_EQUAL -> composer
                    .invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                    .box("java/lang/Boolean");
                case BANG_EQUAL -> composer
                    .invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                    .iconst_1()
                    .ixor()
                    .box("java/lang/Boolean");
                case PLUS -> composer.outline(programClassPool, LOX_MAIN_CLASS, "add", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", outlineComposer -> {
                    var composer = new LoxComposer(outlineComposer, programClassPool, resolver, allocator);
                    var bothDouble = composer.createLabel();
                    var checkAbIsString = composer.createLabel();
                    var checkBaIsString = composer.createLabel();
                    var bothString = composer.createLabel();
                    var throwException = composer.createLabel();
                    var throwExceptionPop = composer.createLabel();
                    var end = composer.createLabel();
                    //noinspection unchecked
                    composer
                            .dup()
                            // A, B, B
                            .instanceof_("java/lang/Double")
                            // A, B, Z
                            .ifeq(checkAbIsString)
                            // A, B
                            .swap()
                            // B, A
                            .dup()
                            // B, A, A
                            .instanceof_("java/lang/Double")
                            // B, A, Z
                            .ifeq(checkBaIsString)
                            // B, A
                            .swap()
                            // A, B
                            .label(bothDouble)
                            .unbox("java/lang/Double")
                            // A, Ba, Bb
                            .dup2_x1()
                            // Ba, Bb, A, Ba, Bb
                            .pop2()
                            // Ba, Bb, A
                            .unbox("java/lang/Double")
                            // Ba, Bb, Aa, Ab
                            .dadd()
                            // Ba, Bb + Aa, Ab
                            .box("java/lang/Double")
                            // A + B
                            .goto_(end)

                            .label(checkBaIsString)
                            .swap()
                            .label(checkAbIsString)
                            // A, B
                            .dup()
                            // A, B, B
                            .instanceof_("java/lang/String")
                            // A, B, Z
                            .ifeq(throwExceptionPop)
                            // A, B
                            .swap()
                            // B, A
                            .instanceof_("java/lang/String")
                            // B, Z
                            .ifeq(throwException)
                            // B
                            .pop()
                            //
                            .label(bothString)
                            .concat(
                                CompactCodeAttributeComposer::aload_0,
                                CompactCodeAttributeComposer::aload_1
                            )
                            .goto_(end)

                            .label(throwExceptionPop)
                            .pop()
                            .label(throwException)
                            .pop()
                            .loxthrow("Operands must be two numbers or two strings.")

                            .label(end);
                    }
                );

                case MINUS -> binaryNumberOp.apply("java/lang/Double", Composer::dsub);
                case SLASH -> binaryNumberOp.apply("java/lang/Double", Composer::ddiv);
                case STAR -> binaryNumberOp.apply("java/lang/Double", Composer::dmul);

                case GREATER -> comparisonOp.apply((composer, label) -> composer
                    .dcmpl()
                    .ifle(label)
                );
                case GREATER_EQUAL -> comparisonOp.apply((composer, label) -> composer
                    .dcmpl()
                    .iflt(label)
                );
                case LESS -> comparisonOp.apply((composer, label) -> composer
                    .dcmpg()
                    .ifge(label)
                );
                case LESS_EQUAL -> comparisonOp.apply((composer, label) -> composer
                    .dcmpg()
                    .ifgt(label)
                );

                default -> throw new IllegalStateException("Unexpected value: " + expr.getOperator());
            };
        }

        @Override
        public LoxComposer visitCallExpr(Expr.Call expr) {
            return expr.getCallee().accept(this)
                .also(composer -> {
                    expr.getArguments().forEach(it -> it.accept(this));
                    return composer;
                })
                .line(expr.getParen().line())
                .invokedynamic(
                        0,
                        "invoke", "(Ljava/lang/Object;" + ("Ljava/lang/Object;".repeat(expr.getArguments().size())) + ")Ljava/lang/Object;",
                        null);
        }

        @Override
        public LoxComposer visitGetExpr(Expr.Get expr) {
            return composer
                .ldc(expr.getName().lexeme())
                .also(composer -> expr.getObject().accept(this))
                .line(expr.getName().line())
                .outline(programClassPool, LOX_MAIN_CLASS, "get", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", composer -> {
                   var notInstance = composer.createLabel();
                   var end = composer.createLabel();
                   var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);

                   loxComposer
                       .dup()
                       .instanceof_(LOX_INSTANCE)
                       .ifeq(notInstance)
                       .checkcast(LOX_INSTANCE)
                       .swap()
                       .invokevirtual(LOX_INSTANCE, "get", "(Ljava/lang/String;)Ljava/lang/Object;")
                       .goto_(end)

                       .label(notInstance)
                       .pop()
                       .loxthrow("Only instances have properties.")

                       .label(end);
                });
        }

        @Override
        public LoxComposer visitGroupingExpr(Expr.Grouping expr) {
            return expr.getExpression().accept(this);
        }

        @Override
        public LoxComposer visitLiteralExpr(Expr.Literal expr) {
            if (expr.getValue() instanceof Boolean b) {
                return b ?
                        composer.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;") :
                        composer.getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
            } else if (expr.getValue() instanceof String s) {
                return composer.ldc(s);
            } else if (expr.getValue() instanceof Double d) {
                return composer.pushDouble(d).box("java/lang/Double");
            } else if (expr.getValue() == null) {
                return composer.aconst_null();
            } else {
                throw new IllegalArgumentException("Unknown literal type: " + expr.getValue());
            }
        }

        @Override
        public LoxComposer visitLogicalExpr(Expr.Logical expr) {
            var end = composer.createLabel();
            return switch (expr.getOperator().type()) {
                case OR -> expr.getLeft().accept(this)
                        .dup()
                        .iftruthy(end)
                        .pop()
                        .also(composer -> expr.getRight().accept(this))
                        .label(end);
                case AND -> expr.getLeft().accept(this)
                        .dup()
                        .ifnottruthy(end)
                        .pop()
                        .also(composer -> expr.getRight().accept(this))
                        .label(end);
                default -> throw new IllegalArgumentException("Unsupported logical expr type: " + expr.getOperator().type());
            };
        }

        @Override
        public LoxComposer visitSetExpr(Expr.Set expr) {
            var notInstance = composer.createLabel();
            var end = composer.createLabel();
            return expr.getObject().accept(this)
                    .dup()
                    .instanceof_(LOX_INSTANCE)
                    .ifeq(notInstance)
                    .checkcast(LOX_INSTANCE)
                    .line(expr.getName().line())
                    .ldc(expr.getName().lexeme())
                    .also(composer1 -> expr.getValue().accept(this))
                    .dup_x2()
                    .invokevirtual(LOX_INSTANCE, "set", "(Ljava/lang/String;Ljava/lang/Object;)V")
                    .goto_(end)

                    .label(notInstance)
                    .pop()
                    .loxthrow("Only instances have fields.")

                    .label(end);
        }

        @Override
        public LoxComposer visitSuperExpr(Expr.Super expr) {
            composer
                .line(expr.getMethod().line())
                // First get the closest method
                .aload_0();

            int distance = resolver.varDef(expr).orElseThrow().distanceTo(currentFunction);
            if (distance > 0) {
                composer
                    .iconst(distance)
                    .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE +";")
                    .checkcast(LOX_METHOD);
            }

            composer
                .dup() // thismethod, thismethod
                // Then get the class in which the method is defined
                .invokevirtual(LOX_METHOD, "getLoxClass", "()L" + LOX_CLASS + ";")
                .ldc(expr.getMethod().lexeme()) // thismethod, class, fieldname
                // Then find the super method
                .invokevirtual(LOX_CLASS, "findSuperMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";") // thismethod, supermethod
                .swap() // supermethod, thismethod
                .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";") // supermethod, thisinstance
                // Finally, bind the instance to the super method
                .invokevirtual(LOX_METHOD, "bind", "(L" + LOX_INSTANCE + ";)L" + LOX_METHOD + ";"); // []

            return composer;
        }

        @Override
        public LoxComposer visitThisExpr(Expr.This expr) {
            int distance = resolver.varDef(expr).orElseThrow().distanceTo(currentFunction);
            composer.aload_0();
            if (distance > 0) {
                composer
                    .iconst(distance)
                    .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE + ";")
                    .checkcast(LOX_METHOD);
            }
            return composer.invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";");
        }

        @Override
        public LoxComposer visitUnaryExpr(Expr.Unary expr) {
            composer.line(expr.getOperator().line());
            expr.getRight().accept(this);
            switch (expr.getOperator().type()) {
                case BANG -> {
                    var isTruthy = composer.createLabel();
                    var end = composer.createLabel();
                    composer
                        .iftruthy(isTruthy)
                        .TRUE()
                        .goto_(end)
                        .label(isTruthy)
                        .FALSE()
                        .label(end);
                }
                case MINUS -> composer
                        .unbox("java/lang/Double", "Operand must be a number.")
                        .dneg()
                        .box("java/lang/Double");

                default -> throw new IllegalArgumentException("Unsupported op: " + expr.getOperator().type());
            }

            return composer;
        }

        @Override
        public LoxComposer visitVariableExpr(Expr.Variable expr) {
            return composer
                .line(expr.getName().line())
                .load(currentFunction, expr);
        }
    }

    private static void addBootstrapMethod(ProgramClass programClass) {
        var constantPoolEditor = new ConstantPoolEditor(programClass);
        var bootstrapMethodsAttributeAdder = new BootstrapMethodsAttributeAdder(programClass);
        var bootstrapMethodInfo = new BootstrapMethodInfo(
            constantPoolEditor.addMethodHandleConstant(
                    REF_INVOKE_STATIC,
                    constantPoolEditor.addMethodrefConstant(
                        LOX_INVOKER,
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        null,
                        null
                    )
            ),
            0,
            new int[0]
        );

        bootstrapMethodsAttributeAdder.visitBootstrapMethodInfo(programClass, bootstrapMethodInfo);
    }

    private static void addClass(ClassPool classPool, Class<?>...classes) {
        for (var clazz : classes) {
            var is = Compiler.class.getClassLoader().getResourceAsStream(internalClassName(clazz.getName()) + ".class");
            assert is != null;
            var classReader = new ProgramClassReader(new DataInputStream(is));
            var programClass = new ProgramClass();
            programClass.accept(classReader);
            classPool.addClass(programClass);
        }
    }


    public static class NativeFunction extends Stmt.Function {

        NativeFunction(Token name, Collection<Token> params) {
            super(name, params, emptyList());
        }
    }

    private Collection<Stmt> prependNative(Collection<Stmt> stmts) {
        var nativeFunctions = new ArrayList<Stmt>();
        for (Method declaredMethod : LoxNative.class.getDeclaredMethods()) {
            var list = new ArrayList<Token>();
            for (int j = 0, parametersLength = declaredMethod.getParameters().length; j < parametersLength; j++) {
                list.add(new Token(IDENTIFIER, String.valueOf(j), null, 0));
            }
            nativeFunctions.add(new NativeFunction(
                new Token(IDENTIFIER, declaredMethod.getName(), null, 0),
                list
            ));
        }

        return Stream.concat(nativeFunctions.stream(), stmts.stream()).toList();
    }
}
