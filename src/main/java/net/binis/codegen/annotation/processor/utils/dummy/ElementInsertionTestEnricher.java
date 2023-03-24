package net.binis.codegen.annotation.processor.utils.dummy;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.annotation.CodePrototype;
import net.binis.codegen.annotation.Default;
import net.binis.codegen.compiler.utils.ElementUtils;
import net.binis.codegen.enrich.handler.base.BaseEnricher;
import net.binis.codegen.generation.core.interfaces.PrototypeDescription;

import java.util.Map;

@Slf4j
public class ElementInsertionTestEnricher extends BaseEnricher {

    @Override
    public void enrich(PrototypeDescription<ClassOrInterfaceDeclaration> description) {
        ElementUtils.removeClassAnnotation(description.getElement(), CodePrototype.class);
        ElementUtils.addClassAnnotation(description.getElement(), Default.class, Map.of("value", "test"));
    }

    @Override
    public int order() {
        return 0;
    }
}
