/*
 * (C) Copyright 2020 Nuxeo SA (http://nuxeo.com/) and others.
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
package org.nuxeo.apidoc.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.LinkedHashMap;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.apidoc.api.BundleGroupFlatTree;
import org.nuxeo.apidoc.api.BundleGroupTreeHelper;
import org.nuxeo.apidoc.api.BundleInfo;
import org.nuxeo.apidoc.api.ComponentInfo;
import org.nuxeo.apidoc.api.ExtensionInfo;
import org.nuxeo.apidoc.api.ExtensionPointInfo;
import org.nuxeo.apidoc.api.ServiceInfo;
import org.nuxeo.apidoc.export.graphs.api.Graph;
import org.nuxeo.apidoc.snapshot.DistributionSnapshot;
import org.nuxeo.apidoc.snapshot.SnapshotManager;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * @since 11.2
 */
@RunWith(FeaturesRunner.class)
@Features({ RuntimeSnaphotFeature.class })
public class TestGraphExport {

    public static final boolean UPDATE_LOCAL_FILES = true;

    @Inject
    protected CoreSession session;

    @Inject
    protected SnapshotManager snapshotManager;

    protected String dumpSnapshot(DistributionSnapshot snap) {
        StringBuilder sb = new StringBuilder();

        BundleGroupTreeHelper bgth = new BundleGroupTreeHelper(snap);
        List<BundleGroupFlatTree> tree = bgth.getBundleGroupTree();
        for (BundleGroupFlatTree info : tree) {
            String pad = " ";
            for (int i = 0; i <= info.getLevel(); i++) {
                pad += " ";
            }
            sb.append(pad)
              .append("- ")
              .append(info.getGroup().getName())
              .append("(")
              .append(info.getGroup().getId())
              .append(")");
            sb.append(" *** ");
            sb.append(info.getGroup().getHierarchyPath());
            sb.append("\n");
        }

        List<String> bids = snap.getBundleIds();
        List<String> cids = snap.getComponentIds();
        List<String> sids = snap.getServiceIds();
        List<String> epids = snap.getExtensionPointIds();
        List<String> exids = snap.getContributionIds();

        //List<Graph> graphs = snap.getGraphs();

        for (String bid : bids) {
            sb.append("bundle: ").append(bid);
            BundleInfo bi = snap.getBundle(bid);
            sb.append(" *** ");
            sb.append(bi.getHierarchyPath());
            sb.append(" *** ");
            sb.append(bi.getRequirements());
            sb.append("\n");
        }

        for (String cid : cids) {
            sb.append("component: ").append(cid);
            sb.append(" *** ");
            ComponentInfo ci = snap.getComponent(cid);
            sb.append(ci.getHierarchyPath());
            sb.append(" *** ");
            sb.append(ci.getRequirements());
            sb.append("\n");
        }

        for (String sid : sids) {
            sb.append("service: ").append(sid);
            sb.append(" *** ");
            ServiceInfo si = snap.getService(sid);
            sb.append(si.getHierarchyPath());
            sb.append("\n");
        }

        for (String epid : epids) {
            sb.append("extensionPoint: ").append(epid);
            sb.append(" *** ");
            ExtensionPointInfo epi = snap.getExtensionPoint(epid);
            sb.append(epi.getHierarchyPath());
            sb.append("\n");
        }

        for (String exid : exids) {
            sb.append("contribution: ").append(exid);
            sb.append(" *** ");
            ExtensionInfo exi = snap.getContribution(exid);
            sb.append(exi.getHierarchyPath());
            sb.append("\n");
        }

//        for (Graph graph : graphs) {
//            sb.append("graph: ").append(graph.getName());
//            sb.append(" *** ");
//            sb.append("type: '" + graph.getType() + "', ");
//            sb.append("title: '" + graph.getTitle() + "', ");
//            sb.append("desc: '" + graph.getDescription() + "'");
//            sb.append("\n");
//        }

        return sb.toString().trim();
    }

    @Test
    public void testPersist() throws Exception {
        DistributionSnapshot runtimeSnapshot = snapshotManager.getRuntimeSnapshot();
        String rtDump = dumpSnapshot(runtimeSnapshot);

        DistributionSnapshot persistent = snapshotManager.persistRuntimeSnapshot(session);
        assertNotNull(persistent);
        persistent = snapshotManager.getSnapshot(runtimeSnapshot.getKey(), session);
        assertNotNull(persistent);

        String pDump = dumpSnapshot(persistent);

        // String ref = "ref_dump.txt";
        // checkContentEquals(getReferenceFileContent(ref), rtDump, String.format("File '%s' content differs: ", ref));
        // checkContentEquals(getReferenceFileContent(ref), pDump, String.format("File '%s' content differs: ", ref));
        assertEquals(rtDump, pDump);

        // check runtime graph export equals to persistent *and* to reference graph
        //List<Graph> runtimeGraphs = runtimeSnapshot.getGraphs();
        //List<Graph> persistentGraphs = persistent.getGraphs();
        LinkedHashMap<String, Boolean> refs = new LinkedHashMap<>();
        // boolean indicates if exact math can be done (not possible when layouting)
        refs.put("basic_graph.json", Boolean.FALSE);
        refs.put("jgrapht.dot", Boolean.FALSE);
        refs.put("gephi.json", Boolean.FALSE);
        refs.put("gephi.gexf", Boolean.FALSE);
        refs.put("gephi_flat.json", Boolean.FALSE);
        refs.put("gephi_bundles.json", Boolean.FALSE);
        refs.put("gephi_xp.json", Boolean.FALSE);
        refs.put("gephi_xp_flat.json", Boolean.FALSE);
        // refs.put("gephi_oo.json", Boolean.FALSE);
        // refs.put("gephi_oo_bundles.json", Boolean.FALSE);
        // refs.put("gephi_ff.json", Boolean.FALSE);
    }

}