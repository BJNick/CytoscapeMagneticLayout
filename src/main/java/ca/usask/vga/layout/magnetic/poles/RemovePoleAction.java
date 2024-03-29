package ca.usask.vga.layout.magnetic.poles;

import ca.usask.vga.layout.magnetic.ActionOnSelected;
import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNode;
import org.cytoscape.util.swing.IconManager;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.View;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.Collection;
import java.util.Properties;

import static org.cytoscape.work.ServiceProperties.*;

/**
 * An action that removes selected poles, and updates the screen to
 * change the displayed colors.
 * Present in the toolbar and the context menu.
 */
public class RemovePoleAction  extends ActionOnSelected {

    private final PoleManager poleManager;

    private static final String TASK_DESCRIPTION = "Remove selected poles";

    public RemovePoleAction(CyApplicationManager am, IconManager im, PoleManager poleManager) {

        super(am, im, TASK_DESCRIPTION);

        this.poleManager = poleManager;

        ImageIcon icon = new ImageIcon(getClass().getResource("/icons/remove_pole_icon.png"));
        im.addIcon(ICON_NAMESPACE+TASK_DESCRIPTION, icon);

        ImageIcon icon2 = new ImageIcon(getClass().getResource("/icons/remove_pole_icon_16.png"));
        im.addIcon(ICON_NAMESPACE+TASK_DESCRIPTION+SMALL, icon2);

        putValue(LARGE_ICON_KEY, icon);
        putValue(SHORT_DESCRIPTION, "Remove selected poles");

        setToolbarGravity(16.2f);

        this.useToggleButton = false;
        this.inToolBar = true;
        this.insertSeparatorAfter = true;
    }

    @Override
    public void runTask(CyNetwork network, Collection<CyNode> selectedNodes) {
        poleManager.beginEdit(TASK_DESCRIPTION, network);

        poleManager.removePole(network, selectedNodes);
        poleManager.updateTables(network);

        poleManager.completeEdit();
    }

    @Override
    public boolean isReady(CyNetwork network, Collection<CyNode> selectedNodes) {
        if (selectedNodes == null) return false;
        if (selectedNodes.size() > 10) return true;
        for (var n : selectedNodes) {
            if (poleManager.isPole(network, n)) return true;
        }
        return false;
    }

    @Override
    public Properties getNetworkTaskProperties() {
        Properties props = new Properties();
        props.setProperty(IN_MENU_BAR, "false");
        props.setProperty(PREFERRED_MENU, POLE_SUBMENU);
        props.setProperty(TITLE, "Remove selected poles");
        props.setProperty(MENU_GRAVITY, "99");
        props.setProperty(SMALL_ICON_ID, ICON_NAMESPACE+TASK_DESCRIPTION);
        return props;
    }

    @Override
    public Properties getTableTaskProperties() {
        var props = getNetworkTaskProperties();
        props.setProperty(TITLE, "Remove selected pole");
        props.setProperty(SMALL_ICON_ID, ICON_NAMESPACE+TASK_DESCRIPTION+SMALL);
        return props;
    }

}
