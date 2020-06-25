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
package org.nuxeo.apidoc.export.graphs.introspection;

import org.nuxeo.apidoc.export.graphs.api.EditableGraph;
import org.nuxeo.apidoc.export.graphs.api.GraphExporter;

/**
 * @since 11.2
 */
public abstract class AbstractGraphExporter implements GraphExporter {

    protected EditableGraph graph;

    public AbstractGraphExporter(EditableGraph graph) {
        this.graph = graph;
    }

    @Override
    public EditableGraph getGraph() {
        return graph;
    }

    public void setGraph(EditableGraph graph) {
        this.graph = graph;
    }

    /**
     * Syncs basic attributes of a content graph.
     */
    protected ContentGraphImpl initContentGraph(EditableGraph graph) {
        ContentGraphImpl cgraph = new ContentGraphImpl(graph.getName());
        cgraph.setTitle(graph.getTitle());
        cgraph.setDescription(graph.getDescription());
        cgraph.setType(graph.getType());
        return cgraph;
    }

}
