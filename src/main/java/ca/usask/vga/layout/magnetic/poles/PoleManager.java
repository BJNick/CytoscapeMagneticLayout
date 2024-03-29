package ca.usask.vga.layout.magnetic.poles;

import org.cytoscape.application.events.SetCurrentNetworkEvent;
import org.cytoscape.application.events.SetCurrentNetworkListener;
import org.cytoscape.model.*;
import org.cytoscape.model.events.NetworkAddedEvent;
import org.cytoscape.model.events.NetworkAddedListener;
import org.cytoscape.session.events.SessionAboutToBeLoadedEvent;
import org.cytoscape.session.events.SessionAboutToBeLoadedListener;
import org.cytoscape.work.undo.UndoSupport;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Used to store information about the selected poles, as well as
 * calculating the closest pole for every node. Any changes to the
 * list of poles must be submitted through this service.
 */
public class PoleManager implements NetworkAddedListener, SetCurrentNetworkListener, SessionAboutToBeLoadedListener {

    protected final UndoSupport undoSupport;

    protected Map<CyNetwork, List<CyNode>> poleList;
    protected Set<CyNode> poleIsOutwards;

    protected Map<CyNetwork, Map<CyNode, Map<CyNode, Byte>>> cachedPoleDistances;

    // Table column names
    public static final String NAMESPACE = "Magnetic Poles", IS_POLE = "Is pole?", CLOSEST_POLE = "Closest pole",
        IS_OUTWARDS = "Is pole outwards?", DISTANCE_TO_POLE = "Distance to pole",
        EDGE_ASSIGNED_POLE = "Assigned pole", EDGE_TARGET_NODE_POLE = "Target node pole",
        IS_DISCONNECTED = "Not connected", IN_POLE_LIST = "Inward Pole List", OUT_POLE_LIST = "Outward Pole List";

    public static final int UNREACHABLE_NODE = 999;

    public static final String DISCONNECTED_NAME = "none", MULTIPLE_POLES_NAME = "multiple";

    protected boolean tableInitialized;

    protected PoleManagerEdit lastEdit;

    private final List<Runnable> changeListeners; // fired when the pole list is changed
    private final List<Runnable> initializationListeners; // fired when the first pole is added

    /**
     * Creates a new PoleManager service for all networks, with undo support.
     */
    public PoleManager(CyNetworkManager networkManager, UndoSupport undoSupport) {
        this.undoSupport = undoSupport;
        poleList = new HashMap<>();
        poleIsOutwards = new HashSet<>();
        cachedPoleDistances = new HashMap<>();
        changeListeners = new ArrayList<>();
        initializationListeners = new ArrayList<>();
        for (CyNetwork net : networkManager.getNetworkSet()) {
            readPoleListFromTable(net);
        }
    }

    /**
     * Initializes the pole list for the given network,
     * if it doesn't already exist.
     */
    protected void initializePoleList(CyNetwork network) {
        if (!poleList.containsKey(network) && network != null) {
            poleList.put(network, new ArrayList<>());
        }
    }

    /**
     * Reads the pole list from the given Cytoscape network's table,
     * if it contains the columns for the inward and outward pole lists.
     */
    protected void readPoleListFromTable(CyNetwork network) {
        if (network == null) return;
        readPoleListFromColumn(network, IN_POLE_LIST, false);
        readPoleListFromColumn(network, OUT_POLE_LIST, true);
    }

    /**
     * Reads the pole list from the given Cytoscape column
     * by searching for nodes with the same given name.
     */
    protected void readPoleListFromColumn(CyNetwork network, String columnName, boolean isOutwards) {
        CyTable table = network.getDefaultNetworkTable();
        if (table.getColumn(NAMESPACE, columnName) != null) {
            List<String> poleNameList = table.getRow(network.getSUID()).getList(NAMESPACE, columnName, String.class);
            if (poleNameList != null) {
                for (String name : poleNameList) {
                    Iterator<Long> iterator = network.getDefaultNodeTable().getMatchingKeys("name", name, Long.class).iterator();
                    if (!iterator.hasNext()) continue;
                    long matchSUID = iterator.next();
                    CyNode node = network.getNode(matchSUID);
                    if (node != null) {
                        addPole(network, node);
                        setPoleDirection(network, node, isOutwards);
                    }
                }
            }
        }
    }

    /**
     * Returns the list of poles for the given network.
     */
    public List<CyNode> getPoleList(CyNetwork network) {
        if (network == null) {
            System.out.println("Warning: Null network passed to getPoleList");
            return Collections.emptyList();
        }
        initializePoleList(network);
        return poleList.get(network);
    }

    /**
     * Returns the list of poles for the given network, sorted by the given comparator.
     * The returned list is a copy, so the original list is not modified.
     */
    public List<CyNode> getPoleListSorted(CyNetwork network, Comparator<CyNode> comparator) {
        List<CyNode> list = getPoleList(network);
        list = new ArrayList<>(list);
        list.sort(comparator);
        Collections.reverse(list);
        return list;
    }

    /**
     * Returns the list of String pole names for the given network.
     */
    public List<String> getPoleNameList(CyNetwork network) {
        List<CyNode> list = getPoleList(network);
        List<String> newList = new ArrayList<>(list.size());
        for (CyNode n : list) {
            newList.add(getPoleName(network, n));
        }
        return newList;
    }

    /**
     * Returns the list of String pole names for the given network,
     * only including inward poles.
     */
    public List<String> getInPoleNameList(CyNetwork network) {
        List<CyNode> list = getPoleList(network);
        List<String> newList = new ArrayList<>(list.size());
        for (CyNode n : list) {
            if (!isPoleOutwards(network, n))
                newList.add(getPoleName(network, n));
        }
        return newList;
    }

    /**
     * Returns the list of String pole names for the given network,
     * only including outward poles.
     */
    public List<String> getOutPoleNameList(CyNetwork network) {
        List<CyNode> list = getPoleList(network);
        List<String> newList = new ArrayList<>(list.size());
        for (CyNode n : list) {
            if (isPoleOutwards(network, n))
                newList.add(getPoleName(network, n));
        }
        return newList;
    }

    /**
     * Adds the given node to the pole list for the given network.
     * Call {@link #updateTables(CyNetwork)} to update the coloring.
     */
    public void addPole(CyNetwork network, CyNode node) {
        if (!getPoleList(network).contains(node)) {
            boolean isFirstPole = getPoleCount(network) == 0;
            getPoleList(network).add(node);

            // If this was the first pole added, fire the event
            if (isFirstPole) for (var l : initializationListeners) l.run();
        }
    }

    /**
     * Adds all the given nodes to the pole list for the given network.
     * Call {@link #updateTables(CyNetwork)} to update the coloring.
     */
    public void addPole(CyNetwork network, Collection<CyNode> nodes) {
        for (CyNode n : nodes)
            addPole(network, n);
    }

    /**
     * Sets the given pole to be an outward pole.
     */
    protected void makePoleOutwards(CyNetwork network, CyNode pole) {
        poleIsOutwards.add(pole);
    }

    /**
     * Sets the given pole to be an inward pole.
     */
    protected void makePoleInwards(CyNetwork network, CyNode pole) {
        poleIsOutwards.remove(pole);
    }

    /**
     * Returns true if the given pole is an outward pole.
     */
    public boolean isPoleOutwards(CyNetwork network, CyNode pole) {
        return poleIsOutwards.contains(pole);
    }

    /**
     * Sets the direction of the given pole, either inward or outward.
     */
    public void setPoleDirection(CyNetwork network, CyNode pole, boolean isOutwards) {
        if (isOutwards != isPoleOutwards(network, pole))
            invalidateCache(network, pole);
        if (isOutwards)
            makePoleOutwards(network, pole);
        else
            makePoleInwards(network, pole);
    }

    /**
     * Sets the direction of all the given nodes, either inward or outward.
     */
    public void setPoleDirection(CyNetwork network, Collection<CyNode> nodes, boolean isOutwards) {
        for (CyNode n : nodes)
            setPoleDirection(network, n, isOutwards);
    }

    /**
     * Removes the given node from the pole list for the given network.
     * Call {@link #updateTables(CyNetwork)} ()} to update the coloring.
     */
    public void removePole(CyNetwork network, CyNode node) {
        getPoleList(network).remove(node);
    }

    /**
     * Removes all the given nodes from the pole list for the given network.
     * Call {@link #updateTables(CyNetwork)} to update the coloring.
     */
    public void removePole(CyNetwork network, Collection<CyNode> nodes) {
        for (CyNode n : nodes)
            removePole(network, n);
    }

    /**
     * Removes every pole from the given network. It is guaranteed that
     * the pole list for the given network is empty after this call.
     * Call {@link #updateTables(CyNetwork)} to update the coloring.
     */
    public void removeAllPoles(CyNetwork network) {
        getPoleList(network).clear();
    }

    /**
     * Returns true if the given node is a pole.
     */
    public boolean isPole(CyNetwork network, CyNode node) {
        return getPoleList(network).contains(node);
    }

    /**
     * Returns the cached shortest distances for the given network and pole.
     */
    protected Map<CyNode, Byte> getCachedShortestDistances(CyNetwork network, CyNode pole) {
        if (cachedPoleDistances != null && cachedPoleDistances.containsKey(network) && cachedPoleDistances.get(network).containsKey(pole))
            return cachedPoleDistances.get(network).get(pole);
        return null;
    }

    /**
     * Saves the given shortest distances for the given network and pole.
     */
    protected void setCachedShortestDistances(CyNetwork network, CyNode pole, Map<CyNode, Byte> distances) {
        if (cachedPoleDistances == null)
            cachedPoleDistances = new HashMap<>();
        if (!cachedPoleDistances.containsKey(network))
            cachedPoleDistances.put(network, new HashMap<CyNode, Map<CyNode, Byte>>());
        cachedPoleDistances.get(network).put(pole, distances);
    }

    /**
     * Invalidates the cached shortest distances for the given network and pole.
     * Called whenever a pole is removed or its direction is changed. The rest of
     * the cache can still be used.
     */
    protected void invalidateCache(CyNetwork network, CyNode pole) {
        if (cachedPoleDistances != null && cachedPoleDistances.containsKey(network))
            cachedPoleDistances.get(network).remove(pole);
    }

    /**
     * Invalidates the cached shortest distances for the given network.
     * Called whenever the entire pole list is changed. The entire cache will be erased.
     */
    protected void invalidateNetworkCache(CyNetwork network) {
        if (cachedPoleDistances != null && cachedPoleDistances.containsKey(network))
            cachedPoleDistances.get(network).clear();
    }

    /**
     * Returns the shortest distances from the given pole to every other node in the given network.
     * This is an expensive operation, so it is cached.
     * To force a new calculation, {@link #invalidateCache(CyNetwork, CyNode)} must be called.
     */
    protected Map<CyNode, Byte> getShortestDistancesFrom(CyNetwork network, CyNode pole) {
        // Caching
        Map<CyNode, Byte> cache = getCachedShortestDistances(network, pole);
        if (cache != null)
            return cache;

        // RUN BFS
        boolean isOutwards = isPoleOutwards(network, pole);

        Queue<CyNode> toExplore = new ArrayDeque<>();
        toExplore.add(pole);

        Set<CyNode> visited = new HashSet<>();

        Map<CyNode, Byte> shortestDistances = new HashMap<>();
        shortestDistances.put(pole, (byte) 0);

        while (!toExplore.isEmpty()) {

            CyNode n = toExplore.remove();
            visited.add(n);
            byte dist = shortestDistances.get(n);

            CyEdge.Type edgeDirection = isOutwards ? CyEdge.Type.OUTGOING : CyEdge.Type.INCOMING;

            for (CyEdge e : network.getAdjacentEdgeIterable(n, edgeDirection)) {
                CyNode n2 = e.getSource();
                if (isOutwards) n2 = e.getTarget();
                if (visited.contains(n2)) continue;
                if (toExplore.contains(n2)) continue;
                toExplore.add(n2);
                shortestDistances.put(n2, (byte) (dist+1));
            }

        }

        setCachedShortestDistances(network, pole, shortestDistances);
        return shortestDistances;
    }

    /**
     * Returns the distance from the given node to the given pole.
     */
    public int getDistanceToPole(CyNetwork network, CyNode pole, CyNode from) {
        Map<CyNode, Byte> distances = getShortestDistancesFrom(network, pole);
        if (!distances.containsKey(from))
            return UNREACHABLE_NODE; // FAR AWAY
        return distances.get(from);
    }

    /**
     * Returns the closest poles to the given node. If there are multiple poles with the same distance,
     * all of them are returned. If there are no poles within reach, an empty list is returned.
     */
    public Collection<CyNode> getClosestPoles(CyNetwork network, CyNode from) {

        List<CyNode> closestPoles = new ArrayList<>();
        int closestDist = UNREACHABLE_NODE;
        boolean equalDistance = false;

        for (CyNode pole : getPoleList(network)) {

            int dist = getDistanceToPole(network, pole, from);
            if (dist <= closestDist) {
                equalDistance = dist == closestDist;
                if (!equalDistance)
                    closestPoles.clear();
                closestDist = dist;
                closestPoles.add(pole);
            }

        }

        if (closestDist == UNREACHABLE_NODE)
            closestPoles.clear();
        return closestPoles;
    }

    /**
     * Returns the closest pole to the given node. If there are multiple poles with the same distance,
     * the first one is returned. If there are no poles within reach, null is returned.
     */
    public CyNode getClosestPole(CyNetwork network, CyNode from) {
        Collection<CyNode> closest = getClosestPoles(network, from);
        if (closest.size() == 1)
            return closest.iterator().next();
        return null;
    }

    /**
     * Returns the distance from the given node to all of its closest poles.
     * If there are no poles within reach, null is returned.
     */
    @Nullable
    public Integer getClosestPoleDistance(CyNetwork network, CyNode from) {
        Collection<CyNode> closest = getClosestPoles(network, from);
        if (closest.size() > 0)
            return getDistanceToPole(network, closest.iterator().next(), from);
        return null;
    }

    /**
     * Returns true if the given node is disconnected from the network,
     * meaning that there are no poles within reach.
     */
    public boolean isDisconnected(CyNetwork network, CyNode from) {
        if (from == null) return true;
        return getClosestPoles(network, from).size() == 0;
    }

    /**
     * Returns true if the given node is closest to multiple poles.
     */
    public boolean isClosestToMultiple(CyNetwork network, CyNode from) {
        if (from == null) return false;
        return getClosestPoles(network, from).size() > 1;
    }

    /**
     * Returns true if the given node is closest to exactly one pole.
     */
    public boolean isClosestToOne(CyNetwork network, CyNode from) {
        if (from == null) return false;
        return getClosestPoles(network, from).size() == 1;
    }

    /**
     * Returns the poles that are closest to the source and target of the given edge.
     */
    public Collection<CyNode> getAssignedPoles(CyNetwork network, CyEdge edge) {
        Collection<CyNode> p1 = getClosestPoles(network, edge.getSource());
        Collection<CyNode> p2 = getClosestPoles(network, edge.getTarget());
        p1.addAll(p2);
        return new HashSet<>(p1);
    }

    /**
     * Returns the pole that is closest to both the source and target of the given edge.
     * If there are multiple poles, or if there are no poles, null is returned.
     */
    public CyNode getAssignedPole(CyNetwork network, CyEdge edge) {
        Collection<CyNode> closest = getAssignedPoles(network, edge);
        if (isClosestToOne(network, edge))
            return closest.iterator().next();
        return null;
    }

    /**
     * Returns the pole that is closest to the target of the given edge, if it is connected to an inward pole,
     * or the pole that is closest to the source of the given edge, if it is connected to an outward pole.
     * If there are multiple conflicting poles, or if there are no poles, null is returned.
     */
    public CyNode getTargetPole(CyNetwork network, CyEdge edge) {
        CyNode inwardPole = null, outwardPole = null;
        if (isClosestToOne(network, edge.getTarget())) {
            var pole = getClosestPole(network, edge.getTarget());
            if (!isPoleOutwards(network, pole))
                inwardPole = pole;
        }
        if (isClosestToOne(network, edge.getSource())) {
            var pole = getClosestPole(network, edge.getSource());
            if (isPoleOutwards(network, pole))
                outwardPole = pole;
        }
        if ((inwardPole == null) == (outwardPole == null))
            return null;
        if (inwardPole != null)
            return inwardPole;
        else
            return outwardPole;
    }

    /**
     * Returns true if the given edge is disconnected from any poles,
     * meaning that either of its nodes is disconnected.
     */
    public boolean isDisconnected(CyNetwork network, CyEdge edge) {
        if (edge == null) return true;
        return isDisconnected(network, edge.getTarget()) || isDisconnected(network, edge.getSource());
    }

    /**
     * Returns true if the given edge is closest to multiple poles, meaning that either one of its nodes
     * is closest to multiple poles or they are closest to different poles from each other.
     */
    public boolean isClosestToMultiple(CyNetwork network, CyEdge edge) {
        if (edge == null) return false;
        if (isDisconnected(network, edge))
            return false;
        if (isClosestToMultiple(network, edge.getSource()) || isClosestToMultiple(network, edge.getTarget()))
            return true;
        return getClosestPole(network, edge.getSource()) != getClosestPole(network, edge.getTarget());
    }

    /**
     * Returns true if the given edge is closest to exactly one pole,
     * meaning both nodes are closest to the same pole and not disconnected.
     */
    public boolean isClosestToOne(CyNetwork network, CyEdge edge) {
        if (edge == null) return false;
        if (isDisconnected(network, edge))
            return false;
        return !isClosestToMultiple(network, edge);
    }

    /**
     * Returns the Cytoscape name of the given pole.
     */
    protected String getPoleName(CyNetwork network, CyNode pole) {
        return network.getDefaultNodeTable().getRow(pole.getSUID()).get("name", String.class);
    }

    /**
     * Fully updates the pole table columns of the given network.
     * This means that colors, names, and other attributes are updated according
     * to the new pole distances and closest poles.
     * After the update, the table may be saved to the session file and reloaded later.
     */
    public void updateTables(CyNetwork network) {

        // Network pole lists
        CyTable networkTable = network.getDefaultNetworkTable();
        if (networkTable.getColumn(NAMESPACE, IN_POLE_LIST) == null) {
            networkTable.createListColumn(NAMESPACE, IN_POLE_LIST, String.class, false);
        }
        networkTable.getRow(network.getSUID()).set(NAMESPACE, IN_POLE_LIST, getInPoleNameList(network));

        if (networkTable.getColumn(NAMESPACE, OUT_POLE_LIST) == null) {
            networkTable.createListColumn(NAMESPACE, OUT_POLE_LIST, String.class, false);
        }
        networkTable.getRow(network.getSUID()).set(NAMESPACE, OUT_POLE_LIST, getOutPoleNameList(network));

        CyTable nodeTable = network.getDefaultNodeTable();

        // IS_POLE column
        if (nodeTable.getColumn(NAMESPACE, IS_POLE) == null) {
            nodeTable.createColumn(NAMESPACE, IS_POLE, Boolean.class, false);
        }
        for (CyNode node : network.getNodeList()) {
            nodeTable.getRow(node.getSUID()).set(NAMESPACE, IS_POLE, isPole(network, node));
        }

        // IS_OUTWARDS column
        /*if (nodeTable.getColumn(NAMESPACE, IS_OUTWARDS) == null) {
            nodeTable.createColumn(NAMESPACE, IS_OUTWARDS, Boolean.class, false);
        }
        for (CyNode node : network.getNodeList()) {
            nodeTable.getRow(node.getSUID()).set(NAMESPACE, IS_OUTWARDS, isPoleOutwards(network, node));
        }*/

        // CLOSEST_POLE and DISTANCE_TO_POLE columns
        if (nodeTable.getColumn(NAMESPACE, CLOSEST_POLE) == null) {
            nodeTable.createColumn(NAMESPACE, CLOSEST_POLE, String.class, false);
        }
        if (nodeTable.getColumn(NAMESPACE, DISTANCE_TO_POLE) == null) {
            nodeTable.createColumn(NAMESPACE, DISTANCE_TO_POLE, Integer.class, false);
        }
        if (nodeTable.getColumn(NAMESPACE, IS_DISCONNECTED) == null) {
            nodeTable.createColumn(NAMESPACE, IS_DISCONNECTED, Boolean.class, false);
        }

        for (CyNode node : network.getNodeList()) {
            CyNode closestPole = getClosestPole(network, node);
            boolean isDisconnected = isDisconnected(network, node);
            if (closestPole != null) {
                nodeTable.getRow(node.getSUID()).set(NAMESPACE, CLOSEST_POLE, getPoleName(network, closestPole));
            } else {
                if (isDisconnected)
                    nodeTable.getRow(node.getSUID()).set(NAMESPACE, CLOSEST_POLE, DISCONNECTED_NAME);
                else
                    nodeTable.getRow(node.getSUID()).set(NAMESPACE, CLOSEST_POLE, MULTIPLE_POLES_NAME);
            }
            nodeTable.getRow(node.getSUID()).set(NAMESPACE, DISTANCE_TO_POLE, getClosestPoleDistance(network, node));
            nodeTable.getRow(node.getSUID()).set(NAMESPACE, IS_DISCONNECTED, isDisconnected);
        }

        // EDGE_POLE_INFLUENCE
        CyTable edgeTable = network.getDefaultEdgeTable();

        if (edgeTable.getColumn(NAMESPACE, EDGE_ASSIGNED_POLE) == null) {
            edgeTable.createColumn(NAMESPACE, EDGE_ASSIGNED_POLE, String.class, false);
        }
        for (CyEdge edge : network.getEdgeList()) {
            CyNode pole = getAssignedPole(network, edge);
            if (pole != null)
                edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_ASSIGNED_POLE, getPoleName(network, pole));
            else {
                if (isDisconnected(network, edge))
                    edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_ASSIGNED_POLE, DISCONNECTED_NAME);
                else
                    edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_ASSIGNED_POLE, MULTIPLE_POLES_NAME);
            }
        }

        // EDGE_POLE_TARGET

        if (edgeTable.getColumn(NAMESPACE, EDGE_TARGET_NODE_POLE) == null) {
            edgeTable.createColumn(NAMESPACE, EDGE_TARGET_NODE_POLE, String.class, false);
        }
        for (CyEdge edge : network.getEdgeList()) {
            CyNode pole = getTargetPole(network, edge);
            if (pole != null)
                edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_TARGET_NODE_POLE, getPoleName(network, pole));
            else {
                if (isDisconnected(network, edge))
                    edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_TARGET_NODE_POLE, DISCONNECTED_NAME);
                else
                    edgeTable.getRow(edge.getSUID()).set(NAMESPACE, EDGE_TARGET_NODE_POLE, MULTIPLE_POLES_NAME);
            }
        }

        tableInitialized = true;
        for (var l : changeListeners) l.run();
    }

    /**
     * When a new network is added, attempt to import the pole list from the table of the network.
     */
    @Override
    public void handleEvent(NetworkAddedEvent e) {
        if (e.getNetwork() == null || e.getNetwork().getDefaultNetworkTable() == null) {
            return;
        }
        readPoleListFromTable(e.getNetwork());
    }

    /**
     * When the current network is changed, update the pole table columns of the network,
     * to make sure the colors and other attributes reflect the new pole positions.
     */
    @Override
    public void handleEvent(SetCurrentNetworkEvent e) {
        if (e.getNetwork() == null || e.getNetwork().getDefaultNetworkTable() == null) {
            // Network could be set to null
            return;
        }
        if (getPoleList(e.getNetwork()).size() != 0 || tableInitialized)
            updateTables(e.getNetwork());
    }

    /**
     * When the session is about to be loaded, reset the tableInitialized flag,
     * to ensure that the user has to prompt adding poles before any new tables are added.
     */
    @Override
    public void handleEvent(SessionAboutToBeLoadedEvent e) {
        tableInitialized = false;
    }

    /**
     * Begin an edit operation on the pole manager. This may involve adding/removing poles,
     * or changing their directions. Once the operation has finished, call {@link #completeEdit()} to save the changes.
     */
    public void beginEdit(String operation, CyNetwork network) {
        if (lastEdit != null) completeEdit();
        lastEdit = new PoleManagerEdit(operation, this, network);
        lastEdit.setBefore();
    }

    /**
     * Complete an edit operation on the pole manage. This adds the edit to the swing undo stack,
     * which the user can undo with ctrl+z. @see {@link #beginEdit(String, CyNetwork)}
     */
    public void completeEdit() {
        lastEdit.setAfter();
        if (lastEdit.changesPresent())
            undoSupport.postEdit(lastEdit);
        lastEdit = null;
    }

    /**
     * Add a change listener to the pole manager.
     * The listener will be notified whenever the pole table columns are updated.
     */
    public void addChangeListener(Runnable r) {
        changeListeners.add(r);
    }

    /**
     * Remove a change listener from the pole manager.
     */
    public void removeChangeListener(Runnable r) {
        changeListeners.remove(r);
    }

    /**
     * Add an initialization listener to the pole manager.
     * The listener will be notified when the first pole is added.
     */
    public void addInitializationListener(Runnable r) {
        initializationListeners.add(r);
    }

    /**
     * Remove an initialization listener from the pole manager.
     */
    public void removeInitializationListener(Runnable r) {
        initializationListeners.remove(r);
    }

    /**
     * Get the total number of poles in the given network.
     */
    public int getPoleCount(CyNetwork net) {
        if (net == null || poleList == null|| poleList.get(net) == null) return 0;
        return poleList.get(net).size();
    }

}
