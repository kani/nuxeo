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

import java.util.Arrays;
import java.util.List;

import org.nuxeo.apidoc.export.graphs.api.EditableGraph;
import org.nuxeo.apidoc.export.graphs.api.GRAPH_TYPE;
import org.nuxeo.apidoc.export.graphs.api.Graph;
import org.nuxeo.apidoc.export.graphs.introspection.AbstractGraphGeneratorImpl;
import org.nuxeo.apidoc.export.graphs.introspection.ContentGraphImpl;
import org.nuxeo.apidoc.snapshot.DistributionSnapshot;

/**
 * Basic implementation relying on introspection of distribution using jgrapht library.
 *
 * @since 11.2
 */
public class JGraphGeneratorImpl extends AbstractGraphGeneratorImpl {

    public JGraphGeneratorImpl() {
        super();
    }

    @Override
    public List<Graph> getGraphs(DistributionSnapshot distribution) {
        EditableGraph graph = getDefaultGraph(distribution);

        graph.setTitle("DOT Graph");
        graph.setDescription("Complete Graph exported in DOT format");
        graph.setType(GRAPH_TYPE.BASIC.name());
        ContentGraphImpl cgraph = new DOTGraphExporter(graph).export();

        return Arrays.asList(cgraph);
    }

}
