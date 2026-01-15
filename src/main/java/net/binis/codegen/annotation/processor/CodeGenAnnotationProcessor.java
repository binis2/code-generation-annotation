package net.binis.codegen.annotation.processor;

/*-
 * #%L
 * code-generator-annotation
 * %%
 * Copyright (C) 2021 - 2026 Binis Belev
 * %%
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
 * #L%
 */

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.CodeGen;
import net.binis.codegen.annotation.CodeConfiguration;
import net.binis.codegen.annotation.CodePrototypeTemplate;
import net.binis.codegen.compiler.CGSymbol;
import net.binis.codegen.discoverer.AnnotationDiscoverer;
import net.binis.codegen.discovery.Discoverer;
import net.binis.codegen.enrich.CustomDescription;
import net.binis.codegen.exception.GenericCodeGenException;
import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.generation.core.Parsables;
import net.binis.codegen.generation.core.Structures;
import net.binis.codegen.generation.core.interfaces.PrototypeData;
import net.binis.codegen.generation.core.interfaces.PrototypeDescription;
import net.binis.codegen.javaparser.CodeGenPrettyPrinter;
import net.binis.codegen.objects.Pair;
import net.binis.codegen.tools.Holder;
import net.binis.codegen.tools.Reflection;
import net.binis.codegen.utils.CodeGenAnnotationProcessorUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static net.binis.codegen.generation.core.Helpers.*;
import static net.binis.codegen.generation.core.Structures.defaultProperties;
import static net.binis.codegen.generation.core.Structures.supportedOptions;
import static net.binis.codegen.tools.Tools.in;
import static net.binis.codegen.tools.Tools.with;
import static net.binis.codegen.utils.CodeGenAnnotationProcessorUtils.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@AutoService(Processor.class)
public class CodeGenAnnotationProcessor extends AbstractProcessor {

    protected Types typeUtils;
    protected Elements elementUtils;
    protected Filer filer;
    protected Messager messager;
    protected Map<String, String> options;
    protected List<Discoverer.DiscoveredService> discovered;
    protected JavaFileManager fileManager;
    protected File targetDir;
    protected Set<String> sourceRoots;

    static {
        addOpensForCodeGen(true);
        CodeFactory.registerType(ProcessingEnvironment.class, params -> CodeGenAnnotationProcessorUtils.getJavacProcessingEnvironment(lookup.getProcessingEnvironment(), lookup.getProcessingEnvironment()));
    }

    public CodeGenAnnotationProcessor() {
        super();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        log.info("Initializing CodeGenAnnotationProcessor...");
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        options = processingEnv.getOptions();
        lookup.setProcessingEnvironment(processingEnv);
        fileManager = getFileManager(processingEnv);
    }

    protected JavaFileManager getFileManager(ProcessingEnvironment processingEnv) {
        try {
            JavaFileManager result = Reflection.getFieldValue(processingEnv, "fileManager");
            if (nonNull(result)) {
                return result;
            }
            //Try to get IDEA file manager
            result = Reflection.getFieldValue(Proxy.getInvocationHandler(Reflection.getFieldValue((Object) Reflection.getFieldValue(Proxy.getInvocationHandler(processingEnv), "val$delegateTo"), "fileManager")), "val$wrapper");
            if (nonNull(result)) {
                return result;
            }
        } catch (Exception e) {
            //Do nothing
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            if (!processed()) {
                var elements = new ArrayList<Element>();
                CodeFactory.registerType(CodeFactory.class, () -> elements);
                lookup.setRoundEnvironment(roundEnv);
                externalLookup(roundEnv);

                var files = Parsables.create();

                processConfigs(roundEnv);
                processTemplates(roundEnv, files);

                defaultProperties.keySet().stream()
                        .map(Reflection::loadClass)
                        .filter(Objects::nonNull)
                        .forEach(cls ->
                                processAnnotation(roundEnv, files, (Class) cls, elements));

                if (!files.isEmpty()) {

                    CodeGen.processSources(files);

                    if (!isElementTest()) {
                        lookup.parsed().stream()
                                .filter(PrototypeDescription::isProcessed)
                                .filter(p -> !p.isNested() || isNull(p.getParentClassName()))
                                .forEach(this::saveParsed);
                        lookup.custom().forEach(this::saveParsed);
                    }
                }
            } else {
                if (isNull(sourceRoots)) {
                    externalLookup(roundEnv);
                }
                log.debug("Prototypes already processed!");
            }
        } catch (Exception e) {
            log.error("CodeGenAnnotationProcessor exception!", e);
        }

        return false;
    }

    protected void saveParsed(PrototypeDescription<ClassOrInterfaceDeclaration> p) {
        if (isNull(p.getCompiled())) {
            if (p.getProperties().isGenerateImplementation() && isNull(p.getProperties().getMixInClass())) {
                if (p instanceof CustomDescription desc && nonNull(desc.getPath())) {
                    saveFile(p.getFiles().get(0), desc.getPath());
                } else {
                    saveFile(p.getFiles().get(0), getBasePath(p.getProperties(), true));
                }
            }
            if (p.getProperties().isGenerateInterface()) {
                if (p instanceof CustomDescription desc && nonNull(desc.getPath())) {
                    saveFile(p.getFiles().get(1), desc.getPath());
                } else {
                    saveFile(p.getFiles().get(1), getBasePath(p.getProperties(), false));
                }
            }
            with(p.getCustomFiles(), custom -> custom.forEach((name, file) -> {
                if (nonNull(file.getJavaClass())) {
                    if (p instanceof CustomDescription desc && nonNull(desc.getPath())) {
                        saveFile(file.getJavaClass().findCompilationUnit().get(), desc.getPath());
                    } else {
                        saveFile(file.getJavaClass().findCompilationUnit().get(), getBasePath(p.getProperties(), true));
                    }
                }
                //TODO: Save non java custom files.
            }));
        }
    }

    protected void processTemplates(RoundEnvironment roundEnv, Parsables files) {
        if (nonNull(targetDir) && nonNull(sourceRoots)) {
            var file = new File(targetDir.getAbsolutePath() + "/binis/annotations");
            if (file.exists()) {
                AnnotationDiscoverer.findAnnotations(file).stream()
                        .filter(Discoverer.DiscoveredService::isTemplate)
                        .filter(a -> !Structures.defaultProperties.containsKey(a.getName()))
                        .forEach(template ->
                                sourceRoots.forEach(root -> with(classNameToFile(root, template.getName()), f -> {
                                    if (f.exists()) {
                                        try {
                                            var source = Files.readString(f.toPath(), Charset.defaultCharset());
                                            var result = lookup.getParser().parse(source);
                                            if (result.isSuccessful()) {
                                                var unit = result.getResult().get();
                                                if (unit.getType(0) instanceof AnnotationDeclaration ann) {
                                                    log.info("Processing template: {}", ann.getNameAsString());
                                                    Structures.registerTemplate(ann);
                                                }
                                            } else {
                                                log.error("Failed template processing ({}) with:", template.getName());
                                            }
                                        } catch (Exception e) {
                                            log.error("Unable to read {}", f);
                                        }
                                    }
                                })));
            }
        }

        var templates = new LinkedHashMap<String, Pair<CompilationUnit, Boolean>>();
        roundEnv.getElementsAnnotatedWith(CodePrototypeTemplate.class).forEach(element ->
                with(readElementSource(element, null, null), source -> {
                    var result = lookup.getParser().parse(source);
                    if (result.isSuccessful()) {
                        templates.put(element.toString(), Pair.of(result.getResult().get(), true));
                    } else {
                        log.error("Failed template processing ({}) with:", element.toString());
                        result.getProblems().forEach(p ->
                                log.error("    {}:{} {}", p.getCause().map(Object::toString).orElse(""), p.getMessage(), p.getLocation().map(Object::toString).orElse("")));
                    }
                    AnnotationDiscoverer.writeTemplate(filer, element.toString());
                    var fileName = Holder.<String>blank();
                    roundEnv.getElementsAnnotatedWith((TypeElement) element).forEach(e ->
                            with(readElementSource(e, element, fileName), s ->
                                    files.file(s).add(e, element, fileName.get())));
                }));

        var shouldBreak = Holder.of(false);
        var lastRun = -1;
        var passes = Holder.of(0);
        while (!templates.isEmpty()) {
            if (lastRun == templates.size()) {
                if (passes.get() > 2) {
                    break;
                } else {
                    passes.set(passes.get() + 1);
                }
            } else {
                lastRun = templates.size();
                passes.set(0);
            }
            for (var entry : templates.entrySet()) {
                var ann = entry.getValue().getKey().getChildNodes().stream()
                        .filter(AnnotationDeclaration.class::isInstance)
                        .map(AnnotationDeclaration.class::cast)
                        .filter(c ->
                                c.getFullyQualifiedName().filter(templates::containsKey).isPresent())
                        .findFirst();
                if (ann.isPresent()) {
                    if (ann.get().getAnnotations().stream()
                            .map(a -> getExternalClassName(ann.get(), a.getNameAsString()))
                            .noneMatch(name -> {
                                var pair = templates.get(name);
                                if (nonNull(pair)) {
                                    return passes.get() < 2 || pair.getValue();
                                } else {
                                    if (!defaultProperties.containsKey(name)) {
                                        var ext = lookup.findExternal(name);
                                        if (nonNull(ext)) {
                                            templates.put(name, Pair.of(ext.getDeclarationUnit(), false));
                                            shouldBreak.set(true);
                                            return true;
                                        }
                                    } else if (!entry.getValue().getValue()) {
                                        entry.setValue(Pair.of(entry.getValue().getKey(), true));
                                        shouldBreak.set(true);
                                    }
                                }
                                return false;
                            })) {
                        if (entry.getValue().getValue()) {
                            CodeGen.processTemplate(ann.get().getNameAsString(), entry.getValue().getKey());
                            templates.remove(entry.getKey());
                            shouldBreak.set(true);
                        }
                    }
                }
                if (shouldBreak.get()) {
                    shouldBreak.set(false);
                    break;
                }
            }
        }

        templates.forEach((name, pair) -> {
            if (pair.getValue()) {
                log.warn("Possible template not processed: {}", name);
            }
        });
    }

    protected void processConfigs(RoundEnvironment roundEnv) {
        roundEnv.getElementsAnnotatedWith(CodeConfiguration.class).forEach(element ->
                AnnotationDiscoverer.writeConfig(filer, element.toString()));
    }


    protected boolean processed() {
        if (isPrototypeTest()) {
            return true;
        }

        try {
            return new File(processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "codegen.info").getName()).exists();
        } catch (Exception e) {
            return false;
        }
    }

    protected void externalLookup(RoundEnvironment roundEnv) {
        var roots = new HashSet<String>();
        if (nonNull(fileManager)) {
            var method = Reflection.findMethod("getLocation", fileManager.getClass(), JavaFileManager.Location.class);
            if (isNull(method)) {
                try {
                    var manager = Reflection.getFieldValue((Object) Reflection.getFieldValue(this.fileManager, "clientJavaFileManager"), "fileManager");
                    method = Reflection.findMethod("getLocation", fileManager.getClass(), JavaFileManager.Location.class);
                } catch (Exception e) {
                    //Do nothing
                }
            }
            if (nonNull(method)) {
                if (Reflection.invoke(method, fileManager, StandardLocation.SOURCE_PATH) instanceof Iterable<?> files) {
                    files.forEach(f -> {
                        if (f instanceof File file) {
                            var path = file.getAbsolutePath();
                            roots.add(path);
                            if (isNull(sourceRoots)) {
                                log.info("Sources Root: {}", path);
                            }
                        }
                    });
                    if (Reflection.invoke(method, fileManager, StandardLocation.CLASS_OUTPUT) instanceof Iterable<?> targets) {
                        targetDir = (File) targets.iterator().next();
                    }
                }
            }
        }

        if (roots.isEmpty()) {
            var rootElements = roundEnv.getRootElements().stream().filter(TypeElement.class::isInstance).toList();
            rootElements.forEach(e -> {
                var source = getSourceFile(e);
                if (source instanceof FileObject fileObject) {
                    try {
                        var file = new File(fileObject.toUri());
                        while (nonNull(file.getParent())) {
                            file = file.getParentFile();
                            if ("java".equals(file.getName())) {
                                var root = file.getAbsolutePath();
                                roots.add(root);
                                if (isNull(sourceRoots)) {
                                    log.info("Sources Root: {}", root);
                                }
                                break;
                            }
                        }
                    } catch (Exception ex) {
                        //Ignore
                    }
                }
            });
        }
        lookup.registerExternalLookup(s -> {
            var ext = roundEnv.getRootElements().stream().filter(TypeElement.class::isInstance).map(TypeElement.class::cast).filter(e -> e.getQualifiedName().toString().equals(s)).findFirst();
            if (ext.isPresent()) {
                var source = getSourceFile(ext.get());
                log.info("Accessing: {}", ext.get().getSimpleName());
                try {
                    return ((FileObject) source).getCharContent(true).toString();
                } catch (Exception ex) {
                    log.error("Unable to read {}", ext.get());
                }
            } else if (!roots.isEmpty()) {
                for (var root : roots) {
                    var file = classNameToFile(root, s);
                    if (file.exists()) {
                        try {
                            return Files.readString(file.toPath(), Charset.defaultCharset());
                        } catch (Exception ex) {
                            log.error("Unable to read {}", file);
                        }
                    }
                }
            }
            return null;
        });
        sourceRoots = roots;
        lookup.setSourcesRoots(roots);
    }

    protected File classNameToFile(String root, String className) {
        return new File(root + '/' + className.replace(".", "/") + ".java");
    }

    protected Object getSourceFile(Element element) {
        var source = Reflection.getFieldValueUnsafe(element, "sourcefile");
        if (isNull(source)) {
            return Reflection.getFieldValue(element, "sourcefile");
        }
        return source;
    }

    protected void processAnnotation(RoundEnvironment roundEnv, Parsables files, Class<? extends Annotation> cls, List<Element> elements) {
        for (var type : roundEnv.getElementsAnnotatedWith(cls)) {
            var fileName = Holder.<String>blank();
            elements.add(type);
            with(readElementSource(type, cls, fileName), source ->
                    files.file(source).add(type, cls, fileName.get()));
        }
    }

    protected static String readElementSource(Element eType, Object annotation, Holder<String> fileName) {
        var type = findClassType(eType);
        try {
            JavaFileObject source = Reflection.getFieldValueUnsafe(type, "sourcefile");
            if (isNull(source)) {
                source = Reflection.getFieldValue(type, "sourcefile");
            }
            log.info("Processing: {} ({}: {}{})", type.getSimpleName(), eType.getKind(), eType.getSimpleName().toString(), nonNull(annotation) ? " - @" + calcAnnotationName(annotation) : "");
            if (nonNull(fileName)) {
                try {
                    fileName.set(source.toUri().toString());
                } catch (Exception e) {
                    fileName.set("<unknown>");
                }
            }
            return source.getCharContent(true).toString();
        } catch (Exception e) {
            log.error("Unable to process {}", type);
        }
        return null;
    }

    protected static String calcAnnotationName(Object annotation) {
        if (annotation instanceof Class cls) {
            return cls.getSimpleName();
        } else if ("com.sun.tools.javac.code.Symbol.ClassSymbol".equals(annotation.getClass().getCanonicalName())) {
            return new CGSymbol(annotation).getName();
        } else {
            return "unknown";
        }
    }

    protected static Element findClassType(Element type) {
        if (in(type.getKind(), ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.ANNOTATION_TYPE, ElementKind.ENUM)) {
            return type;
        }
        type = type.getEnclosingElement();
        if (nonNull(type)) {
            return findClassType(type);
        }
        return null;
    }

    protected void saveFile(CompilationUnit unit, String path) {
        if (nonNull(unit)) {
            var type = unit.getType(0);
            try {
                var printer = new CodeGenPrettyPrinter();

                sortImports(unit);
                if (unit.getType(0).isClassOrInterfaceDeclaration()) {
                    sortClass(unit.getType(0).asClassOrInterfaceDeclaration());
                }

                if (isNull(path)) {
                    try (var stream = filer.createSourceFile(type.getFullyQualifiedName().get()).openOutputStream()) {
                        log.info("Writing file - {}", type.getFullyQualifiedName().get());
                        try (var writer = new PrintWriter(stream)) {
                            writer.write(printer.print(unit));
                        }
                    }
                } else {
                    unit.getPackageDeclaration().ifPresent(p -> {
                        var fileName = path + '/' + p.getNameAsString().replace(".", "/") + '/' + unit.getType(0).getNameAsString() + ".java";
                        log.info("Writing file - {}", fileName);
                        var f = new File(fileName);
                        if (f.getParentFile().exists() || f.getParentFile().mkdirs()) {
                            try {
                                var writer = new BufferedWriter(new FileWriter(fileName));
                                writer.write(printer.print(unit));
                                writer.close();
                            } catch (IOException e) {
                                log.error("Unable to open for write file {}", fileName);
                            }
                        } else {
                            log.error("Unable to write file {}", fileName);
                        }
                    });
                }
            } catch (Exception e) {
                throw new GenericCodeGenException("Unable to save " + type.getFullyQualifiedName().get(), e);
            }
        }
    }

    protected static String getBasePath(PrototypeData properties, boolean implementation) {
        String result = null;

        if (isNotBlank(properties.getBasePath())) {
            result = properties.getBasePath();
        }

        if (implementation) {
            if (isNotBlank(properties.getImplementationPath())) {
                result = properties.getImplementationPath();
            }
        } else {
            if (isNotBlank(properties.getInterfacePath())) {
                result = properties.getInterfacePath();
            }
        }

        return result;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        discovered = AnnotationDiscoverer.findAnnotations();
        discovered.stream().filter(Discoverer.DiscoveredService::isTemplate).forEach(a ->
                Structures.registerTemplate(a.getCls()));
        var result = new HashSet<>(defaultProperties.keySet());
        result.add(CodePrototypeTemplate.class.getCanonicalName());
        result.add(CodeConfiguration.class.getCanonicalName());
        return result;
    }

    @Override
    public Set<String> getSupportedOptions() {
        return supportedOptions;
    }

    protected void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);

    }

}
