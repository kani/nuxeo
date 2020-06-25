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

import org.nuxeo.apidoc.api.NuxeoArtifact;
import org.nuxeo.ecm.core.api.Blob;

/**
 * @since 11.2
 */
public interface Graph extends NuxeoArtifact {

    /** Prefix for {@link #getId}. */
    String ARTIFACT_PREFIX = "graph:";

    String ARTIFACT_TYPE = "graph";

    String TYPE_NAME = "NXGraph";

    String PROP_GRAPH_TYPE = "nxgraph:type";

    String getName();

    String getTitle();

    String getDescription();

    String getType();

    Blob getBlob();

}
