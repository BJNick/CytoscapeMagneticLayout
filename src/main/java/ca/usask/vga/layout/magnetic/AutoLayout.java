package ca.usask.vga.layout.magnetic;

import org.cytoscape.view.layout.*;
import org.cytoscape.work.AbstractTask;
import org.cytoscape.work.TaskMonitor;
import prefuse.util.force.ForceItem;
import prefuse.util.force.ForceSimulator;
import prefuse.util.force.Spring;
import prefuse.util.force.StateMonitor;

import java.util.*;


/**
 * AutoLayout attempts to find good parameters for the force simulator by running multiple trials
 * on a subset of the network.
 * @see AutoLayoutQuality
 * @see AutoLayoutVariables
 */
public class AutoLayout extends AbstractTask {

    public final static int TRIAL_ITERATIONS = 5;

    private final PoleMagneticLayoutTask layout;
    private final LayoutPartition part;
    private final StateMonitor monitor;

    public AutoLayout(PoleMagneticLayoutTask layout, LayoutPartition part, StateMonitor monitor) {
        this.layout = layout;
        this.part = part;
        this.monitor = monitor;
    }

    public PoleMagneticLayoutContext getContext() {
        return (PoleMagneticLayoutContext) layout.context;
    }

    @Override
    public void run(TaskMonitor taskMonitor) {

        // Single threaded; only works on one partition at a time.
        taskMonitor.showMessage(TaskMonitor.Level.INFO, "Finding parameters...");

        AutoLayoutQuality quality = new AutoLayoutQuality(getContext());
        AutoLayoutVariables auto = new AutoLayoutVariables(getContext());
        Iterable<int[]> combinations = auto.getAllCombinations();

        int[] bestComb = new int[auto.getVarCount()];
        auto.setAll(bestComb);
        float maxScore = runTrial(quality);

        float newScore;

        int progress = 1, totalCombinations = auto.getCombinationCount();

        for (int[] combination : combinations) {

            if (monitor.isCancelled()) return;

            taskMonitor.setProgress((float) progress / totalCombinations);
            progress++;

            auto.setAll(combination);
            newScore = runTrial(quality);
            //taskMonitor.showMessage(TaskMonitor.Level.INFO, "Comb: " + Arrays.toString(combination) + " Score: " + newScore);

            if (newScore > maxScore) {
                maxScore = newScore;
                bestComb = combination;
            }

        }

        taskMonitor.showMessage(TaskMonitor.Level.INFO, "Chosen combination: " + Arrays.toString(bestComb) + " Score: " + maxScore);

        taskMonitor.showMessage(TaskMonitor.Level.INFO, quality.qualityToString(layout.getErrorCalculator(part)));
        taskMonitor.showMessage(TaskMonitor.Level.INFO, auto.combinationToString(bestComb));

        taskMonitor.showMessage(TaskMonitor.Level.INFO, "Applying found parameters...");

        auto.setAll(bestComb);
    }

    public float runTrial(AutoLayoutQuality quality) {
        runNewSimulation(TRIAL_ITERATIONS);
        return quality.calculateScore(layout.getErrorCalculator(part));
    }

    protected ForceSimulator runNewSimulation(int iterations) {

        layout.clearMaps();

        // Calculate our edge weights
        part.calculateEdgeWeights();

        ForceSimulator m_fsim = new ForceSimulator(layout.integrator.getNewIntegrator(layout.monitor), layout.monitor);
        layout.addSimulatorForces(m_fsim, part);

        List<LayoutNode> nodeList = part.getNodeList();
        List<LayoutEdge> edgeList = part.getEdgeList();

        if (layout.context.isDeterministic) {
            Collections.sort(nodeList);
            Collections.sort(edgeList);
        }

        Map<LayoutNode, ForceItem> forceItems = new HashMap<>();

        // initialize nodes
        for (LayoutNode ln : nodeList) {

            ForceItem fitem = forceItems.get(ln);

            if (fitem == null) {
                fitem = new ForceItem();
                forceItems.put(ln, fitem);
            }

            fitem.mass = layout.getMassValue(ln);
            fitem.location[0] = (float) ln.getX();
            fitem.location[1] = (float) ln.getY();
            m_fsim.addItem(fitem);

            layout.mapForceItem(ln, fitem);
        }

        // Sample the graph if larger than 500
        if (nodeList.size() > 500)
            for (LayoutNode ln : nodeList) {
                ForceItem fitem = forceItems.get(ln);
                if (fitem != null && Math.random() > 500f / nodeList.size() && !layout.poleClassifier.isPole(fitem)) {
                    m_fsim.removeItem(fitem);
                    forceItems.remove(ln);
                }
            }

        // initialize edges
        for (LayoutEdge e : edgeList) {

            LayoutNode n1 = e.getSource();
            ForceItem f1 = forceItems.get(n1);
            LayoutNode n2 = e.getTarget();
            ForceItem f2 = forceItems.get(n2);

            if (f1 == null || f2 == null)
                continue;

            Spring s = m_fsim.addSpring(f1, f2, layout.getSpringCoefficient(e), layout.getSpringLength(e));
            layout.mapSpring(e, s);
        }

        // perform layout

        long timestep = 1000L;

        for (int i = 0; i < iterations; i++) {

            if (monitor.isCancelled()) return m_fsim;

            timestep *= (1.0 - i / (double) iterations);
            long step = timestep + 50;
            m_fsim.runSimulator(step);

        }

        // Matching full time steps (mixed results)
        /* long timestep = 1000L;
        long cumulative_step = 0;
        int j = 1;
        System.out.println("LOOP");

        for (int i = 0; i < getContext().numIterations; i++) {

            timestep *= (1.0 - i / (double) getContext().numIterations);
            long step = timestep + 50;
            cumulative_step += step;
            //System.out.println("step " + i + "  " + j / iterations + "  vs  " + i / getContext().numIterations);

            if ((float) j / iterations <  (float) i / getContext().numIterations) {
                System.out.println("Big step " + j + " " + cumulative_step);
                j++;
                m_fsim.runSimulator(cumulative_step);
                cumulative_step = 0;
            }

        }

        if (cumulative_step > 0) {
            System.out.println("Big step " + j + " " + cumulative_step);
            m_fsim.runSimulator(cumulative_step);
        }*/

        return m_fsim;
    }
}
