package ca.usask.vga.layout.magnetic.highlight;

import org.cytoscape.model.*;
import org.cytoscape.model.subnetwork.CyRootNetwork;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.VisualProperty;
import org.cytoscape.view.presentation.property.BasicVisualLexicon;
import org.cytoscape.view.vizmap.VisualStyle;

import java.util.*;

/**
 * Used to create a subnetwork of nodes/edges, then copy
 * all the visual style and pole parameters to the new graph.
 */
public class CreateSubnetworkTask {

    private final NetworkCyAccess cy;

    public CreateSubnetworkTask(NetworkCyAccess cy) {
        this.cy = cy;
    }

    public CyNetwork copyCurrentVisible() {
        Set<CyNode> nodes = new HashSet<>();
        Set<CyEdge> edges = new HashSet<>();

        for (CyNode node : cy.am.getCurrentNetwork().getNodeList()) {
            if (cy.am.getCurrentNetworkView().getNodeView(node).getVisualProperty(BasicVisualLexicon.NODE_VISIBLE)) {
                nodes.add(node);
            }
        }
        for (CyEdge edge : cy.am.getCurrentNetwork().getEdgeList()) {
            if (nodes.contains(edge.getSource()) && nodes.contains(edge.getTarget())) {
                edges.add(edge);
            }
        }

        return copyNetwork(cy.am.getCurrentNetwork(), nodes, edges);
    }

    public CyNetwork copyCurrent() {
        var net = cy.am.getCurrentNetwork();
        return copyNetwork(net, net.getNodeList(), net.getEdgeList());
    }

    public CyNetwork copyAndCutCommonEdges() {
        Set<CyNode> nodes = new HashSet<>();
        Set<CyEdge> edges = new HashSet<>();

        for (CyNode node : cy.am.getCurrentNetwork().getNodeList()) {
            if (cy.am.getCurrentNetworkView().getNodeView(node).getVisualProperty(BasicVisualLexicon.NODE_VISIBLE)) {
                nodes.add(node);
            }
        }
        for (CyEdge edge : cy.am.getCurrentNetwork().getEdgeList()) {
            if (nodes.contains(edge.getSource()) && nodes.contains(edge.getTarget())) {
                if (!cy.pm.isClosestToMultiple(cy.am.getCurrentNetwork(), edge)) {
                    edges.add(edge);
                }
            }
        }

        return copyNetwork(cy.am.getCurrentNetwork(), nodes, edges);
    }

    public CyNetwork copyNetwork(CyNetwork supernet, Collection<CyNode> selectedNodes, Collection<CyEdge> selectedEdges) {

        CyRootNetwork root = cy.rnm.getRootNetwork(supernet);

        CyNetwork net = root.addSubNetwork(selectedNodes, selectedEdges);

        copyNetworkTable(supernet, net);
        net.getDefaultNetworkTable().getRow(net.getSUID()).set("name", cy.cnn.getSuggestedSubnetworkTitle(root));

        cy.nm.addNetwork(net);

        CyNetworkView view = cy.vf.createNetworkView(net);
        cy.vm.addNetworkView(view);

        Collection<CyNetworkView> oldViewList = cy.vm.getNetworkViews(supernet);

        if (!oldViewList.isEmpty()) {

            CyNetworkView oldView = oldViewList.iterator().next();

            VisualStyle style = cy.vmm.getVisualStyle(oldView);

            // TODO: Clone style instead of using the same one

            cy.vmm.addVisualStyle(style);
            cy.vmm.setVisualStyle(style, view);
            style.apply(view);

            // Copy node positions and zoom

            for (CyNode n : selectedNodes) {
                view.getNodeView(n).setVisualProperty(BasicVisualLexicon.NODE_X_LOCATION, oldView.getNodeView(n).getVisualProperty(BasicVisualLexicon.NODE_X_LOCATION));
                view.getNodeView(n).setVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION, oldView.getNodeView(n).getVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION));
            }

            VisualProperty[] toCopy = new VisualProperty[] {
                    BasicVisualLexicon.NETWORK_CENTER_X_LOCATION,
                    BasicVisualLexicon.NETWORK_CENTER_Y_LOCATION,
                    BasicVisualLexicon.NETWORK_HEIGHT,
                    BasicVisualLexicon.NETWORK_WIDTH,
                    BasicVisualLexicon.NETWORK_SCALE_FACTOR,
                    BasicVisualLexicon.NETWORK_CENTER_Z_LOCATION,
                    BasicVisualLexicon.NETWORK_SIZE};
            for (VisualProperty vp : toCopy)
                view.setVisualProperty(vp, oldView.getVisualProperty(vp));

        }

        for (var n : cy.pm.getPoleList(supernet)) {
            if (selectedNodes.contains(n)) {
                cy.pm.addPole(net, n);
                cy.pm.setPoleDirection(net, n, cy.pm.isPoleOutwards(supernet, n));
            }
        }

        cy.pm.updateTables(net);
        return net;
    }

    public void copyNetworkTable(CyNetwork supernet, CyNetwork net) {
        var table1 = supernet.getDefaultNetworkTable();
        var table2 = net.getDefaultNetworkTable();
        table1.getColumns().forEach(c -> {
            // Copy only things that aren't causing errors
            try {
                if (table2.getColumn(c.getName()) == null) {
                    table2.createColumn(c.getName(), c.getType(), c.isImmutable());
                }
                table2.getRow(net.getSUID()).set(c.getName(),
                        table1.getRow(supernet.getSUID()).get(c.getName(), c.getType()));
            } catch (Exception ignored) {}
        });
    }
}
