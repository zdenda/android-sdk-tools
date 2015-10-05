/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.build.gradle.internal.incremental;

import com.android.annotations.NonNull;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Value;

import java.util.List;

/**
 * Utilities to detect and manipulate constructor methods.
 *
 * When instrumenting a constructor the original "super" or "this" call, cannot be
 * removed or manipulated in anyway. The same goes for the this object before the super
 * call: It cannot be send to any other method as the verifier sees it as
 * UNINITIALIZED_THIS and won't allow bytecode that "escapes it"
 *
 * A constructor of a non static inner class usually has the form:
 *
 * ALOAD_0              // push this to the stack
 * ...                  // Code to set up $this
 * ALOAD_0              // push this to the stack
 * ...                  // Code to set up the arguments (aka "args") for the delegation
 * ...                  // via super() or this(). Note that here we can have INVOKESPECIALS
 * ...                  // for all the new calls here.
 * INVOKESPECIAL <init> // super() or this() call
 * ...                  // the "body" of the constructor goes here.
 *
 * When instrumenting with incremental support we only allow "swapping" the "body".
 *
 * This class has the utilities to detect which instruction is the right INVOKESPECIAL call before
 * the "body".
 */
public class ConstructorDelegationDetector {

    /**
     * A specialized value used to track the first local variable (this) on the
     * constructor.
     */
    public static class LocalValue extends BasicValue {
        public LocalValue(Type type) {
            super(type);
        }

        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * A deconstructed constructor, split up in the parts mentioned above.
     */
    static class Constructor {

        /**
         * The last LOAD_0 instruction of the original code, before the call to the delegated
         * constructor.
         */
        public final VarInsnNode loadThis;

        /**
         * The "args" part of the constructor. Described above.
         */
        public final MethodNode args;

        /**
         * The INVOKESPECIAL instruction of the original code that calls the delegation.
         */
        public final MethodInsnNode delegation;

        /**
         * A copy of the body of the constructor.
         */
        public final MethodNode body;

        Constructor(VarInsnNode loadThis, MethodNode args, MethodInsnNode delegation, MethodNode body) {
            this.loadThis = loadThis;
            this.args = args;
            this.delegation = delegation;
            this.body = body;
        }
    }

    /**
     * Deconstruct a constructor into its components and adds the necessary code to link the components
     * later. The generated bytecode does not correspond exactly to this code, but in essence, for
     * a constructor of this form:
     * <p/>
     * <code>
     *   <init>(int x) {
     *     super(x = 1, expr2() ? 3 : 7)
     *     doSomething(x)
     *   }
     * </code>
     * <p/>
     * it creates the two parts:
     * <code>
     *   Object[] init$args(Object[] locals, int x) {
     *     Object[] ret = new Object[2];
     *     ret[0] = (x = 1)
     *     ret[1] = expr2() ? 3 : 7;
     *     locals[0] = x;
     *     return ret;
     *   }
     *
     *   void init$body(int x) {
     *     doSomething(x);
     *   }
     * </code>
     *
     * @param owner the owning class.
     * @param method the constructor method.
     */
    @NonNull
    public static Constructor deconstruct(@NonNull String owner, @NonNull MethodNode method) {
        // Basic interpreter uses BasicValue.REFERENCE_VALUE for all object types. However
        // we need to distinguish one in particular. The value of the local variable 0, ie. the
        // uninitialized this. By doing it this way we ensure that whenever there is a ALOAD_0
        // a LocalValue instance will be on the stack.
        BasicInterpreter interpreter = new BasicInterpreter() {
            boolean done = false;
            @Override
            // newValue is called first to initialize the frame values of all the local variables
            // we intercept the first one to create our own special value.
            public BasicValue newValue(Type type) {
                if (type == null) {
                    return BasicValue.UNINITIALIZED_VALUE;
                } else if (type.getSort() == Type.VOID) {
                    return null;
                } else {
                    // If this is the first value created (i.e. the first local variable)
                    // we use a special marker.
                    BasicValue ret = done ? super.newValue(type) : new LocalValue(type);
                    done = true;
                    return ret;
                }
            }
        };

        Analyzer analyzer = new Analyzer(interpreter);
        AbstractInsnNode[] instructions = method.instructions.toArray();
        try {
            Frame[] frames = analyzer.analyze(owner, method);
            if (frames.length != instructions.length) {
                // Should never happen.
                throw new IllegalStateException(
                        "The number of frames is not equals to the number of instructions");
            }
            VarInsnNode lastThis = null;
            int stackAtThis = -1;
            boolean poppedThis = false;
            for (int i = 0; i < instructions.length; i++) {
                AbstractInsnNode insn = instructions[i];
                Frame frame = frames[i];
                if (frame.getStackSize() < stackAtThis) {
                    poppedThis = true;
                }
                if (insn instanceof MethodInsnNode) {
                    // TODO: Do we need to check that the stack is empty after this super call?
                    MethodInsnNode methodhInsn = (MethodInsnNode) insn;
                    Type[] types = Type.getArgumentTypes(methodhInsn.desc);
                    Value value = frame.getStack(frame.getStackSize() - types.length - 1);
                    if (value instanceof LocalValue && methodhInsn.name.equals("<init>")) {
                        if (poppedThis) {
                            throw new IllegalStateException("Unexpected constructor structure.");
                        }
                        return split(owner, method, lastThis, methodhInsn);
                    }
                } else if (insn instanceof VarInsnNode) {
                    VarInsnNode var = (VarInsnNode) insn;
                    if (var.var == 0) {
                        lastThis = var;
                        stackAtThis = frame.getStackSize();
                        poppedThis = false;
                    }
                }
            }
            throw new IllegalStateException("Unexpected constructor structure.");
        } catch (AnalyzerException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Splits the constructor in two methods, the "set up" and the "body" parts (see above).
     */
    @NonNull
    private static Constructor split(@NonNull String owner, @NonNull MethodNode method, @NonNull VarInsnNode loadThis, @NonNull MethodInsnNode delegation ) {
        String[] exceptions = ((List<String>)method.exceptions).toArray(new String[method.exceptions.size()]);
        String newDesc = method.desc.replaceAll("\\((.*)\\)V", "([Ljava/lang/Object;$1)Ljava/lang/Object;");

        MethodNode initArgs = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init$args", newDesc, null, exceptions);
        AbstractInsnNode insn = loadThis.getNext();
        while (insn != delegation) {
            insn.accept(initArgs);
            insn = insn.getNext();
        }
        GeneratorAdapter mv = new GeneratorAdapter(initArgs, initArgs.access, initArgs.name, initArgs.desc);
        // Copy the arguments back to the argument array
        // The init_args part cannot access the "this" object and can have side effects on the
        // local variables. Because of this we use the first argument (which we want to keep
        // so all the other arguments remain unchanged) as a reference to the array where to
        // return the values of the modified local variables.
        Type[] types = Type.getArgumentTypes(initArgs.desc);
        int stack = 1; // Skip the first one which is a reference to the local array.
        for (int i = 1; i < types.length; i++) {
            Type type = types[i];
            // This is not this, but the array of local arguments final values.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.push(i);
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), stack);
            mv.box(type);
            mv.arrayStore(Type.getType(Object.class));
            stack += type.getSize();
        }
        // Create the return array with the values to send to the delegated constructor
        Type[] returnTypes = Type.getArgumentTypes(delegation.desc);
        mv.push(returnTypes.length);
        mv.newArray(Type.getType(Object.class));
        int ret = mv.newLocal(Type.getType("[Ljava/lang/Object;"));
        mv.storeLocal(ret);
        for (int i = returnTypes.length - 1; i >= 0; i--) {
            Type type = returnTypes[i];
            mv.loadLocal(ret);
            mv.swap(type, Type.getType(Object.class));
            mv.push(i);
            mv.swap(type, Type.INT_TYPE);
            mv.box(type);
            mv.arrayStore(Type.getType(Object.class));
        }
        mv.loadLocal(ret);
        mv.returnValue();

        newDesc = method.desc.replace("(", "(L" + owner + ";");
        MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init$body", newDesc, null, exceptions);
        insn = delegation.getNext();
        while (insn != null) {
            insn.accept(body);
            insn = insn.getNext();
        }

        return new Constructor(loadThis, initArgs, delegation, body);
    }
}