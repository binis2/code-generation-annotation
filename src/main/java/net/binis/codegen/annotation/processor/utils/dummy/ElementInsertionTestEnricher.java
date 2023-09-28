package net.binis.codegen.annotation.processor.utils.dummy;

/*-
 * #%L
 * code-generator-annotation
 * %%
 * Copyright (C) 2021 - 2023 Binis Belev
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

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.extern.slf4j.Slf4j;
import net.binis.codegen.annotation.CodePrototype;
import net.binis.codegen.annotation.Default;
import net.binis.codegen.annotation.type.EmbeddedModifierType;
import net.binis.codegen.annotation.type.GenerationStrategy;
import net.binis.codegen.compiler.utils.ElementAnnotationUtils;
import net.binis.codegen.compiler.utils.ElementFieldUtils;
import net.binis.codegen.compiler.utils.ElementMethodUtils;
import net.binis.codegen.enrich.handler.base.BaseEnricher;
import net.binis.codegen.generation.core.interfaces.ElementDescription;
import net.binis.codegen.generation.core.interfaces.PrototypeDescription;
import net.binis.codegen.utils.dummy.Dummy;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static java.lang.reflect.Modifier.*;
import static net.binis.codegen.tools.Reflection.loadClass;

@Slf4j
public class ElementInsertionTestEnricher extends BaseEnricher {

    @Override
    public void enrich(PrototypeDescription<ClassOrInterfaceDeclaration> description) {
        var factoryMethod = ElementMethodUtils.createStaticMethodInvocation(Logger.class, "getLogger",
                ElementMethodUtils.createClassMethodInvocation(description.getElement().getSimpleName().toString(), "getName"));
        var field = ElementFieldUtils.addField(description.getElement(), "log", Logger.class, PUBLIC | STATIC | FINAL, factoryMethod);
        log.info("Added field: {}", field);

        ElementAnnotationUtils.removeAnnotation(description.getElement(), Dummy.class);
        ElementAnnotationUtils.addAnnotationAttribute(description.getElement(), CodePrototype.class, "strategy", GenerationStrategy.IMPLEMENTATION);
        ElementAnnotationUtils.removeAnnotationAttribute(description.getElement(), CodePrototype.class, "interfaceName");
        ElementAnnotationUtils.replaceAnnotationAttribute(description.getElement(), CodePrototype.class, "classGetters", false);

        var map = new HashMap<String, Object>();
        map.put("bool", true);
        map.put("integer", 10);
        map.put("lng", 15L);
        map.put("shrt", Short.parseShort("13"));
        map.put("dbl", 0.15);
        map.put("flt", Float.parseFloat("1.30"));
        map.put("byt", Byte.parseByte("120"));
        map.put("chr", 'Z');
        map.put("cls", loadClass("java.util.List"));
        map.put("typ", EmbeddedModifierType.BOTH);
        map.put("ints", new int[] {1, 2, 3, 4, 5});

        ElementAnnotationUtils.addAnnotation(description.getElement(), Dummy.class, map);
    }

    @Override
    public void enrichElement(ElementDescription element) {
        ElementAnnotationUtils.addAnnotation(element.getElement(), lombok.Generated.class, Map.of());
        ElementAnnotationUtils.removeAnnotation(element.getElement(), CodePrototype.class);

        ElementAnnotationUtils.replaceAnnotationAttribute(element.getElement(), Default.class, "value", "zxc");

        if (element.getElement().getSimpleName().toString().equals("method2")) {
            ElementAnnotationUtils.addAnnotation(element.getElement(), Default.class, Map.of("value", "rty"));
        }
    }

    public int order() {
        return 0;
    }
}
