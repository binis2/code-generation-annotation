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
import net.binis.codegen.compiler.utils.ElementUtils;
import net.binis.codegen.enrich.handler.base.BaseEnricher;
import net.binis.codegen.generation.core.interfaces.MethodDescription;
import net.binis.codegen.generation.core.interfaces.PrototypeDescription;

import java.util.HashMap;
import java.util.Map;

import static net.binis.codegen.tools.Reflection.loadClass;

@Slf4j
public class ElementInsertionTestEnricher extends BaseEnricher {

    @Override
    public void enrich(PrototypeDescription<ClassOrInterfaceDeclaration> description) {
        ElementUtils.removeClassAnnotation(description.getElement(), Dummy.class);
        ElementUtils.addClassAnnotationAttribute(description.getElement(), CodePrototype.class, "strategy", GenerationStrategy.IMPLEMENTATION);
        ElementUtils.removeClassAnnotationAttribute(description.getElement(), CodePrototype.class, "interfaceName");
        ElementUtils.replaceClassAnnotationAttribute(description.getElement(), CodePrototype.class, "classGetters", false);

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

        ElementUtils.addClassAnnotation(description.getElement(), Dummy.class, map);
    }

    @Override
    public void enrichMethod(MethodDescription method) {
        ElementUtils.addMethodAnnotation(method.getElement(), lombok.Generated.class, Map.of());
        ElementUtils.removeMethodAnnotation(method.getElement(), CodePrototype.class);

        ElementUtils.replaceMethodAnnotationAttribute(method.getElement(), Default.class, "value", "zxc");

        if (method.getMethod().getNameAsString().equals("method2")) {
            ElementUtils.addMethodAnnotation(method.getElement(), Default.class, Map.of("value", "rty"));
        }
    }

    public int order() {
        return 0;
    }
}
