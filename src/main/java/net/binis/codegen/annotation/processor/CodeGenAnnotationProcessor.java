package net.binis.codegen.annotation.processor;

/*-
 * #%L
 * code-generator-annotation
 * %%
 * Copyright (C) 2021 Binis Belev
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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.CodeGen;
import net.binis.codegen.annotation.CodeConfiguration;
import net.binis.codegen.annotation.CodePrototypeTemplate;
import net.binis.codegen.generation.core.Parsables;
import net.binis.codegen.utils.CodeGenAnnotationProcessorUtils;
import net.binis.codegen.discoverer.AnnotationDiscoverer;
import net.binis.codegen.discovery.Discoverer;
import net.binis.codegen.exception.GenericCodeGenException;
import net.binis.codegen.factory.CodeFactory;
import net.binis.codegen.generation.core.Structures;
import net.binis.codegen.generation.core.interfaces.PrototypeData;
import net.binis.codegen.generation.core.interfaces.PrototypeDescription;
import net.binis.codegen.javaparser.CodeGenPrettyPrinter;
import net.binis.codegen.tools.Reflection;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.lang.annotation.Annotation;
import java.util.*;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static net.binis.codegen.utils.CodeGenAnnotationProcessorUtils.addOpensForCodeGen;
import static net.binis.codegen.generation.core.Helpers.*;
import static net.binis.codegen.generation.core.Structures.defaultProperties;
import static net.binis.codegen.tools.Reflection.loadClass;
import static net.binis.codegen.tools.Tools.in;
import static net.binis.codegen.tools.Tools.with;
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

    static {
        addOpensForCodeGen(CodeGenAnnotationProcessor.class);
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
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            if (!processed()) {
                lookup.setRoundEnvironment(roundEnv);

                var files = Parsables.create();

                processConfigs(roundEnv);
                processTemplates(roundEnv, files);

                defaultProperties.keySet().stream()
                        .map(Reflection::loadClass)
                        .filter(Objects::nonNull)
                        .forEach(cls ->
                                processAnnotation(roundEnv, files, (Class) cls));

                if (!files.isEmpty()) {

                    externalLookup(roundEnv);

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
                saveFile(p.getFiles().get(0), getBasePath(p.getProperties(), true));
            }
            if (p.getProperties().isGenerateInterface()) {
                saveFile(p.getFiles().get(1), getBasePath(p.getProperties(), false));
            }
            p.getCustomFiles().forEach((name, file) -> {
                if (nonNull(file.getJavaClass())) {
                    saveFile(file.getJavaClass().findCompilationUnit().get(), getBasePath(p.getProperties(), true));
                }
                //TODO: Save non java custom files.
            });
        }
    }

    protected void processTemplates(RoundEnvironment roundEnv, Parsables files) {
        roundEnv.getElementsAnnotatedWith(CodePrototypeTemplate.class).forEach(element ->
                with(readElementSource(element), source -> {
                    CodeGen.processTemplate(element.getSimpleName().toString(), source);
                    AnnotationDiscoverer.writeTemplate(filer, element.toString());
                    roundEnv.getElementsAnnotatedWith((TypeElement) element).forEach(e ->
                            with(readElementSource(e), s ->
                                    files.file(s).add(e)));
                }));
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

    protected static boolean isPrototypeTest() {
        var cls = loadClass("net.binis.codegen.test.BaseCodeGenTest");
        if (nonNull(cls)) {
            if (nonNull(CodeFactory.create(cls))) {
                return true;
            }
        }
        return false;
    }

    protected static boolean isElementTest() {
        var cls = loadClass("net.binis.codegen.test.BaseCodeGenElementTest");
        if (nonNull(cls)) {
            if (nonNull(CodeFactory.create(cls))) {
                return true;
            }
        }
        return false;
    }


    protected void externalLookup(RoundEnvironment roundEnv) {
        lookup.registerExternalLookup(s -> {
            var ext = roundEnv.getRootElements().stream().filter(TypeElement.class::isInstance).map(TypeElement.class::cast).filter(e -> e.getQualifiedName().toString().equals(s)).findFirst();
            if (ext.isPresent()) {
                var source = Reflection.getFieldValueUnsafe(ext.get(), "sourcefile");
                if (isNull(source)) {
                    source = Reflection.getFieldValue(ext.get(), "sourcefile");
                }
                log.info("Accessing: {}", ext.get().getSimpleName());
                try {
                    return ((FileObject) source).getCharContent(true).toString();
                } catch (Exception ex) {
                    log.error("Unable to read {}", ext.get());
                }
            }
            return null;
        });
    }

    protected void processAnnotation(RoundEnvironment roundEnv, Parsables files, Class<? extends Annotation> cls) {
        for (var type : roundEnv.getElementsAnnotatedWith(cls)) {
            with(readElementSource(type), source ->
                    files.file(source).add(type));
        }
    }

    protected static String readElementSource(Element eType) {
        var type = findClassType(eType);
        try {
            JavaFileObject source = Reflection.getFieldValueUnsafe(type, "sourcefile");
            if (isNull(source)) {
                source = Reflection.getFieldValue(type, "sourcefile");
            }
            log.info("Processing: {} ({}: {})", type.getSimpleName(), eType.getKind(), eType.getSimpleName().toString());
            return source.getCharContent(true).toString();
        } catch (Exception e) {
            log.error("Unable to process {}", type);
        }
        return null;
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

    protected void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);

    }

}
