package overflowdb;

import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import overflowdb.tp3.Converters;
import overflowdb.util.ArrayOffsetIterator;
import overflowdb.util.MultiIterator2;
import overflowdb.util.PackedIntArray;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

/**
 * Node that stores adjacent Nodes directly, rather than via edges.
 * Motivation: in many graph use cases, edges don't hold any properties and thus accounts for more memory and
 * traversal time than necessary
 */
public abstract class OdbNode implements Vertex, Node {

  public final NodeRef ref;

  /**
   * holds refs to all adjacent nodes (a.k.a. dummy edges) and the edge properties
   */
  private Object[] adjacentNodesWithProperties = new Object[0];

  /* store the start offset and length into the above `adjacentNodesWithProperties` array in an interleaved manner,
   * i.e. each outgoing edge type has two entries in this array. */
  private PackedIntArray edgeOffsets;

  /**
   * Flag that helps us save time when serializing, both when overflowing to disk and when storing
   * the graph on close.
   * `true`  when node is first created, or is modified (property or edges)
   * `false` when node is freshly serialized to disk or deserialized from disk
   */
  private boolean dirty;

  protected OdbNode(NodeRef ref) {
    this.ref = ref;

    ref.setNode(this);
    if (ref.graph != null) {
      ref.graph.referenceManager.applyBackpressureMaybe();
    }

    edgeOffsets = PackedIntArray.create(layoutInformation().numberOfDifferentAdjacentTypes() * 2);
  }

  public abstract NodeLayoutInformation layoutInformation();

  protected <V> Iterator<VertexProperty<V>> specificProperties(String key) {
    final Object value = specificProperty2(key);
    if (value != null) return IteratorUtils.of(new OdbNodeProperty(this, key, value));
    else return Collections.emptyIterator();
  }

  // TODO drop suffix `2` after tinkerpop interface is gone
  protected abstract Object specificProperty2(String key);

  public Object[] getAdjacentNodesWithProperties() {
    return adjacentNodesWithProperties;
  }

  public void setAdjacentNodesWithProperties(Object[] adjacentNodesWithProperties) {
    this.adjacentNodesWithProperties = adjacentNodesWithProperties;
  }

  public int[] getEdgeOffsets() {
    return edgeOffsets.toIntArray();
  }

  public PackedIntArray getEdgeOffsetsPackedArray() {
    return edgeOffsets;
  }


  public void setEdgeOffsets(int[] edgeOffsets) {
    this.edgeOffsets = PackedIntArray.of(edgeOffsets);
  }

  public abstract Map<String, Object> valueMap();

  @Override
  public Graph graph() {
    return ref.graph;
  }

  @Override
  public OdbGraph graph2() {
    return ref.graph;
  }

  @Override
  public Object id() {
    return ref.id;
  }

  public long id2() {
    return ref.id;
  }

  @Override
  public String label() {
    return ref.label();
  }

  @Override
  public Set<String> keys() {
    return layoutInformation().propertyKeys();
  }

  @Override
  public <V> VertexProperty<V> property(String key) {
    return specificProperty(key);
  }

  /* You can override this default implementation in concrete specialised instances for performance
   * if you like, since technically the Iterator isn't necessary.
   * This default implementation works fine though. */
  protected <V> VertexProperty<V> specificProperty(String key) {
    Iterator<VertexProperty<V>> iter = specificProperties(key);
    if (iter.hasNext()) {
      return iter.next();
    } else {
      return VertexProperty.empty();
    }
  }

  @Override
  public <V> Iterator<VertexProperty<V>> properties(String... propertyKeys) {
    if (propertyKeys.length == 0) { // return all properties
      return (Iterator) layoutInformation().propertyKeys().stream().flatMap(key ->
          StreamSupport.stream(Spliterators.spliteratorUnknownSize(
              specificProperties(key), Spliterator.ORDERED), false)
      ).iterator();
    } else if (propertyKeys.length == 1) { // treating as special case for performance
      return specificProperties(propertyKeys[0]);
    } else {
      return (Iterator) Arrays.stream(propertyKeys).flatMap(key ->
          StreamSupport.stream(Spliterators.spliteratorUnknownSize(
              specificProperties(key), Spliterator.ORDERED), false)
      ).iterator();
    }
  }

  @Override
  public Map<String, Object> propertyMap() {
    final Set<String> propertyKeys = layoutInformation().propertyKeys();
    final Map<String, Object> results = new HashMap<>(propertyKeys.size());

    for (String propertyKey : propertyKeys) {
      final Object value = property2(propertyKey);
      if (value != null) results.put(propertyKey, value);
    }

    return results;
  }

  @Override
  public <P> P property2(String propertyKey) {
    return (P) specificProperty2(propertyKey);
  }

  @Override
  public <V> VertexProperty<V> property(VertexProperty.Cardinality cardinality, String key, V value, Object... keyValues) {
    ElementHelper.legalPropertyKeyValueArray(keyValues);
    ElementHelper.validateProperty(key, value);
    final VertexProperty<V> vp = updateSpecificProperty(cardinality, key, value);
    ref.graph.indexManager.putIfIndexed(key, value, ref);
    /* marking as dirty *after* we updated - if node gets serialized before we finish, it'll be marked as dirty */
    this.markAsDirty();
    return vp;
  }

  @Override
  public <P> void setProperty(String key, P value) {
    this.property(VertexProperty.Cardinality.single, key, value);
  }

  protected abstract <V> VertexProperty<V> updateSpecificProperty(
      VertexProperty.Cardinality cardinality, String key, V value);

  protected abstract void removeSpecificProperty(String key);

  @Override
  public void remove() {
    OdbGraph graph = ref.graph;
    final List<Edge> edges = new ArrayList<>();
    bothE().forEachRemaining(edges::add);
    for (Edge edge : edges) {
      if (!((OdbEdge) edge).isRemoved()) {
        edge.remove();
      }
    }
    graph.indexManager.removeElement(ref);
    graph.nodes.remove(ref.id);
    graph.nodesByLabel.get(label()).remove(ref);

    graph.storage.removeNode(ref.id);
    /* marking as dirty *after* we updated - if node gets serialized before we finish, it'll be marked as dirty */
    this.markAsDirty();
  }

  public void markAsDirty() {
    this.dirty = true;
  }

  public void markAsClean() {
    this.dirty = false;
  }

  public <V> Iterator<Property<V>> getEdgeProperties(Direction direction,
                                                     OdbEdge edge,
                                                     int blockOffset,
                                                     String... keys) {
    List<Property<V>> result = new ArrayList<>();

    if (keys.length != 0) {
      for (String key : keys) {
        result.add(getEdgeProperty(direction, edge, blockOffset, key));
      }
    } else {
      for (String propertyKey : layoutInformation().edgePropertyKeys(edge.label())) {
        result.add(getEdgeProperty(direction, edge, blockOffset, propertyKey));
      }
    }

    return result.iterator();
  }

  public Map<String, Object> getEdgePropertyMap(Direction direction, OdbEdge edge, int blockOffset) {
    final Set<String> edgePropertyKeys = layoutInformation().edgePropertyKeys(edge.label());
    final Map<String, Object> results = new HashMap<>(edgePropertyKeys.size());

    for (String propertyKey : edgePropertyKeys) {
      final Object value = getEdgeProperty2(direction, edge, blockOffset, propertyKey);
      if (value != null) results.put(propertyKey, value);
    }

    return results;
  }

  public <V> Property<V> getEdgeProperty(Direction direction,
                                         OdbEdge edge,
                                         int blockOffset,
                                         String key) {
    V value = getEdgeProperty2(direction, edge, blockOffset, key);
    if (value == null) {
      return EmptyProperty.instance();
    }
    return new OdbProperty<>(key, value, edge);
  }

  // TODO drop suffix `2` after tinkerpop interface is gone
  public <P> P getEdgeProperty2(Direction direction,
                                OdbEdge edge,
                                int blockOffset,
                                String key) {
    int propertyPosition = getEdgePropertyIndex(direction, edge.label(), key, blockOffset);
    if (propertyPosition == -1) {
      return null;
    }
    return (P) adjacentNodesWithProperties[propertyPosition];
  }

  public <V> void setEdgeProperty(Direction direction,
                                  String edgeLabel,
                                  String key,
                                  V value,
                                  int blockOffset) {
    int propertyPosition = getEdgePropertyIndex(direction, edgeLabel, key, blockOffset);
    if (propertyPosition == -1) {
      throw new RuntimeException("Edge " + edgeLabel + " does not support property `" + key + "`.");
    }
    adjacentNodesWithProperties[propertyPosition] = value;
    /* marking as dirty *after* we updated - if node gets serialized before we finish, it'll be marked as dirty */
    this.markAsDirty();
  }

  private int calcAdjacentNodeIndex(Direction direction,
                                    String edgeLabel,
                                    int blockOffset) {
    int offsetPos = getPositionInEdgeOffsets(direction, edgeLabel);
    if (offsetPos == -1) {
      return -1;
    }

    int start = startIndex(offsetPos);
    return start + blockOffset;
  }

  /**
   * Return -1 if there exists no edge property for the provided argument combination.
   */
  private int getEdgePropertyIndex(Direction direction,
                                   String label,
                                   String key,
                                   int blockOffset) {
    int adjacentNodeIndex = calcAdjacentNodeIndex(direction, label, blockOffset);
    if (adjacentNodeIndex == -1) {
      return -1;
    }

    int propertyOffset = layoutInformation().getOffsetRelativeToAdjacentNodeRef(label, key);
    if (propertyOffset == -1) {
      return -1;
    }

    return adjacentNodeIndex + propertyOffset;
  }

  @Override
  public OdbEdge addEdge2(String label, Node inNode, Object... keyValues) {
    final NodeRef inNodeRef = (NodeRef) inNode;
    NodeRef thisNodeRef = ref;

    int outBlockOffset = storeAdjacentNode(Direction.OUT, label, inNodeRef, keyValues);
    int inBlockOffset = inNodeRef.get().storeAdjacentNode(Direction.IN, label, thisNodeRef, keyValues);

    OdbEdge dummyEdge = instantiateDummyEdge(label, thisNodeRef, inNodeRef);
    dummyEdge.setOutBlockOffset(outBlockOffset);
    dummyEdge.setInBlockOffset(inBlockOffset);

    return dummyEdge;
  }

  @Override
  public OdbEdge addEdge2(String label, Node inNode, Map<String, Object> keyValues) {
    return addEdge2(label, inNode, toKeyValueArray(keyValues));
  }

  @Override
  public void addEdgeSilent(String label, Node inNode, Object... keyValues) {
    final NodeRef inNodeRef = (NodeRef) inNode;
    NodeRef thisNodeRef = ref;

    storeAdjacentNode(Direction.OUT, label, inNodeRef, keyValues);
    inNodeRef.get().storeAdjacentNode(Direction.IN, label, thisNodeRef, keyValues);
  }

  @Override
  public void addEdgeSilent(String label, Node inNode, Map<String, Object> keyValues) {
    addEdgeSilent(label, inNode, toKeyValueArray(keyValues));
  }

  @Override
  public Edge addEdge(String label, Vertex inNode, Object... keyValues) {
    return addEdge2(label, (Node) inNode, keyValues);
  }

  @Override
  public Iterator<Edge> edges(org.apache.tinkerpop.gremlin.structure.Direction tinkerDirection, String... edgeLabels) {
    Direction direction = Converters.fromTinker(tinkerDirection);
    final MultiIterator2<Edge> multiIterator = new MultiIterator2<>();
    if (direction == Direction.IN || direction == Direction.BOTH) {
      for (String label : calcInLabels(edgeLabels)) {
        Iterator<OdbEdge> edgeIterator = createDummyEdgeIterator(Direction.IN, label);
        multiIterator.addIterator(edgeIterator);
      }
    }
    if (direction == Direction.OUT || direction == Direction.BOTH) {
      for (String label : calcOutLabels(edgeLabels)) {
        Iterator<OdbEdge> edgeIterator = createDummyEdgeIterator(Direction.OUT, label);
        multiIterator.addIterator(edgeIterator);
      }
    }

    return multiIterator;
  }

  @Override
  public Iterator<Vertex> vertices(org.apache.tinkerpop.gremlin.structure.Direction direction, String... edgeLabels) {
    return nodes(Converters.fromTinker(direction), edgeLabels);
  }

  /* lookup adjacent nodes via direction and labels */
  public Iterator<Vertex> nodes(Direction direction, String... edgeLabels) {
    final MultiIterator2<Vertex> multiIterator = new MultiIterator2<>();
    if (direction == Direction.IN || direction == Direction.BOTH) {
      for (String label : calcInLabels(edgeLabels)) {
        multiIterator.addIterator(in(label));
      }
    }
    if (direction == Direction.OUT || direction == Direction.BOTH) {
      for (String label : calcOutLabels(edgeLabels)) {
        multiIterator.addIterator(out(label));
      }
    }

    return multiIterator;
  }

  /* adjacent OUT nodes (all labels) */
  @Override
  public Iterator<Node> out() {
    final MultiIterator2<Node> multiIterator = new MultiIterator2<>();
    for (String label : layoutInformation().allowedOutEdgeLabels()) {
      multiIterator.addIterator(out(label));
    }
    return multiIterator;
  }

  /* adjacent OUT nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  @Override
  public Iterator<Node> out(String edgeLabel) {
    return createAdjacentNodeIterator(Direction.OUT, edgeLabel);
  }

  /* adjacent IN nodes (all labels) */
  @Override
  public Iterator<Node> in() {
    final MultiIterator2<Node> multiIterator = new MultiIterator2<>();
    for (String label : layoutInformation().allowedInEdgeLabels()) {
      multiIterator.addIterator(in(label));
    }
    return multiIterator;
  }

  /* adjacent IN nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  @Override
  public Iterator<Node> in(String edgeLabel) {
    return createAdjacentNodeIterator(Direction.IN, edgeLabel);
  }

  /* adjacent OUT/IN nodes (all labels) */
  @Override
  public Iterator<Node> both() {
    final MultiIterator2<Node> multiIterator = new MultiIterator2<>();
    multiIterator.addIterator(out());
    multiIterator.addIterator(in());
    return multiIterator;
  }

  /* adjacent OUT/IN nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  @Override
  public Iterator<Node> both(String edgeLabel) {
    final MultiIterator2<Node> multiIterator = new MultiIterator2<>();
    multiIterator.addIterator(out(edgeLabel));
    multiIterator.addIterator(in(edgeLabel));
    return multiIterator;
  }

  /* adjacent OUT edges (all labels) */
  @Override
  public Iterator<OdbEdge> outE() {
    final MultiIterator2<OdbEdge> multiIterator = new MultiIterator2<>();
    for (String label : layoutInformation().allowedOutEdgeLabels()) {
      multiIterator.addIterator(outE(label));
    }
    return multiIterator;
  }

  /* adjacent OUT edges for a specific label
   * specialized version of `edges(Direction, String...)` for efficiency */
  @Override
  public Iterator<OdbEdge> outE(String edgeLabel) {
    return createDummyEdgeIterator(Direction.OUT, edgeLabel);
  }

  /* adjacent IN edges (all labels) */
  @Override
  public Iterator<OdbEdge> inE() {
    final MultiIterator2<OdbEdge> multiIterator = new MultiIterator2<>();
    for (String label : layoutInformation().allowedInEdgeLabels()) {
      multiIterator.addIterator(inE(label));
    }
    return multiIterator;
  }

  /* adjacent IN edges for a specific label
   * specialized version of `edges(Direction, String...)` for efficiency */
  @Override
  public Iterator<OdbEdge> inE(String edgeLabel) {
    return createDummyEdgeIterator(Direction.IN, edgeLabel);
  }

  /* adjacent OUT/IN edges (all labels) */
  @Override
  public Iterator<OdbEdge> bothE() {
    final MultiIterator2<OdbEdge> multiIterator = new MultiIterator2<>();
    multiIterator.addIterator(outE());
    multiIterator.addIterator(inE());
    return multiIterator;
  }

  /* adjacent OUT/IN edges for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  @Override
  public Iterator<OdbEdge> bothE(String edgeLabel) {
    final MultiIterator2<OdbEdge> multiIterator = new MultiIterator2<>();
    multiIterator.addIterator(outE(edgeLabel));
    multiIterator.addIterator(inE(edgeLabel));
    return multiIterator;
  }

  /**
   * If there are multiple edges between the same two nodes with the same label, we use the
   * `occurrence` to differentiate between those edges. Both nodes use the same occurrence
   * index for the same edge.
   *
   * @return the occurrence for a given edge, calculated by counting the number times the given
   * adjacent node occurred between the start of the edge-specific block and the blockOffset
   */
  protected final int blockOffsetToOccurrence(Direction direction,
                                     String label,
                                     NodeRef otherNode,
                                     int blockOffset) {
    int offsetPos = getPositionInEdgeOffsets(direction, label);
    int start = startIndex(offsetPos);
    int strideSize = getStrideSize(label);

    int occurrenceCount = -1;
    for (int i = start; i <= start + blockOffset; i += strideSize) {
      final NodeRef adjacentNodeWithProperty = (NodeRef) adjacentNodesWithProperties[i];
      if (adjacentNodeWithProperty != null &&
          adjacentNodeWithProperty.id().equals(otherNode.id())) {
        occurrenceCount++;
      }
    }

    if (occurrenceCount == -1)
      throw new RuntimeException("unable to calculate occurrenceCount");
    else
      return occurrenceCount;
  }

  /**
   * @param direction  OUT or IN
   * @param label      the edge label
   * @param occurrence if there are multiple edges between the same two nodes with the same label,
   *                   this is used to differentiate between those edges.
   *                   Both nodes use the same occurrence index in their `adjacentNodesWithProperties` array for the same edge.
   * @return the index into `adjacentNodesWithProperties`
   */
  protected final int occurrenceToBlockOffset(Direction direction,
                                     String label,
                                     NodeRef adjacentNode,
                                     int occurrence) {
    int offsetPos = getPositionInEdgeOffsets(direction, label);
    int start = startIndex(offsetPos);
    int length = blockLength(offsetPos);
    int strideSize = getStrideSize(label);

    int currentOccurrence = 0;
    for (int i = start; i < start + length; i += strideSize) {
      final NodeRef adjacentNodeWithProperty = (NodeRef) adjacentNodesWithProperties[i];
      if (adjacentNodeWithProperty != null &&
          adjacentNodeWithProperty.id().equals(adjacentNode.id())) {
        if (currentOccurrence == occurrence) {
          int adjacentNodeIndex = i - start;
          return adjacentNodeIndex;
        } else {
          currentOccurrence++;
        }
      }
    }
    throw new RuntimeException("Unable to find occurrence " + occurrence + " of "
        + label + " edge to node " + adjacentNode.id());
  }

  /**
   * Removes an 'edge', i.e. in reality it removes the information about the adjacent node from
   * `adjacentNodesWithProperties`. The corresponding elements will be set to `null`, i.e. we'll have holes.
   * Note: this decrements the `offset` of the following edges in the same block by one, but that's ok because the only
   * thing that matters is that the offset is identical for both connected nodes (assuming thread safety).
   *
   * @param blockOffset must have been initialized
   */
  protected final synchronized void removeEdge(Direction direction, String label, int blockOffset) {
    int offsetPos = getPositionInEdgeOffsets(direction, label);
    int start = startIndex(offsetPos) + blockOffset;
    int strideSize = getStrideSize(label);

    for (int i = start; i < start + strideSize; i++) {
      adjacentNodesWithProperties[i] = null;
    }

    /* marking as dirty *after* we updated - if node gets serialized before we finish, it'll be marked as dirty */
    this.markAsDirty();
  }

  private Iterator<OdbEdge> createDummyEdgeIterator(Direction direction,
                                                 String label) {
    int offsetPos = getPositionInEdgeOffsets(direction, label);
    if (offsetPos != -1) {
      int start = startIndex(offsetPos);
      int length = blockLength(offsetPos);
      int strideSize = getStrideSize(label);

      return new DummyEdgeIterator(adjacentNodesWithProperties, start, start + length, strideSize,
          direction, label, ref);
    } else {
      return Collections.emptyIterator();
    }
  }

  private final Iterator<Node> createAdjacentNodeIterator(Direction direction, String label) {
    return createAdjacentNodeIteratorByOffSet(getPositionInEdgeOffsets(direction, label));
  }

  /* Simplify hoisting of string lookups.
   * n.b. `final` so that the JIT compiler can inline it */
  public final Iterator<Node> createAdjacentNodeIteratorByOffSet(int offsetPos){
    if (offsetPos != -1) {
      int start = startIndex(offsetPos);
      int length = blockLength(offsetPos);
      int strideSize = layoutInformation().getEdgePropertyCountByOffsetPos(offsetPos) + 1;
      return new ArrayOffsetIterator<>(adjacentNodesWithProperties, start, start + length, strideSize);
    } else {
      return Collections.emptyIterator();
    }
  }

  private int storeAdjacentNode(Direction direction,
                                String edgeLabel,
                                NodeRef nodeRef,
                                Object... edgeKeyValues) {
    int blockOffset = storeAdjacentNode(direction, edgeLabel, nodeRef);

    /* set edge properties */
    for (int i = 0; i < edgeKeyValues.length; i = i + 2) {
      if (!edgeKeyValues[i].equals(T.id) && !edgeKeyValues[i].equals(T.label)) {
        String key = (String) edgeKeyValues[i];
        Object value = edgeKeyValues[i + 1];
        setEdgeProperty(direction, edgeLabel, key, value, blockOffset);
      }
    }

    /* marking as dirty *after* we updated - if node gets serialized before we finish, it'll be marked as dirty */
    this.markAsDirty();

    return blockOffset;
  }

  private final synchronized int storeAdjacentNode(Direction direction, String edgeLabel, NodeRef nodeRef) {
    int offsetPos = getPositionInEdgeOffsets(direction, edgeLabel);
    if (offsetPos == -1) {
      throw new RuntimeException("Edge of type " + edgeLabel + " with direction " + direction +
          " not supported by class " + getClass().getSimpleName());
    }
    int start = startIndex(offsetPos);
    int length = blockLength(offsetPos);
    int strideSize = getStrideSize(edgeLabel);

    int insertAt = start + length;
    if (adjacentNodesWithProperties.length <= insertAt || adjacentNodesWithProperties[insertAt] != null) {
      // space already occupied - grow adjacentNodesWithProperties array, leaving some room for more elements
      adjacentNodesWithProperties = growAdjacentNodesWithProperties(offsetPos, strideSize, insertAt, length);
    }

    adjacentNodesWithProperties[insertAt] = nodeRef;
    // update edgeOffset length to include the newly inserted element
    edgeOffsets.set(2 * offsetPos + 1, length + strideSize);

    int blockOffset = length;
    return blockOffset;
  }

  private int startIndex(int offsetPosition) {
    return edgeOffsets.get(2 * offsetPosition);
  }

  /**
   * @return number of elements reserved in `adjacentNodesWithProperties` for a given edge label
   * includes space for the node ref and all properties
   */
  private final int getStrideSize(String edgeLabel) {
    int sizeForNodeRef = 1;
    Set<String> allowedPropertyKeys = layoutInformation().edgePropertyKeys(edgeLabel);
    return sizeForNodeRef + allowedPropertyKeys.size();
  }

  /**
   * @return The position in edgeOffsets array. -1 if the edge label is not supported
   */
  private final int getPositionInEdgeOffsets(Direction direction, String label) {
    final Integer positionOrNull;
    if (direction == Direction.OUT) {
      positionOrNull = layoutInformation().outEdgeToOffsetPosition(label);
    } else {
      positionOrNull = layoutInformation().inEdgeToOffsetPosition(label);
    }
    if (positionOrNull != null) {
      return positionOrNull;
    } else {
      return -1;
    }
  }

  /**
   * Returns the length of an edge type block in the adjacentNodesWithProperties array.
   * Length means number of index positions.
   */
  private final int blockLength(int offsetPosition) {
    return edgeOffsets.get(2 * offsetPosition + 1);
  }

  private final String[] calcInLabels(String... edgeLabels) {
    if (edgeLabels.length != 0) {
      return edgeLabels;
    } else {
      return layoutInformation().allowedInEdgeLabels();
    }
  }

  private final String[] calcOutLabels(String... edgeLabels) {
    if (edgeLabels.length != 0) {
      return edgeLabels;
    } else {
      return layoutInformation().allowedOutEdgeLabels();
    }
  }

  /**
   * grow the adjacentNodesWithProperties array
   * <p>
   * preallocates more space than immediately necessary, so we don't need to grow the array every time
   * (tradeoff between performance and memory).
   * grows with the square root of the double of the current capacity.
   */
  private final synchronized Object[] growAdjacentNodesWithProperties(int offsetPos,
                                                   int strideSize,
                                                   int insertAt,
                                                   int currentLength) {
    // TODO optimize growth function - optimizing has potential to save a lot of memory, but the below slowed down processing massively
//    int currentCapacity = currentLength / strideSize;
//    double additionalCapacity = Math.sqrt(currentCapacity) + 1;
//    int additionalCapacityInt = (int) Math.ceil(additionalCapacity);
//    int additionalEntriesCount = additionalCapacityInt * strideSize;
    int growthEmptyFactor = 2;
    int additionalEntriesCount = (currentLength + strideSize) * growthEmptyFactor;
    int newSize = adjacentNodesWithProperties.length + additionalEntriesCount;
    Object[] newArray = new Object[newSize];
    System.arraycopy(adjacentNodesWithProperties, 0, newArray, 0, insertAt);
    System.arraycopy(adjacentNodesWithProperties, insertAt, newArray, insertAt + additionalEntriesCount, adjacentNodesWithProperties.length - insertAt);

    // Increment all following start offsets by `additionalEntriesCount`.
    for (int i = offsetPos + 1; 2 * i < edgeOffsets.length(); i++) {
      edgeOffsets.set(2 * i, edgeOffsets.get(2 * i) + additionalEntriesCount);
    }
    return newArray;
  }

  /**
   * to follow the tinkerpop api, instantiate and return a dummy edge, which doesn't really exist in the graph
   */
  protected final OdbEdge instantiateDummyEdge(String label, NodeRef outNode, NodeRef inNode) {
    final EdgeFactory edgeFactory = ref.graph.edgeFactoryByLabel.get(label);
    if (edgeFactory == null)
      throw new IllegalArgumentException("specializedEdgeFactory for label=" + label + " not found - please register on startup!");
    return edgeFactory.createEdge(ref.graph, outNode, inNode);
  }

  /**
   * Trims the node to save storage: shrinks overallocations
   * */
  public synchronized long trim(){
    int newSize = 0;
    for(int offsetPos = 0; 2*offsetPos < edgeOffsets.length(); offsetPos++){
      int length = blockLength(offsetPos);
      newSize += length;
    }
    Object[] newArray = new Object[newSize];

    int off = 0;
    for(int offsetPos = 0; 2*offsetPos < edgeOffsets.length(); offsetPos++){
      int start = startIndex(offsetPos);
      int length = blockLength(offsetPos);
      System.arraycopy(adjacentNodesWithProperties, start, newArray, off, length);
      edgeOffsets.set(2 * offsetPos, off);
      off += length;
    }
    int oldsize = adjacentNodesWithProperties.length;
    adjacentNodesWithProperties = newArray;
    return (long)newSize + ( ((long)oldsize) << 32);
  }

  public final boolean isDirty() {
    return dirty;
  }

  private Object[] toKeyValueArray(Map<String, Object> keyValues) {
    final Object[] keyValuesArray = new Object[keyValues.size() * 2];
    int i = 0;
    final Iterator<Map.Entry<String, Object>> iterator = keyValues.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Object> entry = iterator.next();
      keyValuesArray[i++] = entry.getKey();
      keyValuesArray[i++] = entry.getValue();
    }
    return keyValuesArray;
  }

}
