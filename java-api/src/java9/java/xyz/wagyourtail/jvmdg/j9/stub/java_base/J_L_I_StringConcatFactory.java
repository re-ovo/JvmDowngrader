package xyz.wagyourtail.jvmdg.j9.stub.java_base;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import xyz.wagyourtail.jvmdg.version.JEP;
import xyz.wagyourtail.jvmdg.version.Modify;
import xyz.wagyourtail.jvmdg.version.Ref;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

@JEP(280)
public class J_L_I_StringConcatFactory {

    @Modify(ref = @Ref(value = "java/lang/invoke/StringConcatFactory", member = "makeConcat", desc = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"))
    public static void makeConcat(MethodNode mnode, int i) {
        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) mnode.instructions.get(i);
        Type[] args = Type.getArgumentTypes(indy.desc);
        char[] chars = new char[args.length];
        for (int j = 0; j < args.length; j++) {
            chars[j] = '\u0001';
        }
        InsnList list = makeConcatInline(mnode, new String(chars), args, new ArrayDeque<>());
        mnode.instructions.insertBefore(indy, list);
        mnode.instructions.remove(indy);
    }

    @Modify(ref = @Ref(value = "java/lang/invoke/StringConcatFactory", member = "makeConcatWithConstants", desc = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"))
    public static void makeConcatWithConstants(MethodNode mnode, int i) {
        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) mnode.instructions.get(i);
        Type[] args = Type.getArgumentTypes(indy.desc);
        Deque<Object> bsmArgs = new ArrayDeque<>(Arrays.asList(indy.bsmArgs));
        String chars = (String) bsmArgs.removeFirst();
        InsnList list = makeConcatInline(mnode, chars, args, bsmArgs);
        mnode.instructions.insertBefore(indy, list);
        mnode.instructions.remove(indy);
    }

    /*
     * This method makes the following code:
     * ALOAD 1
     * ILOAD 2
     * INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String; [
     *   java/lang/invoke/StringConcatFactory,
     *   "Hello, \u0001! You are \u0001",
     * ]
     * ARETURN
     *
     * into:
     * ALOAD 1
     * ILOAD 2
     * ISTORE 4
     * ASTORE 3
     * NEW java/lang/StringBuilder
     * DUP
     * INVOKESPECIAL java/lang/StringBuilder.<init> ()V
     * LDC "Hello, "
     * INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
     * ALOAD 3
     * INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
     * LDC "! You are "
     * INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
     * ILOAD 4
     * INVOKEVIRTUAL java/lang/StringBuilder.append (I)Ljava/lang/StringBuilder;
     * INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;
     * ARETURN
     *
     * the stores and loads are skipped when there is only one value on the stack,
     * as it can just be swapped with the StringBuilder.
     */
    public static InsnList makeConcatInline(MethodNode mnode, String args, Type[] types, Deque<Object> bsmArgs) {
        int stackArgs = 0;
        int constArgs = 0;
        for (int j = 0; j < args.length(); j++) {
            switch (args.charAt(j)) {
                case '\u0001':
                    stackArgs++;
                    break;
                case '\u0002':
                    constArgs++;
                    break;
                default:
                    break;
            }
        }
        if (stackArgs != types.length) {
            throw new IllegalStateException("Expected " + stackArgs + " types, got " + types.length);
        }
        if (constArgs != bsmArgs.size()) {
            throw new IllegalStateException("Expected " + constArgs + " constants, got " + bsmArgs.size());
        }
        InsnList list = new InsnList();
        if (stackArgs == 0 && constArgs == 0) {
            // no args
            list.add(new LdcInsnNode(args));
            return list;
        }
        // stack = [...types]
        int[] vars = new int[types.length];
        if (types.length > 1) {
            int var = firstFreeLocal(mnode);
            for (int j = 0; j < types.length; j++) {
                vars[j] = var;
                var += types[j].getSize();
            }
            mnode.maxLocals = Math.max(mnode.maxLocals, var);
            for (int j = types.length - 1; j >= 0; j--) {
                list.add(new ConcatVarInsnNode(types[j].getOpcode(Opcodes.ISTORE), vars[j]));
            }
            // stack = []
        }
        list.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        // stack = [StringBuilder]
        // or if there is only one type
        // stack = [type, StringBuilder]
        int index = 0;
        int typesIndex = 0;
        while (index < args.length()) {
            switch (args.charAt(index)) {
                case '\u0001':
                    Type type = types[typesIndex];
                    if (types.length > 1) {
                        // stack = [StringBuilder]
                        list.add(new ConcatVarInsnNode(type.getOpcode(Opcodes.ILOAD), vars[typesIndex]));
                    } else {
                        // stack = [type, StringBuilder]
                        switch (type.getSize()) {
                            case 1:
                                list.add(new InsnNode(Opcodes.SWAP));
                                break;
                            case 2:
                                list.add(new InsnNode(Opcodes.DUP_X2));
                                list.add(new InsnNode(Opcodes.POP));
                                break;
                            default:
                                throw new IllegalStateException("Unexpected value: " + type.getSize());
                        }
                    }
                    // stack = [StringBuilder, type]
                    String desc = type.getDescriptor();
                    switch (type.getSort()) {
                        case Type.OBJECT:
                        case Type.ARRAY:
                            if (!desc.equals("Ljava/lang/String;")) {
                                desc = "Ljava/lang/Object;";
                            }
                            break;
                        case Type.BYTE:
                        case Type.SHORT:
                            desc = "I";
                            break;
                        default:
                            break;
                    }
                    list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false));
                    // stack = [StringBuilder]
                    typesIndex++;
                    index++;
                    break;
                case '\u0002':
                    // stack = [StringBuilder]
                    list.add(new LdcInsnNode(bsmArgs.removeFirst()));
                    // stack = [StringBuilder, String]
                    list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    index++;
                    break;
                default:
                    int nextStack = args.indexOf('\u0001', index);
                    if (nextStack == -1) {
                        nextStack = args.length();
                    }
                    int nextArg = args.indexOf('\u0002', index);
                    if (nextArg == -1) {
                        nextArg = args.length();
                    }
                    int next = Math.min(nextStack, nextArg);
                    String literal = args.substring(index, next);
                    // stack = [StringBuilder]
                    list.add(new LdcInsnNode(literal));
                    // stack = [StringBuilder, String]
                    list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                    index = next;
                    break;
            }
        }
        // stack = [StringBuilder]
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        // stack = [String]
        return list;
    }

    /**
     * finds the first local that isn't used by the method itself.
     * maxLocals can't be trusted for this, as generated methods don't have it set until they are written.
     * locals from other concats are ignored, they're dead by the time the next concat runs, so the slots can be reused.
     */
    private static int firstFreeLocal(MethodNode mnode) {
        int free = Type.getArgumentsAndReturnSizes(mnode.desc) >> 2;
        if ((mnode.access & Opcodes.ACC_STATIC) != 0) {
            free--;
        }
        for (AbstractInsnNode insn : mnode.instructions) {
            if (insn instanceof ConcatVarInsnNode) {
                continue;
            }
            if (insn instanceof VarInsnNode) {
                int size;
                switch (insn.getOpcode()) {
                    case Opcodes.LLOAD:
                    case Opcodes.DLOAD:
                    case Opcodes.LSTORE:
                    case Opcodes.DSTORE:
                        size = 2;
                        break;
                    default:
                        size = 1;
                        break;
                }
                free = Math.max(free, ((VarInsnNode) insn).var + size);
            } else if (insn instanceof IincInsnNode) {
                free = Math.max(free, ((IincInsnNode) insn).var + 1);
            }
        }
        return free;
    }

    public static class ConcatVarInsnNode extends VarInsnNode {

        public ConcatVarInsnNode(int opcode, int var) {
            super(opcode, var);
        }

    }

}
