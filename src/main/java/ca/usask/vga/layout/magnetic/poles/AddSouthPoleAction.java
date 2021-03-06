package ca.usask.vga.layout.magnetic.poles;

import ca.usask.vga.layout.magnetic.ActionOnSelected;
import org.cytoscape.model.CyNetwork;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class AddSouthPoleAction extends ActionOnSelected {

    private final PoleManager poleManager;

    private static final String TASK_DESCRIPTION = "Add new inward poles";

    public AddSouthPoleAction(PoleManager poleManager) {

        super(TASK_DESCRIPTION);

        this.poleManager = poleManager;

        ImageIcon icon = new ImageIcon(getClass().getResource("/icons/add_pole_S_icon.png"));
        putValue(LARGE_ICON_KEY, icon);

        putValue(SHORT_DESCRIPTION, "Make new South (Inward) poles from selected nodes");

        setToolbarGravity(16.12f);

        this.useToggleButton = false;
        this.inToolBar = true;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (isSelectionActive()) {
            CyNetwork network = getNetwork();
            poleManager.beginEdit(TASK_DESCRIPTION, network);

            poleManager.addPole(network, getSelectedNodes());
            poleManager.setPoleDirection(network, getSelectedNodes(), false);
            poleManager.updateTables(network);

            poleManager.completeEdit();
        }
    }

}
