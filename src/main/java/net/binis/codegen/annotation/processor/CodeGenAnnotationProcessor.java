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
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.CodeGen;
import net.binis.codegen.annotation.CodePrototype;
import net.binis.codegen.annotation.builder.CodeBuilder;
import net.binis.codegen.annotation.builder.CodeQueryBuilder;
import net.binis.codegen.annotation.builder.CodeValidationBuilder;
import net.binis.codegen.exception.GenericCodeGenException;
import net.binis.codegen.tools.Reflection;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static net.binis.codegen.generation.core.Helpers.*;

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
            var files = new ArrayList<String>();
            processAnnotation(roundEnv, files, CodePrototype.class);
            processAnnotation(roundEnv, files, CodeBuilder.class);
            processAnnotation(roundEnv, files, CodeValidationBuilder.class);
            processAnnotation(roundEnv, files, CodeQueryBuilder.class);

            if (!files.isEmpty()) {

                externalLookup(roundEnv);

                CodeGen.processSources(files);

                lookup.parsed().stream().filter(v -> nonNull(v.getFiles())).forEach(p -> {
                    if (p.getProperties().isGenerateImplementation() && isNull(p.getProperties().getMixInClass())) {
                        saveFile(p.getFiles().get(0));
                    }
                    if (p.getProperties().isGenerateInterface()) {
                        saveFile(p.getFiles().get(1));
                    }
                });
            }
        } catch (Exception e) {
            log.error("CodeGenAnnotationProcessor exception!", e);
        }

        return false;
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

    private void saveFile(CompilationUnit unit) {
        var type = unit.getType(0);
        try {
            var config = new DefaultPrinterConfiguration();
            var printer = new DefaultPrettyPrinter(config);

            sortImports(unit);
            if (unit.getType(0).isClassOrInterfaceDeclaration()) {
                sortClass(unit.getType(0).asClassOrInterfaceDeclaration());
            }

            try (var stream = filer.createSourceFile(type.getFullyQualifiedName().get()).openOutputStream()) {
                log.info("Writing file - {}", type.getFullyQualifiedName().get());
                try (var writer = new PrintWriter(stream)) {
                    writer.write(printer.print(unit));
                }
            }
        } catch (Exception e) {
            throw new GenericCodeGenException("Unable to save " + type.getFullyQualifiedName().get(), e);
        }
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
                "net.binis.codegen.annotation.builder.CodeValidationBuilder");
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                String.format(msg, args),
                e);

    }
}
