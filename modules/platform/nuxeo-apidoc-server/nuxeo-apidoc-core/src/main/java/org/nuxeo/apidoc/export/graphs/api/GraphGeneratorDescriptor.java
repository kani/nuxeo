/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Anahide Tchertchian
 */
package org.nuxeo.apidoc.export.graphs.api;

import java.util.HashMap;
import java.util.Map;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XNodeMap;
import org.nuxeo.common.xmap.annotation.XObject;

/**
 * Descriptor for graph generators.
 *
 * @since 11.2
 */
@XObject("graphGenerator")
public class GraphGeneratorDescriptor {

    @XNode("@name")
    String name;

    @XNode("@class")
    Class<? extends GraphGenerator> clazz;

    @XNodeMap(value = "properties/property", key = "@name", type = HashMap.class, componentType = String.class)
    Map<String, String> properties = new HashMap<>();

    public String getName() {
        return name;
    }

    public Class<? extends GraphGenerator> getClazz() {
        return clazz;
    }

    public GraphGenerator getInstance() {
        try {
            GraphGenerator gg = getClazz().getDeclaredConstructor().newInstance();
            gg.setName(getName());
            gg.setProperties(getProperties());
            return gg;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, String> getProperties() {
        return properties;
    }

}
