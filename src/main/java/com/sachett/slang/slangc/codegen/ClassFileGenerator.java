package com.sachett.slang.slangc.codegen;

import com.sachett.slang.slangc.codegen.compoundstmt.FunctionGenerator;
import com.sachett.slang.slangc.codegen.expressions.BooleanExprCodeGen;
import com.sachett.slang.slangc.codegen.expressions.IntExprCodeGen;
import com.sachett.slang.slangc.codegen.expressions.StringExprCodeGen;
import com.sachett.slang.slangc.codegen.function.FunctionCodeGen;
import com.sachett.slang.slangc.codegen.utils.delegation.CodeGenDelegatedMethod;
import com.sachett.slang.slangc.codegen.utils.delegation.CodeGenDelegationManager;
import com.sachett.slang.slangc.codegen.utils.delegation.ICodeGenDelegatable;
import com.sachett.slang.slangc.symbol.*;
import com.sachett.slang.slangc.symbol.symboltable.SymbolTable;

import com.sachett.slang.parser.SlangParser;
import kotlin.Pair;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.*;

public class ClassFileGenerator extends ICodeGenDelegatable {
    private final TraceClassVisitor classWriter;
    private final ClassWriter delegateClassWriter;
    private final SlangParser.ProgramContext programContext;
    private String fileName;
    private String className;
    private final ArrayDeque<FunctionCodeGen> functionCodeGenStack = new ArrayDeque<>();
    private FunctionCodeGen currentFunctionCodeGen;
    private final CommonCodeGen delegateCommonCodeGen;
    private final SymbolTable symbolTable;

    /**
     * The CodeGenDelegationManager helps manage the delegation of the partial code generators.
     */
    private CodeGenDelegationManager sharedCodeGenDelegationManager
            = new CodeGenDelegationManager(this, null);

    /**
     * A hashmap to store the static variables. The entries are of the form:
     * symbolName: corresponding ISymbol
     */
    private final HashMap<String, ISymbol> staticVariables = new HashMap<>();

    public ClassFileGenerator(
            SlangParser.ProgramContext programContext,
            @NotNull String fileName,
            @NotNull SymbolTable symbolTable
    ) {
        super();

        /**
         * Register the stuff that this generator generates with the shared delegation manager.
         */
        HashSet<CodeGenDelegatedMethod> delegatedMethodHashSet = new HashSet<>(List.of(CodeGenDelegatedMethod.BLOCK,
                CodeGenDelegatedMethod.DECL,
                CodeGenDelegatedMethod.NORMAL_DECLASSIGN,
                CodeGenDelegatedMethod.BOOLEAN_DECLASSIGN,
                CodeGenDelegatedMethod.TYPEINF_DECLASSIGN,
                CodeGenDelegatedMethod.TYPEINF_BOOLEAN_DECLASSIGN,
                CodeGenDelegatedMethod.EXPR_ASSIGN,
                CodeGenDelegatedMethod.BOOLEAN_EXPR_ASSIGN,
                CodeGenDelegatedMethod.FUNCTIONCALL_NOARGS,
                CodeGenDelegatedMethod.FUNCTIONCALL_WITHARGS,
                CodeGenDelegatedMethod.IMPLICIT_RET_FUNCDEF,
                CodeGenDelegatedMethod.EXPLICIT_RET_FUNCDEF,
                CodeGenDelegatedMethod.IF,
                CodeGenDelegatedMethod.WHILE,
                CodeGenDelegatedMethod.BREAK,
                CodeGenDelegatedMethod.CONTINUE));
        this.registerDelegatedMethods(delegatedMethodHashSet);

        /**
         * Initialize the class file generator.
         */

        this.programContext = programContext;
        this.fileName = fileName;
        this.symbolTable = symbolTable;

        // ensure that the symbol table's currentScopeIndex is reset
        symbolTable.resetScopeIndex();

        String[] fileNameParts = fileName.split("\\.");
        StringBuilder genClassNameBuilder = new StringBuilder();

        for (String fileNamePart : fileNameParts) {
            // Capitalize the first letter of each part of the filename

            if (Objects.equals(fileNamePart, "")) {
                continue;
            }

            if (fileNamePart.contains("/")) {
                String[] dirParts = fileNamePart.split("[/\\\\]");
                fileNamePart = dirParts.length > 0 ? dirParts[dirParts.length - 1] : fileNamePart;
            }

            String modFileNamePart = fileNamePart.substring(0, 1).toUpperCase() + fileNamePart.substring(1);
            genClassNameBuilder.append(modFileNamePart);
        }

        this.className = genClassNameBuilder.toString();

        // Generate a default class
        // TODO: Make this COMPUTE_MAXS and compute frames properly in jumps
        // This is being done already, but for some reason the JVM complains EVEN IF the stack frames are consistent.
        // To try it, change COMPUTE_FRAMES to COMPUTE_MAXS and try running the generated class file.
        this.delegateClassWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        this.classWriter = new TraceClassVisitor(delegateClassWriter, new PrintWriter(System.out));
        classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, this.className, null, "java/lang/Object", null);

        // Generate a default main function
        currentFunctionCodeGen = new FunctionCodeGen(
                classWriter,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null
        );
        currentFunctionCodeGen.getMv().visitCode();
        delegateCommonCodeGen = new CommonCodeGen(this, currentFunctionCodeGen, symbolTable, className, "");
    }

    public void generateClass() {
        this.visit(this.programContext);
        currentFunctionCodeGen.getMv().visitInsn(Opcodes.RETURN);
        currentFunctionCodeGen.getMv().visitMaxs(0, 0); // any arguments work, will be recalculated
        currentFunctionCodeGen.getMv().visitEnd();
        classWriter.visitEnd();
    }

    public void writeClass() {
        byte[] classBytes = delegateClassWriter.toByteArray();
        try (FileOutputStream stream
                     = FileUtils.openOutputStream(new File("./out/" + this.className + ".class"))) {
            stream.write(classBytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ISymbol makeFieldFromSymbol(String idName) {
        ISymbol symbol = symbolTable.lookup(idName);

        if (symbol == null) {
            return null;
        }

        SymbolType symbolType = symbol.getSymbolType();
        if (symbolType == SymbolType.INT) {
            classWriter.visitField(
                    Opcodes.ACC_STATIC + Opcodes.ACC_PRIVATE,
                    symbol.getName(),
                    Type.INT_TYPE.getDescriptor(),
                    null,
                    ((IntSymbol) symbol).getValue()
            ).visitEnd();
        } else if (symbolType == SymbolType.BOOL) {
            classWriter.visitField(
                    Opcodes.ACC_STATIC + Opcodes.ACC_PRIVATE,
                    symbol.getName(),
                    Type.BOOLEAN_TYPE.getDescriptor(),
                    null,
                    ((BoolSymbol) symbol).getValue()
            ).visitEnd();
        } else if (symbolType == SymbolType.STRING) {
            classWriter.visitField(
                    Opcodes.ACC_STATIC + Opcodes.ACC_PUBLIC,
                    symbol.getName(),
                    Type.getType(String.class).getDescriptor(),
                    null,
                    ((StringSymbol) symbol).getValue()
            ).visitEnd();
        }

        return symbol;
    }

    private void initializeField(ISymbol symbol, @Nullable SlangParser.ExprContext initExpr) {
        switch (symbol.getSymbolType()) {
            case INT:
                if (!symbol.isInitialValueCalculated()) {
                    // Runtime evaluation
                    if (initExpr != null) {
                        IntExprCodeGen intExprCodeGen = new IntExprCodeGen(
                                initExpr,
                                symbolTable,
                                currentFunctionCodeGen,
                                className,
                                ""
                        );
                        intExprCodeGen.doCodeGen();
                    } else {
                        currentFunctionCodeGen.getMv().visitLdcInsn(SymbolType.INT.getDefaultValue());
                    }

                    currentFunctionCodeGen.getMv().visitFieldInsn(
                            Opcodes.PUTSTATIC,
                            className,
                            symbol.getName(),
                            Type.INT_TYPE.getDescriptor()
                    );
                }
                break;

            case BOOL:
                // If we are here, that means initExpr is null and the Symbol is a bool symbol
                // So for boolean field dynamic initialization: we don't do that here
                // (it's done in initializeBooleanField() as it requires a BooleanExprContext)
                break;

            case STRING:
                if (!symbol.isInitialValueCalculated()) {
                    if (initExpr != null) {
                        StringExprCodeGen stringExprCodeGen = new StringExprCodeGen(
                                initExpr,
                                symbolTable,
                                currentFunctionCodeGen,
                                className,
                                ""
                        );
                        stringExprCodeGen.doCodeGen();
                    } else {
                        currentFunctionCodeGen.getMv().visitLdcInsn(SymbolType.STRING.getDefaultValue());
                    }

                    // the string should now be on the top of the stack
                    currentFunctionCodeGen.getMv().visitFieldInsn(
                            Opcodes.PUTSTATIC,
                            className,
                            symbol.getName(),
                            Type.getType(String.class).getDescriptor()
                    );
                }
                break;
        }
    }

    private void initializeBooleanField(ISymbol symbol, SlangParser.BooleanExprContext initExpr) {
        if (symbol.getSymbolType() != SymbolType.BOOL) {
            return;
        }
        if (!symbol.isInitialValueCalculated()) {
            BooleanExprCodeGen booleanExprCodeGen = new BooleanExprCodeGen(
                    initExpr,
                    symbolTable,
                    currentFunctionCodeGen,
                    className,
                    ""
            );
            booleanExprCodeGen.doCodeGen();

            currentFunctionCodeGen.getMv().visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    className,
                    symbol.getName(),
                    Type.BOOLEAN_TYPE.getDescriptor()
            );
        }
    }

    @Override
    public Void visitBlock(SlangParser.BlockContext ctx) {
        delegateCommonCodeGen.visitBlock(ctx);
        return null;
    }

    @Override
    public Void visitDeclStmt(SlangParser.DeclStmtContext ctx) {
        String idName = ctx.IDENTIFIER().getSymbol().getText();
        ISymbol symbol = makeFieldFromSymbol(idName);
        if (symbol != null) {
            initializeField(symbol, null);
        }
        return null;
    }

    @Override
    public Void visitNormalDeclAssignStmt(SlangParser.NormalDeclAssignStmtContext ctx) {
        String idName = ctx.IDENTIFIER().getSymbol().getText();
        ISymbol symbol = makeFieldFromSymbol(idName);
        if (symbol != null) {
            initializeField(symbol, ctx.expr());
        }
        return null;
    }

    @Override
    public Void visitBooleanDeclAssignStmt(SlangParser.BooleanDeclAssignStmtContext ctx) {
        String idName = ctx.IDENTIFIER().getSymbol().getText();
        ISymbol symbol = makeFieldFromSymbol(idName);
        if (symbol != null) {
            initializeBooleanField(symbol, ctx.booleanExpr());
        }
        return null;
    }

    @Override
    public Void visitTypeInferredDeclAssignStmt(SlangParser.TypeInferredDeclAssignStmtContext ctx) {
        String idName = ctx.IDENTIFIER().getSymbol().getText();
        ISymbol symbol = makeFieldFromSymbol(idName);
        if (symbol != null) {
            initializeField(symbol, ctx.expr());
        }
        return null;
    }

    @Override
    public Void visitTypeInferredBooleanDeclAssignStmt(SlangParser.TypeInferredBooleanDeclAssignStmtContext ctx) {
        String idName = ctx.IDENTIFIER().getSymbol().getText();
        ISymbol symbol = makeFieldFromSymbol(idName);
        if (symbol != null) {
            initializeBooleanField(symbol, ctx.booleanExpr());
        }
        return null;
    }

    @Override
    public Void visitExprAssign(SlangParser.ExprAssignContext ctx) {
        delegateCommonCodeGen.visitExprAssign(ctx);
        return null;
    }

    @Override
    public Void visitBooleanExprAssign(SlangParser.BooleanExprAssignContext ctx) {
        String idName = ctx.IDENTIFIER().getText();

        Pair<ISymbol, Integer> lookupInfo = symbolTable.lookupWithNearestScopeValue(idName);
        if (lookupInfo.getFirst() == null) {
            // lookup failed
            return null;
        }

        // Let's just trust the compile-time type checker here
        Type type = Type.BOOLEAN_TYPE;
        int storeInstruction = Opcodes.ISTORE;

        // Do codegen of RHS
        BooleanExprCodeGen boolCodeGen = new BooleanExprCodeGen(
                ctx.booleanExpr(), symbolTable, currentFunctionCodeGen, className, "");
        boolCodeGen.doCodeGen();

        // Store the value generated into the variable
        if (lookupInfo.getSecond() == 0) {
            // we're talking about a global variable
            // (a static field of the class during generation)
            currentFunctionCodeGen.getMv().visitFieldInsn(
                    Opcodes.PUTSTATIC, className, idName, type.getDescriptor()
            );
        } else {
            Integer localVarIndex = currentFunctionCodeGen.getLocalVarIndex(idName);
            currentFunctionCodeGen.getMv().visitVarInsn(storeInstruction, localVarIndex);
        }

        return super.visitBooleanExprAssign(ctx);
    }

    @Override
    public Void visitFunctionCallNoArgs(SlangParser.FunctionCallNoArgsContext ctx) {
        return delegateCommonCodeGen.visitFunctionCallNoArgs(ctx);
    }

    @Override
    public Void visitFunctionCallWithArgs(SlangParser.FunctionCallWithArgsContext ctx) {
        return delegateCommonCodeGen.visitFunctionCallWithArgs(ctx);
    }

    private void setCurrentFunctionCodeGen(FunctionCodeGen functionCodeGen) {
        // save current functionCodeGen to a stack
        functionCodeGenStack.push(currentFunctionCodeGen);
        currentFunctionCodeGen = functionCodeGen;

        // Update functionCodeGens of delegates
        delegateCommonCodeGen.setFunctionCodeGen(currentFunctionCodeGen); // TODO: refactor this redundancy
    }

    private void restoreLastFunctionCodeGen() {
        currentFunctionCodeGen = functionCodeGenStack.pop();

        // Update functionCodeGens of delegates
        delegateCommonCodeGen.setFunctionCodeGen(currentFunctionCodeGen);
    }

    private FunctionGenerator makeMethod(String funcIdName) {
        var funcSymbol = symbolTable.lookup(funcIdName);

        if (funcSymbol == null) {
            return null;
        }

        if (!(funcSymbol instanceof FunctionSymbol functionSymbol)) {
            return null;
        }

        String funcDescriptor = FunctionCodeGen.generateDescriptor(functionSymbol);
        FunctionCodeGen functionCodeGen = new FunctionCodeGen(
                classWriter,
                Opcodes.ACC_STATIC + Opcodes.ACC_PUBLIC,
                functionSymbol.getName(),
                funcDescriptor,
                null, null
        );

        setCurrentFunctionCodeGen(functionCodeGen);

        return new FunctionGenerator(
                this, functionCodeGen,
                delegateCommonCodeGen, symbolTable, functionSymbol, className, ""
        );
    }

    @Override
    public Void visitImplicitRetTypeFuncDef(SlangParser.ImplicitRetTypeFuncDefContext ctx) {
        String funcIdName = ctx.IDENTIFIER().getText();
        FunctionGenerator functionGenerator = makeMethod(funcIdName);
        if (functionGenerator == null) return null;

        this.startDelegatingTo(functionGenerator);
        functionGenerator.generateImplicitRetTypeFuncDef(ctx);
        this.finishDelegating();

        // restore previous functionCodeGen
        restoreLastFunctionCodeGen();
        return null;
    }

    @Override
    public Void visitExplicitRetTypeFuncDef(SlangParser.ExplicitRetTypeFuncDefContext ctx) {
        String funcIdName = ctx.IDENTIFIER().getText();
        FunctionGenerator functionGenerator = makeMethod(funcIdName);
        if (functionGenerator == null) return null;

        this.startDelegatingTo(functionGenerator);
        functionGenerator.generateExplicitRetTypeFuncDef(ctx);
        this.finishDelegating();

        // restore previous functionCodeGen
        restoreLastFunctionCodeGen();
        return null;
    }

    @Override
    public Void visitIfStmt(SlangParser.IfStmtContext ctx) {
        return delegateCommonCodeGen.visitIfStmt(ctx);
    }

    @Override
    public Void visitWhileStmt(SlangParser.WhileStmtContext ctx) {
        return delegateCommonCodeGen.visitWhileStmt(ctx);
    }

    @Override
    public Void visitBreakControlStmt(SlangParser.BreakControlStmtContext ctx) {
        return delegateCommonCodeGen.visitBreakControlStmt(ctx);
    }

    @Override
    public Void visitContinueControlStmt(SlangParser.ContinueControlStmtContext ctx) {
        return delegateCommonCodeGen.visitContinueControlStmt(ctx);
    }
}
