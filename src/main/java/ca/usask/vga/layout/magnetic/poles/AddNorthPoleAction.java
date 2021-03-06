package ca.usask.vga.layout.magnetic.poles;

import ca.usask.vga.layout.magnetic.ActionOnSelected;
import org.cytoscape.model.CyNetwork;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class AddNorthPoleAction  extends ActionOnSelected {

    private final PoleManager poleManager;

    private static final String TASK_DESCRIPTION = "Add new outward poles";

    public AddNorthPoleAction(PoleManager poleManager) {

        super(TASK_DESCRIPTION);

        this.poleManager = poleManager;

        ImageIcon icon = new ImageIcon(getClass().getResource("/icons/add_pole_N_icon.png"));
        putValue(LARGE_ICON_KEY, icon);

        putValue(SHORT_DESCRIPTION, "Make new North (Outward) poles from selected nodes");

        setToolbarGravity(16.11f);

        this.useToggleButton = false;
        this.inToolBar = true;
        this.insertToolbarSeparatorBefore = true;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (isSelectionActive()) {
            CyNetwork network = getNetwork();
            poleManager.beginEdit(TASK_DESCRIPTION, network);

            poleManager.addPole(network, getSelectedNodes());
            poleManager.setPoleDirection(network, getSelectedNodes(), true);
            poleManager.updateTables(network);

            poleManager.completeEdit();
        }
    }

}