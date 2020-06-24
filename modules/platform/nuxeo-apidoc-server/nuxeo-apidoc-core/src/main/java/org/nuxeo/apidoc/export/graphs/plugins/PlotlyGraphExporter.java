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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.nuxeo.apidoc.api.NuxeoArtifact;
import org.nuxeo.apidoc.export.graphs.api.Edge;
import org.nuxeo.apidoc.export.graphs.api.EditableGraph;
import org.nuxeo.apidoc.export.graphs.api.GraphExporter;
import org.nuxeo.apidoc.export.graphs.api.Node;
import org.nuxeo.apidoc.export.graphs.introspection.ContentGraphImpl;
import org.nuxeo.apidoc.export.graphs.introspection.EdgeImpl;
import org.nuxeo.apidoc.export.graphs.introspection.NodeImpl;
import org.nuxeo.apidoc.export.graphs.introspection.PositionedNodeImpl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Exporter for Plotly graph format.
 *
 * @since 11.2
 */
public class PlotlyGraphExporter extends JsonGraphExporter implements GraphExporter {

    public PlotlyGraphExporter(EditableGraph graph) {
        super(graph);
    }

    @Override
    public ContentGraphImpl export() {
        ContentGraphImpl cgraph = initContentGraph(graph);

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final ObjectMapper mapper = new ObjectMapper().registerModule(
                new SimpleModule().addAbstractTypeMapping(Node.class, NodeImpl.class)
                                  .addAbstractTypeMapping(Edge.class, EdgeImpl.class)
                                  .addSerializer(Node.class, new NodeSerializer()));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("name", graph.getName());
        values.put("title", graph.getTitle());
        values.put("description", graph.getDescription());
        values.put("type", graph.getType());
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

    public class NodeSerializer<T extends NuxeoArtifact> extends StdSerializer<Node<T>> {

        private static final long serialVersionUID = 1L;

        public NodeSerializer() {
            this(null);
        }

        public NodeSerializer(Class<Node<T>> t) {
            super(t);
        }

        @Override
        public void serialize(Node<T> node, JsonGenerator jgen, SerializerProvider provider)
                throws IOException, JsonProcessingException {
            // "id" : "NXService-org.nuxeo.ecm.core.api.blobholder.BlobHolderAdapterService",
            // "label" : "org.nuxeo.ecm.core.api.blobholder.BlobHolderAdapterService",
            // "weight" : 2,
            // "path" :
            // "/grp:org.nuxeo.ecm.core/org.nuxeo.ecm.core.api/org.nuxeo.ecm.core.api.blobholder.BlobHolderAdapterComponent/Services/org.nuxeo.ecm.core.api.blobholder.BlobHolderAdapterService",
            // "type" : "SERVICE",
            // "category" : "CORE",
            // "color" : "#0000FF",
            // "x" : 188.06268,
            // "y" : -155.23984,
            // "z" : 0.0
            jgen.writeStartObject();
            jgen.writeStringField("id", node.getId());
            jgen.writeStringField("label", node.getLabel());
            jgen.writeStringField("type", node.getType());
            jgen.writeNumberField("weight", node.getWeight());
            Map<String, String> attributes = node.getAttributes();
            if (attributes != null) {
                for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                    jgen.writeStringField(attribute.getKey(), attribute.getValue());
                }
            }
            if (node instanceof PositionedNodeImpl) {
                PositionedNodeImpl<T> pnode = (PositionedNodeImpl<T>) node;
                jgen.writeNumberField("x", pnode.getX());
                jgen.writeNumberField("y", pnode.getY());
                jgen.writeNumberField("z", pnode.getZ());
            }
            jgen.writeEndObject();
            // TODO: potential additional info to the node, as described on originally attached object (ComponentInfo,
            // etc...)
        }
    }

}
