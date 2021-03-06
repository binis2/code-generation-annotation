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
import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.CodeGen;
import net.binis.codegen.annotation.CodePrototype;
import net.binis.codegen.annotation.builder.CodeBuilder;
import net.binis.codegen.annotation.builder.CodeQueryBuilder;
import net.binis.codegen.annotation.builder.CodeRequest;
import net.binis.codegen.annotation.builder.CodeValidationBuilder;
import net.binis.codegen.exception.GenericCodeGenException;
import net.binis.codegen.generation.core.interfaces.PrototypeData;
import net.binis.codegen.javaparser.CodeGenPrettyPrinter;
import net.binis.codegen.tools.Reflection;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static net.binis.codegen.generation.core.Helpers.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@AutoService(Processor.class)
public class CodeGenAnnotationProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;
    private Map<String, String> options;

    public CodeGenAnnotationProcessor() {
        super();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        options = processingEnv.getOptions();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            if (!processed()) {
                var files = new ArrayList<String>();
                processAnnotation(roundEnv, files, CodePrototype.class);
                processAnnotation(roundEnv, files, CodeBuilder.class);
                processAnnotation(roundEnv, files, CodeValidationBuilder.class);
                processAnnotation(roundEnv, files, CodeQueryBuilder.class);
                processAnnotation(roundEnv, files, CodeRequest.class);

                if (!files.isEmpty()) {

                    externalLookup(roundEnv);

                    CodeGen.processSources(files);

                    lookup.parsed().stream().filter(v -> nonNull(v.getFiles())).filter(p -> !p.isNested() || isNull(p.getParentClassName())).forEach(p -> {
                        if (isNull(p.getCompiled())) {
                            if (p.getProperties().isGenerateImplementation() && isNull(p.getProperties().getMixInClass())) {
                                saveFile(p.getFiles().get(0), getBasePath(p.getProperties(), true));
                            }
                            if (p.getProperties().isGenerateInterface()) {
                                saveFile(p.getFiles().get(1), getBasePath(p.getProperties(), false));
                            }
                        }
                    });
                }
            } else {
                log.info("Prototypes already processed!");
            }
        } catch (Exception e) {
            log.error("CodeGenAnnotationProcessor exception!", e);
        }

        return false;
    }

    private boolean processed() {
        try {
            return new File(processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "codegen.info").getName()).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void externalLookup(RoundEnvironment roundEnv) {
        lookup.registerExternalLookup(s -> {
            var ext = roundEnv.getRootElements().stream().filter(TypeElement.class::isInstance).map(TypeElement.class::cast).filter(e -> e.getQualifiedName().toString().equals(s)).findFirst();
            if (ext.isPresent()) {
                var source = Reflection.getFieldValue(ext.get(), "sourcefile");
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

    private void processAnnotation(RoundEnvironment roundEnv, List<String> files, Class<? extends Annotation> cls) {
        for (var type : roundEnv.getElementsAnnotatedWith(cls)) {
            try {
                var source = Reflection.getFieldValue(type, "sourcefile");
                log.info("Processing: {}", type.getSimpleName());
                files.add(((FileObject) source).getCharContent(true).toString());
            } catch (Exception e) {
                log.error("Unable to process {}", type);
            }
        }
    }

    private void saveFile(CompilationUnit unit, String path) {
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

    private static String getBasePath(PrototypeData properties, boolean implementation) {
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
        return Set.of("net.binis.codegen.annotation.CodePrototype",
                "net.binis.codegen.annotation.builder.CodeBuilder",
                "net.binis.codegen.annotation.builder.CodeQueryBuilder",
                "net.binis.codegen.annotation.builder.CodeValidationBuilder",
                "net.binis.codegen.annotation.builder.CodeRequest");
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);

    }
}
