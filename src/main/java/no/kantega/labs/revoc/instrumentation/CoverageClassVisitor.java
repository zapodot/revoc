/*
 * Copyright 2012 Kantega AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package no.kantega.labs.revoc.instrumentation;

import no.kantega.labs.revoc.registry.BranchPoint;
import no.kantega.labs.revoc.registry.Registry;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 *
 */
public class CoverageClassVisitor extends ClassVisitor implements Opcodes {

    /** Representing this class with an integer which can be used as an array index **/
    private final int classId;

    /** Lines in this class file will have bits set for each line containing line number instructions **/
    private final BitSet existingLines = new BitSet();

    /** Maps actual debug line number to the index of its first usage **/
    private final Map<Integer, Integer> classLineNumbers = new HashMap<Integer, Integer>();

    private List<String> innerClasses = new ArrayList<String>();

    private List<BranchPoint> branchPoints = new ArrayList<BranchPoint>();
    private String source;
    private String className;

    private boolean trackLines = true;
    private boolean trackTime = true;
    private boolean trackBranches = false;
    private boolean profile = false;
    private int access;

    private int maxLocalVariableReportLoad = 10000;
    private List<String> methodNames = new ArrayList<String>();
    private List<String> methodDescs = new ArrayList<String>();
    private boolean staticInjected = false;

    public CoverageClassVisitor(ClassVisitor classVisitor, int classId) {
        super(ASM4, classVisitor);
        this.classId = classId;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        className = name;
        this.access = access;
    }

    

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        super.visitInnerClass(name, outerName, innerName, access);
        if(!className.equals(name)) {
            innerClasses.add(name);
        }
    }

    @Override
    public void visitSource(String source, String debug) {
        super.visitSource(source, debug);
        this.source = source;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if ((access & ACC_SYNTHETIC) != 0) {
            return mv;
        }

        return new FirstPassAnalysis(mv, access, name, desc, signature, exceptions);

    }

    @Override
    public void visitEnd() {

        FieldVisitor fv = super.visitField(ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC + ACC_FINAL, "revoc_counters", "Ljava/util/concurrent/atomic/AtomicLongArray;", null, null);
        fv.visitEnd();
        if(!staticInjected) {
            MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            visitFetchRevocCounter(mv);
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 0);
            mv.visitEnd();
        }
        super.visitEnd();
    }

    private void visitFetchRevocCounter(MethodVisitor mv) {
        mv.visitFieldInsn(GETSTATIC, "no/kantega/labs/revoc/registry/Registry", "lineVisits", "[Ljava/util/concurrent/atomic/AtomicLongArray;");
        mv.visitLdcInsn(classId);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(PUTSTATIC, className, "revoc_counters", "Ljava/util/concurrent/atomic/AtomicLongArray;");
    }

    protected MethodVisitor createSecondPassAnalyzer(int classId, Map<Integer, Integer> classLineNumbers, Map<Integer, Integer> methodLineNumbers, Map<Integer, Integer> branchPoints, int reportLoad, MethodVisitor mv, int access, String name, String desc) {
        return new SecondPassInstrumentation(classId, classLineNumbers, methodLineNumbers, branchPoints, reportLoad, mv, access, name, desc);
    }

    public int getClassId() {
        return classId;
    }

    public void setTrackTime(boolean trackTime) {
        this.trackTime = trackTime;
    }

    public void setTrackBranches(boolean trackBranches) {
        this.trackBranches = trackBranches;
    }

    /**
     * Runs a first pass of the code such that instrumentation can be done on the basis of class analysis.
     */
    class FirstPassAnalysis extends MethodNode {

        private final MethodVisitor mv;

        public FirstPassAnalysis(MethodVisitor mv, int access, String name, String desc, String signature, String[] exceptions) {
            super(access, name, desc, signature, exceptions);
            this.mv = mv;
        }

        @Override
        public void visitEnd() {


            final Map<Integer, Integer> methodLineNumbers = analyzeLinePoints(instructions);

            final Map<Integer, Integer> branchPoints = analyzeBranchPoints(instructions);

            int numExitPoints = countExitPoints(instructions);
            int reportLoad = (methodLineNumbers.size() + branchPoints.size()) * numExitPoints;

            accept(createSecondPassAnalyzer(classId, classLineNumbers, methodLineNumbers, branchPoints, reportLoad, mv, access, name, desc));
            methodNames.add(name);
            methodDescs.add(desc);



        }



        private int countExitPoints(InsnList instructions) {
            int numberOfReturns = 0;
            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode ins = instructions.get(i);
                if (ins instanceof InsnNode) {
                    InsnNode node = (InsnNode) ins;
                    if((node.getOpcode() >= IRETURN && node.getOpcode() <= RETURN ) || node.getOpcode() == ATHROW) {
                        numberOfReturns ++;
                    }
                }
            }
            return numberOfReturns;

        }

        private Map<Integer, Integer> analyzeBranchPoints(InsnList instructions) {
            int currentLineNumber = 0;
            final Map<Integer, Integer> branchPoints = new TreeMap<Integer, Integer>();
            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode ins = instructions.get(i);
                if (ins instanceof LineNumberNode) {
                    LineNumberNode node = (LineNumberNode) ins;
                    currentLineNumber = node.line;
                }

                if (ins instanceof JumpInsnNode) {
                    JumpInsnNode node = (JumpInsnNode) ins;


                    if (node.getOpcode() != Opcodes.GOTO && node.getOpcode() != Opcodes.JSR) {
                        int globalIndex = CoverageClassVisitor.this.branchPoints.size();

                        CoverageClassVisitor.this.branchPoints.add(new BranchPoint(node.getOpcode(), currentLineNumber - 1));

                        branchPoints.put(branchPoints.size(), globalIndex);
                    }
                }
            }
            return branchPoints;
        }

        private Map<Integer, Integer> analyzeLinePoints(InsnList instructions) {
            final Map<Integer, Integer> methodLineNumbers = new TreeMap<Integer, Integer>();

            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode ins = instructions.get(i);
                if (ins instanceof LineNumberNode) {
                    LineNumberNode node = (LineNumberNode) ins;
                    int lineNumber = node.line;
                    existingLines.set(lineNumber);
                    if (!classLineNumbers.containsKey(lineNumber)) {
                        classLineNumbers.put(lineNumber, classLineNumbers.size());
                    }
                    if (!methodLineNumbers.containsKey(lineNumber)) {
                        methodLineNumbers.put(lineNumber, methodLineNumbers.size());
                    }

                }
            }
            return methodLineNumbers;
        }

    }

    class SecondPassInstrumentation extends AdviceAdapter {


        private final int classId;
        private final Map<Integer, Integer> classLineNumbers;
        private final Map<Integer, Integer> methodLineNumbers;
        private final Map<Integer, Integer> branchPoints;
        private final int access;
        private final String name;
        private int methodJumpIndex = 0;
        private int timeLocal;

        private final boolean useLocalVariables;
        private int lineVisitsLocalVariable;
        private int timeVisitsLocalVariable;
        private int beforeBranchPointsLocalVariable;
        private int afterBranchPointsLocalVariable;
        private int frameMapLocalVariable;
        private int waitTimeLocalVariable;
        private int totalWaitTimeLocalVariable;
        private boolean profile;

        protected SecondPassInstrumentation(int classId, Map<Integer, Integer> classLineNumbers, Map<Integer, Integer> methodLineNumbers, Map<Integer, Integer> branchPoints, int reportLoad, MethodVisitor methodVisitor, int access, String name, String desc) {
            super(ASM4, methodVisitor, access, name, desc);
            this.classId = classId;
            this.classLineNumbers = classLineNumbers;
            this.methodLineNumbers = methodLineNumbers;
            this.branchPoints = branchPoints;
            this.access = access;
            this.name = name;
            this.useLocalVariables = reportLoad <= maxLocalVariableReportLoad;
            this.profile = CoverageClassVisitor.this.profile && !"<clinit>".equals(name);
        }

        // Maps absolute line number to local variable index
        private Map<Integer, Integer> lineNumberLocalVariables = new TreeMap<Integer, Integer>();
        private Map<Integer, Integer> lineTimeLocalVariables = new TreeMap<Integer, Integer>();
        private Map<Integer, Integer> beforeBranchPointLocalVariables = new TreeMap<Integer, Integer>();
        private Map<Integer, Integer> afterBranchPointLocalVariables = new TreeMap<Integer, Integer>();
        public Label before;
        public Label handler;



        @Override
        public void visitCode() {
            super.visitCode();

            if(name.equals("<clinit>") && (access & ACC_STATIC) != 0) {
                staticInjected = true;
                visitFetchRevocCounter(mv);
            }

            if(trackTime) {
                timeLocal = newLocal(Type.LONG_TYPE);
                updateTime();
            }
            if(trackLines) {
                if(useLocalVariables) {
                    initializeLineNumberLocalVariables();
                } else {
                    initializeLineNumerArrayLocalVariable();
                }
            }

            if(trackBranches) {
                if(useLocalVariables) {
                    initializeBranchPointLocalVariables();
                } else {
                    initializeBranchPointArrayLocalVariable();
                }
            }

            if(profile) {
                mv.visitLdcInsn((long)classId << 32 | (long) methodNames.size());
                mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerMethodEnter", "(J)Lno/kantega/labs/revoc/registry/Registry$FrameMap;");
                mv.visitVarInsn(ASTORE, frameMapLocalVariable = newLocal(Type.getType(Registry.FrameMap.class)));
                initalizeProfilingLocalVariables();
                                
            }
            before = new Label();
            handler = new Label();
            mv.visitLabel(before);
        }

        private void initalizeProfilingLocalVariables() {
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, totalWaitTimeLocalVariable = newLocal(Type.getType("J")));
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, waitTimeLocalVariable = newLocal(Type.getType("J")));
        }

        private void nanoTime() {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J");
        }

        private void initializeBranchPointArrayLocalVariable() {
            visitIntConstantInstruction(branchPoints.size());
            mv.visitIntInsn(NEWARRAY, T_INT);
            mv.visitVarInsn(ASTORE, beforeBranchPointsLocalVariable = newLocal(Type.getType("[I")));
            visitIntConstantInstruction(branchPoints.size());
            mv.visitIntInsn(NEWARRAY, T_INT);
            mv.visitVarInsn(ASTORE, afterBranchPointsLocalVariable = newLocal(Type.getType("[I")));
        }

        private void initializeLineNumerArrayLocalVariable() {
            visitIntConstantInstruction(methodLineNumbers.size());
            mv.visitIntInsn(NEWARRAY, T_INT);
            mv.visitVarInsn(ASTORE, lineVisitsLocalVariable = newLocal(Type.getType("[I")));
            if(trackTime) {
                visitIntConstantInstruction(methodLineNumbers.size());
                mv.visitIntInsn(NEWARRAY, T_LONG);
                mv.visitVarInsn(ASTORE, timeVisitsLocalVariable = newLocal(Type.getType("[J")));
            }


        }

        private void initializeBranchPointLocalVariables() {
            for (int branchIndex : branchPoints.keySet()) {
                {
                    mv.visitInsn(ICONST_0);
                    int local = newLocal(Type.INT_TYPE);
                    beforeBranchPointLocalVariables.put(branchIndex, local);
                    mv.visitVarInsn(ISTORE, local);
                }
                {
                    mv.visitInsn(ICONST_0);
                    int local = newLocal(Type.INT_TYPE);
                    afterBranchPointLocalVariables.put(branchIndex, local);
                    mv.visitVarInsn(ISTORE, local);
                }
            }
        }

        private void initializeLineNumberLocalVariables() {
            for (int lineNumber : methodLineNumbers.keySet()) {
                {
                    mv.visitInsn(ICONST_0);
                    int local = newLocal(Type.INT_TYPE);
                    lineNumberLocalVariables.put(lineNumber, local);
                    mv.visitVarInsn(ISTORE, local);
                }
                if(trackTime) {
                    mv.visitInsn(LCONST_0);
                    int local = newLocal(Type.LONG_TYPE);
                    lineTimeLocalVariables.put(lineNumber, local);
                    mv.visitVarInsn(LSTORE, local);
                }
            }
        }


        @Override
        public void visitLineNumber(int lineNumber, Label label) {
            mv.visitLineNumber(lineNumber, label);
            if(trackLines) {
                if(useLocalVariables) {
                    mv.visitIincInsn(lineNumberLocalVariables.get(lineNumber), 1);

                    if(trackTime) {
                        mv.visitVarInsn(LLOAD, timeLocal);
                        mv.visitVarInsn(LSTORE, lineTimeLocalVariables.get(lineNumber));
                    }
                } else {
                    {
                    mv.visitVarInsn(ALOAD, lineVisitsLocalVariable);
                    visitIntConstantInstruction(methodLineNumbers.get(lineNumber));
                    mv.visitInsn(DUP2);
                    mv.visitInsn(IALOAD);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(IADD);
                    mv.visitInsn(IASTORE);
                    }
                    if(trackTime) {
                        mv.visitVarInsn(ALOAD, timeVisitsLocalVariable);
                        visitIntConstantInstruction(methodLineNumbers.get(lineNumber));
                        mv.visitVarInsn(LLOAD, timeLocal);
                        mv.visitInsn(LASTORE);
                    }
                }


            }

        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
            if(profile && isWaitMethod(opcode, owner, name, desc)) {
                nanoTime();
                mv.visitVarInsn(LSTORE, waitTimeLocalVariable);

            }
            super.visitMethodInsn(opcode, owner, name, desc);
            if(profile && isWaitMethod(opcode, owner, name, desc)) {
                nanoTime();
                mv.visitVarInsn(LLOAD, waitTimeLocalVariable);
                mv.visitInsn(LSUB);
                mv.visitVarInsn(LLOAD, totalWaitTimeLocalVariable);
                mv.visitInsn(LADD);
                mv.visitVarInsn(LSTORE, totalWaitTimeLocalVariable);

            }
            if(trackTime) {
                updateTime();
            }
        }

        private boolean isWaitMethod(int opcode, String owner, String name, String desc) {
            return opcode == INVOKEVIRTUAL && "java/lang/Thread".equals(owner) && "join".equals(name) && "()V".equals(desc);
        }

        @Override
        public void visitInsn(int i) {
            if (trackTime && i >= IRETURN && i <= RETURN) {
                updateTime();
            }
            super.visitInsn(i);
        }

        private void updateTime() {
            if(trackTime) {
                mv.visitFieldInsn(GETSTATIC, "no/kantega/labs/revoc/registry/Registry", "time", "J");
                mv.visitVarInsn(LSTORE, timeLocal);
            }
        }

        /**
         * Visit a BIPUSH, SIPUSH or LDC instruction based on the size of num
         *
         * @param num the int constant to put on the stack
         */
        private void visitIntConstantInstruction(int num) {
            if (num <= 5) {
                mv.visitInsn(ICONST_0 + num);
            } else if (num < 128) {
                mv.visitIntInsn(BIPUSH, num);
            } else if (num <= Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, num);
            } else {
                mv.visitLdcInsn(num);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitTryCatchBlock(before, handler, handler, null);
            mv.visitLabel(handler);


            generateLineVisitRegistration();

            mv.visitInsn(ATHROW);
            mv.visitMaxs(maxStack, maxLocals + lineNumberLocalVariables.size() + beforeBranchPointLocalVariables.size() + afterBranchPointLocalVariables.size());
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                generateLineVisitRegistration();
            }
        }

        @Override
        public void visitJumpInsn(int i, Label label) {
            if (!trackBranches || i == Opcodes.GOTO || i == Opcodes.JSR ) {
                super.visitJumpInsn(i, label);
            } else {
                int index = methodJumpIndex;

                if(useLocalVariables) {
                    {
                        int local = beforeBranchPointLocalVariables.get(index);
                        mv.visitIincInsn(local, 1);
                    }
                    super.visitJumpInsn(i, label);

                    {
                        int local = afterBranchPointLocalVariables.get(index);
                        mv.visitIincInsn(local, 1);
                    }
                } else {
                     {
                         mv.visitVarInsn(ALOAD, beforeBranchPointsLocalVariable);
                         visitIntConstantInstruction(index);
                         mv.visitInsn(DUP2);
                         mv.visitInsn(IALOAD);
                         mv.visitInsn(ICONST_1);
                         mv.visitInsn(IADD);
                         mv.visitInsn(IASTORE);
                     }
                    super.visitJumpInsn(i, label);
                    {
                        mv.visitVarInsn(ALOAD, afterBranchPointsLocalVariable);
                        visitIntConstantInstruction(index);
                        mv.visitInsn(DUP2);
                        mv.visitInsn(IALOAD);
                        mv.visitInsn(ICONST_1);
                        mv.visitInsn(IADD);
                        mv.visitInsn(IASTORE);
                    }

                }
                methodJumpIndex++;
            }
        }

        private void generateLineVisitRegistration() {

            if(profile) {
                mv.visitVarInsn(ALOAD, frameMapLocalVariable);
                nanoTime();
                mv.visitVarInsn(LLOAD, totalWaitTimeLocalVariable);
                mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerMethodExit", "(Lno/kantega/labs/revoc/registry/Registry$FrameMap;JJ)V");
            }
            
            // Get the int[] for this class


            if(trackLines) {

                {

                    mv.visitFieldInsn(GETSTATIC, className, "revoc_counters", "Ljava/util/concurrent/atomic/AtomicLongArray;");

                    if(trackTime) {
                        mv.visitFieldInsn(GETSTATIC, "no/kantega/labs/revoc/registry/Registry", "lineTimes", "[Ljava/util/concurrent/atomic/AtomicLongArray;");
                        visitIntConstantInstruction(classId);
                        mv.visitInsn(AALOAD);
                    }

                }

                if(useLocalVariables) {



                    for (int i = 0; i < lineNumberLocalVariables.size() - 1; i++) {
                        if(trackTime) {
                            mv.visitInsn(DUP2);
                        } else {
                            mv.visitInsn(DUP);
                        }
                    }

                    for (Integer lineNumber : lineNumberLocalVariables.keySet()) {
                        visitIntConstantInstruction(classLineNumbers.get(lineNumber));
                        mv.visitVarInsn(ILOAD, lineNumberLocalVariables.get(lineNumber));
                        if(trackTime) {
                            mv.visitVarInsn(LLOAD, lineTimeLocalVariables.get(lineNumber));
                            mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerLineTimeVisited", "(Ljava/util/concurrent/atomic/AtomicLongArray;Ljava/util/concurrent/atomic/AtomicLongArray;IIJ)V");
                        } else {
                            mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerLineVisited", "(Ljava/util/concurrent/atomic/AtomicLongArray;II)V");
                        }




                    }
                } else {
                    mv.visitVarInsn(ALOAD, lineVisitsLocalVariable);
                    if(trackTime) {
                        mv.visitVarInsn(ALOAD, timeVisitsLocalVariable);
                    }
                    visitIntConstantInstruction(classLineNumbers.get(methodLineNumbers.keySet().iterator().next()));

                    if(trackTime) {
                        mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerLineTimeVisitedArray", "(Ljava/util/concurrent/atomic/AtomicLongArray;Ljava/util/concurrent/atomic/AtomicLongArray;[I[JI)V");
                    } else {
                        mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerLineVisitedArray", "(Ljava/util/concurrent/atomic/AtomicLongArray;[II)V");
                    }
                }
            }

            if(trackBranches && !branchPoints.isEmpty()) {
                if(useLocalVariables) {
                    for(Integer index :branchPoints.keySet()) {

                        visitIntConstantInstruction(classId);
                        visitIntConstantInstruction(branchPoints.get(index));
                        mv.visitVarInsn(ILOAD, beforeBranchPointLocalVariables.get(index));
                        mv.visitVarInsn(ILOAD, afterBranchPointLocalVariables.get(index));
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerBranchPointVisits", "(IIII)V");

                    }
                } else {
                    visitIntConstantInstruction(classId);
                    mv.visitVarInsn(ALOAD, beforeBranchPointsLocalVariable);
                    mv.visitVarInsn(ALOAD, afterBranchPointsLocalVariable);
                    visitIntConstantInstruction(branchPoints.get(branchPoints.keySet().iterator().next()));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "registerBranchPointVisitsArray", "(I[I[II)V");
                }
            }

            visitIntConstantInstruction(classId);
            mv.visitMethodInsn(INVOKESTATIC, "no/kantega/labs/revoc/registry/Registry", "linesTouched", "(I)V");
        }

    }

    public BitSet getExistingLines() {
        return existingLines;
    }

    public int[] getLineIndexes() {
        Map<Integer, Integer> index2Line = new TreeMap<Integer, Integer>();

        for (Integer lineNum : classLineNumbers.keySet()) {
            index2Line.put(classLineNumbers.get(lineNum), lineNum);
        }
        int[] lines = new int[index2Line.size()];
        int c = 0;
        for (Integer index : index2Line.keySet()) {
            lines[c++] = index2Line.get(index);
        }
        return lines;
    }

    public List<BranchPoint> getBranchPoints() {
        return branchPoints;
    }

    public String getSource() {
        return source;
    }

    public String getClassName() {
        return className;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public List<String> getInnerClasses() {
        return innerClasses;
    }

    public List<String> getMethodNames() {
        return methodNames;
    }

    public List<String> getMethodDescs() {
        return methodDescs;
    }
}
