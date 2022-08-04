package ca.usask.vga.layout.magnetic;

import ca.usask.vga.layout.magnetic.force.HierarchyForce;
import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.work.TaskManager;

import java.util.HashSet;

public class SoftwareLayout {

    private final PoleMagneticLayout pml;
    private final TaskManager tm;
    private final CyApplicationManager am;

    private int maxRings;
    private float pinRadius;

    public SoftwareLayout(PoleMagneticLayout pml, TaskManager tm, CyApplicationManager am) {
        this.pml = pml;
        this.tm = tm;
        this.am = am;
    }

    public void runLayout() {

        var context = getContext();

        var netView = am.getCurrentNetworkView();

        var task = pml.createTaskIterator(netView, context, new HashSet<>(netView.getNodeViews()), null);

        tm.execute(task);

    }

    protected PoleMagneticLayoutContext getContext() {

        var c = new PoleMagneticLayoutContext();

        c.repulsionCoefficient = 1;
        c.defaultSpringLength = 50;
        c.defaultSpringCoefficient = 1e-5;

        c.useCentralGravity = true;
        c.centralGravity = 1e-6;

        if (pinRadius == 0) {
            c.pinPoles = false;
        } else {
            c.pinPoles = true;
            c.useCirclePin = true;
            c.pinRadius = pinRadius;
        }

        c.usePoleAttraction = true;
        c.poleGravity = 1e-5;

        if (maxRings == 0) {
            c.useHierarchyForce = false;
        } else {
            c.useHierarchyForce = true;
            c.hierarchyType = HierarchyForce.Type.SINE_FUNCTION;
            c.ringRadius = pinRadius / (maxRings + 1);
            c.hierarchyForce = 1e0;
        }

        c.magnetEnabled = true;
        c.magneticAlpha = 0;
        c.magneticBeta = 1;
        c.useMagneticPoles = true;
        c.magneticFieldStrength = 1e-4;

        c.numIterations = 50;
        c.useAnimation = true;

        c.useAutoLayout = false;

        return c;
    }

    public void setPinRadius(float newValue) {
        this.pinRadius = newValue;
    }

    public void setMaxRings(int newValue) {
        this.maxRings = newValue;
    }

}