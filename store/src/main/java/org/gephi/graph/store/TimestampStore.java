/*
 * Copyright 2012-2013 Gephi Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.gephi.graph.store;

import it.unimi.dsi.fastutil.doubles.Double2IntMap;
import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap;
import it.unimi.dsi.fastutil.doubles.Double2IntRBTreeMap;
import it.unimi.dsi.fastutil.doubles.Double2IntSortedMap;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.gephi.attribute.time.TimestampSet;
import org.gephi.graph.api.DirectedSubgraph;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphView;
import org.gephi.graph.api.Node;
import org.gephi.graph.utils.MapDeepEquals;

/**
 *
 * @author mbastian
 */
public class TimestampStore {

    //Const
    public static final int NULL_INDEX = -1;
    //Lock (optional
    protected final GraphLock lock;
    //Timestamp index managament
    protected final Double2IntMap timestampMap;
    protected final Double2IntSortedMap timestampSortedMap;
    protected final IntSortedSet garbageQueue;
    protected double[] indexMap;
    protected int length;
    //Index
    protected final TimestampIndexImpl mainIndex;
    protected final Map<GraphView, TimestampIndexImpl> viewIndexes;

    public TimestampStore(GraphLock graphLock) {
        lock = graphLock;
        timestampMap = new Double2IntOpenHashMap();
        timestampMap.defaultReturnValue(NULL_INDEX);
        garbageQueue = new IntRBTreeSet();
        mainIndex = new TimestampIndexImpl(this, true);
        viewIndexes = new Object2ObjectOpenHashMap<GraphView, TimestampIndexImpl>();
        timestampSortedMap = new Double2IntRBTreeMap();
        indexMap = new double[0];
    }

    public TimestampIndexImpl getIndex(Graph graph) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            GraphView view = graph.getView();
            if (view.isMainView()) {
                return mainIndex;
            }
            writeLock();
            try {
                TimestampIndexImpl viewIndex = viewIndexes.get(graph.getView());
                if (viewIndex == null) {
                    viewIndex = createViewIndex(graph);
                }
                return viewIndex;
            } finally {
                writeUnlock();
            }
        }
        return null;
    }

    protected TimestampIndexImpl createViewIndex(Graph graph) {
        if (graph.getView().isMainView()) {
            throw new IllegalArgumentException("Can't create a view index for the main view");
        }
        TimestampIndexImpl viewIndex = new TimestampIndexImpl(this, false);
        viewIndexes.put(graph.getView(), viewIndex);

        for (Node node : graph.getNodes()) {
            indexNode((NodeImpl) node);
        }
        for (Edge edge : graph.getEdges()) {
            indexEdge((EdgeImpl) edge);
        }

        return viewIndex;
    }

    protected void deleteViewIndex(Graph graph) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            if (graph.getView().isMainView()) {
                throw new IllegalArgumentException("Can't delete a view index for the main view");
            }
            TimestampIndexImpl index = viewIndexes.remove(graph.getView());
            if (index != null) {
                index.clear();
            }
        }
    }

    //Protected
    public int getTimestampIndex(double timestamp) {
        int index = timestampMap.get(timestamp);
        if (index == NULL_INDEX) {
            index = addTimestamp(timestamp);
        }
        return index;
    }

    public boolean contains(double timestamp) {
        checkDouble(timestamp);

        return timestampMap.containsKey(timestamp);
    }

    public double[] getTimestamps(int[] indices) {
        int indicesLength = indices.length;
        double[] res = new double[indicesLength];
        for (int i = 0; i < indicesLength; i++) {
            int index = indices[i];
            checkIndex(index);
            res[i] = indexMap[i];
        }
        return res;
    }

    public int size() {
        return timestampMap.size();
    }

    public void clear() {
        timestampMap.clear();
        timestampSortedMap.clear();
        garbageQueue.clear();
        indexMap = new double[0];

        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            mainIndex.clear();

            if (!viewIndexes.isEmpty()) {
                for (TimestampIndexImpl index : viewIndexes.values()) {
                    index.clear();
                }
            }
        }
    }

    public void clearEdges() {
        if (!mainIndex.hasNodes()) {
            clear();
        } else {
            mainIndex.clearEdges();

            if (!viewIndexes.isEmpty()) {
                for (TimestampIndexImpl index : viewIndexes.values()) {
                    index.clearEdges();
                }
            }
        }
    }

    public int addElement(double timestamp, ElementImpl element) {
        if (element instanceof NodeImpl) {
            return addNode(timestamp, (NodeImpl) element);
        } else {
            return addEdge(timestamp, (EdgeImpl) element);
        }
    }

    public int removeElement(double timestamp, ElementImpl element) {
        if (element instanceof NodeImpl) {
            return removeNode(timestamp, (NodeImpl) element);
        } else {
            return removeEdge(timestamp, (EdgeImpl) element);
        }
    }

    protected void index(ElementImpl element) {
        if (element instanceof NodeImpl) {
            indexNode((NodeImpl) element);
        } else {
            indexEdge((EdgeImpl) element);
        }
    }

    protected void clear(ElementImpl element) {
        if (element instanceof NodeImpl) {
            clearNode((NodeImpl) element);
        } else {
            clearEdge((EdgeImpl) element);
        }
    }

    //private
    protected void indexNode(NodeImpl node) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            TimestampSet set = node.getTimestampSet();
            if (set != null) {
                int[] ts = set.getTimestamps();
                int tsLength = ts.length;
                for (int i = 0; i < tsLength; i++) {
                    int timestamp = ts[i];
                    mainIndex.addNode(timestamp, node);
                }

                if (!viewIndexes.isEmpty()) {
                    for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                        GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                        DirectedSubgraph graph = graphView.getDirectedGraph();
                        if (graph.contains(node)) {
                            for (int i = 0; i < tsLength; i++) {
                                int timestamp = ts[i];
                                entry.getValue().addNode(timestamp, node);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void indexEdge(EdgeImpl edge) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            TimestampSet set = edge.getTimestampSet();
            if (set != null) {
                int[] ts = set.getTimestamps();
                int tsLength = ts.length;
                for (int i = 0; i < tsLength; i++) {
                    int timestamp = ts[i];
                    mainIndex.addEdge(timestamp, edge);
                }

                if (!viewIndexes.isEmpty()) {
                    for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                        GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                        DirectedSubgraph graph = graphView.getDirectedGraph();
                        if (graph.contains(edge)) {
                            for (int i = 0; i < tsLength; i++) {
                                int timestamp = ts[i];
                                entry.getValue().addEdge(timestamp, edge);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void clearNode(NodeImpl node) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            TimestampSet set = node.getTimestampSet();
            if (set != null) {
                int[] ts = set.getTimestamps();
                int tsLength = ts.length;
                for (int i = 0; i < tsLength; i++) {
                    int timestamp = ts[i];
                    mainIndex.removeNode(timestamp, node);

                    if (mainIndex.timestamps[i] == null) {
                        removeTimestamp(indexMap[i]);
                    }
                }

                if (!viewIndexes.isEmpty()) {
                    for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                        GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                        DirectedSubgraph graph = graphView.getDirectedGraph();
                        if (graph.contains(node)) {
                            for (int i = 0; i < tsLength; i++) {
                                int timestamp = ts[i];
                                entry.getValue().removeNode(timestamp, node);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void clearEdge(EdgeImpl edge) {
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            TimestampSet set = edge.getTimestampSet();
            if (set != null) {
                int[] ts = set.getTimestamps();
                int tsLength = ts.length;
                for (int i = 0; i < tsLength; i++) {
                    int timestamp = ts[i];
                    mainIndex.removeEdge(timestamp, edge);

                    if (mainIndex.timestamps[i] == null) {
                        removeTimestamp(indexMap[i]);
                    }
                }

                if (!viewIndexes.isEmpty()) {
                    for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                        GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                        DirectedSubgraph graph = graphView.getDirectedGraph();
                        if (graph.contains(edge)) {
                            for (int i = 0; i < tsLength; i++) {
                                int timestamp = ts[i];
                                entry.getValue().removeEdge(timestamp, edge);
                            }
                        }
                    }
                }
            }
        }
    }

    protected int addNode(double timestamp, NodeImpl node) {
        int timestampIndex = getTimestampIndex(timestamp);
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            mainIndex.addNode(timestampIndex, node);

            if (!viewIndexes.isEmpty()) {
                for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                    GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                    DirectedSubgraph graph = graphView.getDirectedGraph();
                    if (graph.contains(node)) {
                        entry.getValue().addNode(timestampIndex, node);
                    }
                }
            }
        }

        return timestampIndex;
    }

    protected int addEdge(double timestamp, EdgeImpl edge) {
        int timestampIndex = getTimestampIndex(timestamp);
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            mainIndex.addEdge(timestampIndex, edge);

            if (!viewIndexes.isEmpty()) {
                for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                    GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                    DirectedSubgraph graph = graphView.getDirectedGraph();
                    if (graph.contains(edge)) {
                        entry.getValue().addEdge(timestampIndex, edge);
                    }
                }
            }
        }
        return timestampIndex;
    }

    protected int removeNode(double timestamp, NodeImpl node) {
        int timestampIndex = getTimestampIndex(timestamp);
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            mainIndex.removeNode(timestampIndex, node);

            if (!viewIndexes.isEmpty()) {
                for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                    GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                    DirectedSubgraph graph = graphView.getDirectedGraph();
                    if (graph.contains(node)) {
                        entry.getValue().removeNode(timestampIndex, node);
                    }
                }
            }

            if (mainIndex.timestamps[timestampIndex] == null) {
                removeTimestamp(timestamp);
            }
        }

        return timestampIndex;
    }

    protected int removeEdge(double timestamp, EdgeImpl edge) {
        int timestampIndex = getTimestampIndex(timestamp);
        if (GraphStoreConfiguration.ENABLE_INDEX_TIMESTAMP) {
            mainIndex.removeEdge(timestampIndex, edge);

            if (!viewIndexes.isEmpty()) {
                for (Entry<GraphView, TimestampIndexImpl> entry : viewIndexes.entrySet()) {
                    GraphViewImpl graphView = (GraphViewImpl) entry.getKey();
                    DirectedSubgraph graph = graphView.getDirectedGraph();
                    if (graph.contains(edge)) {
                        entry.getValue().removeEdge(timestampIndex, edge);
                    }
                }
            }

            if (mainIndex.timestamps[timestampIndex] == null) {
                removeTimestamp(timestamp);
            }
        }

        return timestampIndex;
    }

    protected int addTimestamp(final double timestamp) {
        checkDouble(timestamp);

        int id;
        if (!garbageQueue.isEmpty()) {
            id = garbageQueue.firstInt();
            garbageQueue.remove(id);
        } else {
            id = length++;
        }
        timestampMap.put(timestamp, id);
        timestampSortedMap.put(timestamp, id);
        ensureArraySize(id);
        indexMap[id] = timestamp;

        return id;
    }

    protected void removeTimestamp(final double timestamp) {
        checkDouble(timestamp);

        int id = timestampMap.get(timestamp);
        garbageQueue.add(id);
        timestampMap.remove(timestamp);
        timestampSortedMap.remove(timestamp);
        indexMap[id] = Double.NaN;
    }

    protected void ensureArraySize(int index) {
        if (index >= indexMap.length) {
            double[] newArray = new double[index + 1];
            System.arraycopy(indexMap, 0, newArray, 0, indexMap.length);
            indexMap = newArray;
        }
    }

    void checkDouble(double timestamp) {
        if (Double.isInfinite(timestamp) || Double.isNaN(timestamp)) {
            throw new IllegalArgumentException("Timestamp can' be NaN or infinity");
        }
    }

    void checkIndex(int index) {
        if (index < 0 || index >= length) {
            throw new IllegalArgumentException("The timestamp store index is out of bounds");
        }
    }

    @Override
    public int hashCode() {
        int hash = 3;
        for (Double2IntMap.Entry entry : timestampSortedMap.double2IntEntrySet()) {
            hash = 29 * hash + entry.getKey().hashCode();
            hash = 29 * hash + entry.getValue().hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final TimestampStore other = (TimestampStore) obj;
        if (!MapDeepEquals.mapDeepEquals(timestampSortedMap, other.timestampSortedMap)) {
            return false;
        }
        return true;
    }

    private void readLock() {
        if (lock != null) {
            lock.readLock();
        }
    }

    private void readUnlock() {
        if (lock != null) {
            lock.readUnlock();
        }
    }

    private void writeLock() {
        if (lock != null) {
            lock.writeLock();
        }
    }

    private void writeUnlock() {
        if (lock != null) {
            lock.writeUnlock();
        }
    }
}
