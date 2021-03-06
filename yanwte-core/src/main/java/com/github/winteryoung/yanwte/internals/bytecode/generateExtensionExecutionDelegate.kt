package com.github.winteryoung.yanwte.internals.bytecode

import com.github.winteryoung.yanwte.ExtensionPointInput
import com.github.winteryoung.yanwte.ExtensionPointOutput
import com.github.winteryoung.yanwte.internals.ExtensionExecution
import com.github.winteryoung.yanwte.internals.ExtensionPoint
import com.github.winteryoung.yanwte.internals.utils.toDescriptor
import com.github.winteryoung.yanwte.internals.utils.toInternalName
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.lang.reflect.Method

/*
This function is effectively the same as

public class ExtensionExecutionDelegate implements ExtensionExecution {
    public ExtensionExecutionDelegate(TestExtensionPoint target) {
        this.target = target;
    }

    private TestExtensionPoint target;

    @Override
    public ExtensionPointOutput execute(ExtensionPointInput input) {
        List<Object> args = input.getArgs();
        Double value = target.foo((Integer) args.get(0), (Long) args.get(1));
        return new ExtensionPointOutput(value);
    }
}
*/
/**
 * Generates an extension execution delegate from the given extension instance.
 */
internal fun generateExtensionExecutionDelegate(
        extensionPoint: ExtensionPoint,
        extension: Any
): ExtensionExecution {
    if (!extensionPoint.samInterface.isAssignableFrom(extension.javaClass)) {
        throw IllegalArgumentException()
    }

    val (name, bytes) = generateExtensionExecutionDelegateBytes(extensionPoint)
    return loadExtensionPointDelegateInstance(name, bytes, extension)
}

private fun generateExtensionExecutionDelegateBytes(
        extensionPoint: ExtensionPoint
): Pair<String, ByteArray> {
    val extensionPointInterfaceClass = extensionPoint.samInterface
    val extensionExecutionClass = ExtensionExecution::class.java
    val delegateMethod = extensionPoint.method
    val generatedType = Type.getObjectType("${extensionPoint.samInterface.toInternalName()}__yanwteDelegate__")

    checkDelegateMethodTypeIntegrity(delegateMethod, extensionPointInterfaceClass)

    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(
            V1_6,
            ACC_PUBLIC + ACC_SUPER,
            generatedType.internalName,
            null,
            "java/lang/Object",
            arrayOf(extensionExecutionClass.toInternalName())
    )

    buildFields(cw, extensionPointInterfaceClass)
    buildConstructor(cw, extensionPointInterfaceClass, generatedType)
    buildExecuteMethod(cw, delegateMethod, extensionPointInterfaceClass, generatedType)

    cw.visitEnd()

    return "${extensionPointInterfaceClass.name}__yanwteDelegate__" to cw.toByteArray()
}

private fun buildExecuteMethod(
        cw: ClassWriter,
        delegateMethod: Method,
        extensionPointInterfaceClass: Class<*>,
        generatedType: Type
) {
    val delegateMethodParamsSig = delegateMethod.parameterTypes.map {
        it.toDescriptor()
    }.joinToString("")
    val delegateMethodSig = "($delegateMethodParamsSig)${delegateMethod.returnType.toDescriptor()}"
    val extensionPointInputClass = ExtensionPointInput::class.java
    val extensionPointOutputClass = ExtensionPointOutput::class.java

    cw.visitMethod(
            ACC_PUBLIC,
            "execute",
            "(${extensionPointInputClass.toDescriptor()})${extensionPointOutputClass.toDescriptor()}",
            null,
            null
    ).run {
        visitCode()

        val l0 = Label() // List<Object> args = input.getArgs();
        visitLabel(l0)
        visitVarInsn(ALOAD, 1)
        visitMethodInsn(
                INVOKEVIRTUAL,
                extensionPointInputClass.toInternalName(),
                "getArgs",
                "()Ljava/util/List;",
                false
        )
        visitVarInsn(ASTORE, 2)

        val l1 = Label() // Double value = target.foo((Integer) args.get(0), (Long) args.get(1));
        visitLabel(l1)
        visitVarInsn(ALOAD, 0)
        visitFieldInsn(
                GETFIELD,
                generatedType.internalName,
                "target",
                extensionPointInterfaceClass.toDescriptor()
        )
        delegateMethod.parameterTypes.forEachIndexed { i, paramType ->
            visitVarInsn(ALOAD, 2)
            visitLdcInsn(i)
            visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
            visitTypeInsn(CHECKCAST, paramType.toInternalName())
        }
        visitMethodInsn(
                INVOKEINTERFACE,
                extensionPointInterfaceClass.toInternalName(),
                delegateMethod.name,
                delegateMethodSig,
                true
        )
        if (delegateMethod.returnType != Void.TYPE) {
            visitVarInsn(ASTORE, 3)
        }

        val l2 = Label() // return new ExtensionPointOutput(value);
        visitLabel(l2)
        visitTypeInsn(NEW, extensionPointOutputClass.toInternalName())
        visitInsn(DUP)
        if (delegateMethod.returnType == Void.TYPE) {
            visitInsn(ACONST_NULL)
        } else {
            visitVarInsn(ALOAD, 3)
        }
        visitMethodInsn(
                INVOKESPECIAL,
                extensionPointOutputClass.toInternalName(),
                "<init>",
                "(Ljava/lang/Object;)V",
                false
        )
        visitInsn(ARETURN)

        val l3 = Label() // define locals
        visitLabel(l3)
        visitLocalVariable("this", generatedType.descriptor, null, l0, l3, 0)
        visitLocalVariable("input", extensionPointInputClass.toDescriptor(), null, l0, l3, 1)
        visitLocalVariable("args", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/Object;>;", l1, l3, 2)
        if (delegateMethod.returnType != Void.TYPE) {
            visitLocalVariable("value", delegateMethod.returnType.toDescriptor(), null, l2, l3, 3)
        }

        visitMaxs(0, 0)
        visitEnd()
    }
}

private fun buildFields(cw: ClassWriter, extensionPointInterfaceClass: Class<*>) {
    cw.visitField(
            ACC_PRIVATE,
            "target",
            extensionPointInterfaceClass.toDescriptor(),
            null,
            null
    ).run {
        visitEnd()
    }
}

/**
 * this.target = target
 */
private fun buildConstructor(
        cw: ClassWriter,
        extensionPointInterfaceClass: Class<*>,
        generatedType: Type
) {
    cw.visitMethod(
            ACC_PUBLIC,
            "<init>",
            "(${extensionPointInterfaceClass.toDescriptor()})V",
            null,
            null
    ).run {
        visitCode()
        val l0 = Label()
        visitLabel(l0)
        visitVarInsn(ALOAD, 0)
        visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        val l1 = Label()
        visitLabel(l1)
        visitVarInsn(ALOAD, 0)
        visitVarInsn(ALOAD, 1)
        visitFieldInsn(
                PUTFIELD,
                generatedType.internalName,
                "target",
                extensionPointInterfaceClass.toDescriptor()
        )
        val l2 = Label()
        visitLabel(l2)
        visitInsn(RETURN)
        val l3 = Label()
        visitLabel(l3)
        visitLocalVariable("this", generatedType.descriptor, null, l0, l3, 0)
        visitLocalVariable("target", extensionPointInterfaceClass.toDescriptor(), null, l0, l3, 1)
        visitMaxs(0, 0)
        visitEnd()
    }
}