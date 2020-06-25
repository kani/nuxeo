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
package org.nuxeo.apidoc.export.graphs.plugins;

import java.util.LinkedHashMap;

import org.nuxeo.apidoc.export.graphs.api.Edge;
import org.nuxeo.apidoc.export.graphs.api.EditableGraph;
import org.nuxeo.apidoc.export.graphs.api.GraphExporter;
import org.nuxeo.apidoc.export.graphs.api.Node;
import org.nuxeo.apidoc.export.graphs.introspection.AbstractGraphExporter;
import org.nuxeo.apidoc.export.graphs.introspection.ContentGraphImpl;
import org.nuxeo.apidoc.export.graphs.introspection.EdgeImpl;
import org.nuxeo.apidoc.export.graphs.introspection.NodeImpl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Exporter for Plotly graph format.
 *
 * @since 11.2
 */
public class JsonGraphExporter extends AbstractGraphExporter implements GraphExporter {

    public JsonGraphExporter(EditableGraph graph) {
        super(graph);
    }

    @Override
    public ContentGraphImpl export() {
        ContentGraphImpl cgraph = initContentGraph(graph);

        final ObjectMapper mapper = new ObjectMapper().registerModule(
                new SimpleModule().addAbstractTypeMapping(Node.class, NodeImpl.class)
                                  .addAbstractTypeMapping(Edge.class, EdgeImpl.class));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("name", graph.getName());
        values.put("title", graph.getTitle());
        values.put("description", graph.getDescription());
        values.put("type", graph.getType());
        if (!graph.getProperties().isEmpty()) {
            values.put("properties", graph.getProperties());
        }
        values.put("nodes", graph.getNodes());
        values.put("edges", graph.getEdges());
        try {
            String content = mapper.writerFor(LinkedHashMap.class)
                                   .with(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)
                                   .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                                   .withDefaultPrettyPrinter()
                                   .writeValueAsString(values);
            cgraph.setContent(content);
            cgraph.setContentName("graph.json");
            cgraph.setContentType("application/json");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return cgraph;
    }

}
