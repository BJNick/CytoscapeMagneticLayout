package ca.usask.vga.layout.magnetic;

import ca.usask.vga.layout.magnetic.highlight.ChangeHopDistanceAction;
import ca.usask.vga.layout.magnetic.highlight.CopyHighlightedAction;
import ca.usask.vga.layout.magnetic.highlight.EdgeHighlighting;
import ca.usask.vga.layout.magnetic.highlight.ToggleHighlightAction;
import ca.usask.vga.layout.magnetic.poles.AddNorthPoleAction;
import ca.usask.vga.layout.magnetic.poles.AddSouthPoleAction;
import ca.usask.vga.layout.magnetic.poles.PoleManager;
import ca.usask.vga.layout.magnetic.poles.RemovePoleAction;
import org.cytoscape.application.events.SetCurrentNetworkListener;
import org.cytoscape.application.swing.CyAction;
import org.cytoscape.model.CyNetworkFactory;
import org.cytoscape.model.CyNetworkManager;
import org.cytoscape.model.CyNetworkTableManager;
import org.cytoscape.model.CyTableManager;
import org.cytoscape.model.events.NetworkAddedListener;
import org.cytoscape.model.events.SelectedNodesAndEdgesListener;
import org.cytoscape.model.subnetwork.CyRootNetworkManager;
import org.cytoscape.service.util.AbstractCyActivator;
import org.cytoscape.session.CyNetworkNaming;
import org.cytoscape.session.events.SessionAboutToBeLoadedListener;
import org.cytoscape.view.layout.CyLayoutAlgorithm;
import org.cytoscape.view.layout.CyLayoutAlgorithmManager;
import org.cytoscape.view.model.CyNetworkViewFactory;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.view.vizmap.VisualMappingManager;
import org.cytoscape.view.vizmap.VisualStyleFactory;
import org.cytoscape.work.TaskManager;
import org.cytoscape.work.TunableSetter;
import org.cytoscape.work.undo.UndoSupport;
import org.osgi.framework.BundleContext;

import java.util.Properties;

import static org.cytoscape.work.ServiceProperties.*;

public class CyActivator extends AbstractCyActivator {
	public CyActivator() {
		super();
	}

	public void start(BundleContext bc) {
		
		UndoSupport undo = getService(bc,UndoSupport.class);

		// App preferences
		AppPreferences preferences = new AppPreferences("magnetic-layout");
    	Properties propsReaderServiceProps = new Properties();
    	propsReaderServiceProps.setProperty("cyPropertyName", "magnetic-layout.props");
    	registerAllServices(bc, preferences, propsReaderServiceProps);

		// Editor Edge Highlighting
		final EdgeHighlighting edgeHighlighting = new EdgeHighlighting(new EdgeHighlighting.CyAccess(
				getService(bc, CyNetworkFactory.class),
				getService(bc, CyNetworkManager.class),
				getService(bc, CyNetworkViewFactory.class),
				getService(bc, CyNetworkViewManager.class),
				getService(bc, CyNetworkNaming.class),
				getService(bc, VisualMappingManager.class),
				getService(bc, CyRootNetworkManager.class)
		), preferences);
		registerService(bc, edgeHighlighting, SelectedNodesAndEdgesListener.class);

		registerService(bc, new ToggleHighlightAction(edgeHighlighting), CyAction.class, new Properties());
		registerService(bc, new CopyHighlightedAction(edgeHighlighting), CyAction.class, new Properties());
		registerService(bc, new ChangeHopDistanceAction(edgeHighlighting), CyAction.class, new Properties());

		// Simple Magnetic Layout
		SimpleMagneticLayout simpleMagneticLayout = new SimpleMagneticLayout(undo);

        Properties sLayoutProps = new Properties();
        sLayoutProps.setProperty(PREFERRED_MENU,"Layout.Magnetic Layouts");
        sLayoutProps.setProperty("preferredTaskManager","menu");  // Purpose: unknown
        sLayoutProps.setProperty(TITLE,simpleMagneticLayout.toString());
        sLayoutProps.setProperty(MENU_GRAVITY,"10.51");
		registerService(bc,simpleMagneticLayout,CyLayoutAlgorithm.class, sLayoutProps);

		// Magnetic Poles
		PoleManager poleManager = new PoleManager(getService(bc, CyNetworkManager.class));
		registerService(bc, poleManager, PoleManager.class);
		registerService(bc, poleManager, NetworkAddedListener.class);
		registerService(bc, poleManager, SetCurrentNetworkListener.class);
		registerService(bc, poleManager, SessionAboutToBeLoadedListener.class);

		AddNorthPoleAction addNPole = new AddNorthPoleAction(poleManager);
		registerService(bc, addNPole, CyAction.class);
		registerService(bc, addNPole, SelectedNodesAndEdgesListener.class);

		AddSouthPoleAction addSPole = new AddSouthPoleAction(poleManager);
		registerService(bc, addSPole, CyAction.class);
		registerService(bc, addSPole, SelectedNodesAndEdgesListener.class);

		RemovePoleAction removePole = new RemovePoleAction(poleManager);
		registerService(bc, removePole, CyAction.class);
		registerService(bc, removePole, SelectedNodesAndEdgesListener.class);

		// Pole Magnetic Layout
		PoleMagneticLayout poleMagneticLayout = new PoleMagneticLayout(poleManager, undo);

		Properties pLayoutProps = new Properties();
		pLayoutProps.setProperty(PREFERRED_MENU,"Layout.Magnetic Layouts");
		pLayoutProps.setProperty("preferredTaskManager","menu");  // Purpose: unknown
		pLayoutProps.setProperty(TITLE,poleMagneticLayout.toString());
		pLayoutProps.setProperty(MENU_GRAVITY,"10.52");
		registerService(bc,poleMagneticLayout,CyLayoutAlgorithm.class, pLayoutProps);


	}
}

