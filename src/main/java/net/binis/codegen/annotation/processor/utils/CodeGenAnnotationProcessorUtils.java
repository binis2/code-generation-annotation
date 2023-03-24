package net.binis.codegen.annotation.processor.utils;

import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.annotation.processor.CodeGenAnnotationProcessor;
import net.binis.codegen.annotation.processor.utils.dummy.Parent;
import net.binis.codegen.tools.Reflection;
import sun.misc.Unsafe;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static java.util.Objects.nonNull;
import static net.binis.codegen.tools.Reflection.loadClass;

@Slf4j
public class CodeGenAnnotationProcessorUtils {

    public static void addOpensForCodeGen() {
        Class<?> cModule;
        try {
            cModule = Class.forName("java.lang.Module");
        } catch (ClassNotFoundException e) {
            return; //jdk8-; this is not needed.
        }

        Unsafe unsafe = getUnsafe();
        Object jdkCompilerModule = getJdkCompilerModule();
        Object ownModule = getOwnModule();
        String[] allPkgs = {
                "com.sun.tools.javac.code",
                "com.sun.tools.javac.comp",
                "com.sun.tools.javac.file",
                "com.sun.tools.javac.main",
                "com.sun.tools.javac.model",
                "com.sun.tools.javac.parser",
                "com.sun.tools.javac.processing",
                "com.sun.tools.javac.tree",
                "com.sun.tools.javac.util",
                "com.sun.tools.javac.jvm",
        };

        try {
            Method m = cModule.getDeclaredMethod("implAddOpens", String.class, cModule);
            long firstFieldOffset = getFirstFieldOffset(unsafe);
            unsafe.putBooleanVolatile(m, firstFieldOffset, true);
            for (String p : allPkgs) {
                m.invoke(jdkCompilerModule, p, ownModule);
            }
        } catch (Exception ignore) {
        }
    }

    private static long getFirstFieldOffset(Unsafe unsafe) {
        try {
            return unsafe.objectFieldOffset(Parent.class.getDeclaredField("first"));
        } catch (NoSuchFieldException e) {
            // can't happen.
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            // can't happen
            throw new RuntimeException(e);
        }
    }

    private static Unsafe getUnsafe() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object getJdkCompilerModule() {
        try {
            Class<?> cModuleLayer = Class.forName("java.lang.ModuleLayer");
            Method mBoot = cModuleLayer.getDeclaredMethod("boot");
            Object bootLayer = mBoot.invoke(null);
            Class<?> cOptional = Class.forName("java.util.Optional");
            Method mFindModule = cModuleLayer.getDeclaredMethod("findModule", String.class);
            Object oCompilerO = mFindModule.invoke(bootLayer, "jdk.compiler");
            return cOptional.getDeclaredMethod("get").invoke(oCompilerO);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object getOwnModule() {
        try {
            Method m = Reflection.findMethod("getModule", Class.class);
            return m.invoke(CodeGenAnnotationProcessor.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getJavacProcessingEnvironment(ProcessingEnvironment processingEnv, Object procEnv) {
        var cls = loadClass("com.sun.tools.javac.processing.JavacProcessingEnvironment");
        if (nonNull(cls)) {

            if (cls.isAssignableFrom(procEnv.getClass())) {
                return procEnv;
            }

            // try to find a "delegate" field in the object, and use this to try to obtain a JavacProcessingEnvironment
            for (Class<?> procEnvClass = procEnv.getClass(); procEnvClass != null; procEnvClass = procEnvClass.getSuperclass()) {
                Object delegate = tryGetDelegateField(procEnvClass, procEnv);
                if (delegate == null) delegate = tryGetProxyDelegateToField(procEnvClass, procEnv);
                if (delegate == null) delegate = tryGetProcessingEnvField(procEnvClass, procEnv);

                if (delegate != null) return getJavacProcessingEnvironment(processingEnv, delegate);
                // delegate field was not found, try on superclass
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Can't get the delegate of the gradle IncrementalProcessingEnvironment. Lombok won't work.");
        }
        return null;
    }

    /**
     * Gradle incremental processing
     */
    private static Object tryGetDelegateField(Class<?> delegateClass, Object instance) {
        try {
            return Reflection.getFieldValue(instance, "delegate");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * IntelliJ IDEA >= 2020.3
     */
    private static Object tryGetProxyDelegateToField(Class<?> delegateClass, Object instance) {
        try {
            InvocationHandler handler = Proxy.getInvocationHandler(instance);
            return Reflection.getFieldValue(handler, "val$delegateTo");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Kotlin incremental processing
     */
    private static Object tryGetProcessingEnvField(Class<?> delegateClass, Object instance) {
        try {
            return Reflection.getFieldValue(delegateClass, instance, "processingEnv");
        } catch (Exception e) {
            return null;
        }
    }

}
