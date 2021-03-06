package overflowdb;

import java.util.Iterator;
import java.util.Map;

import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface Node extends OdbElement, Vertex {

  /**
   * Add an outgoing edge to the node with provided label and edge properties as key/value pairs.
   * These key/values must be provided in an even number where the odd numbered arguments are {@link String}
   * property keys and the even numbered arguments are the related property values.
   */
  // TODO drop suffix `2` after tinkerpop interface is gone
  OdbEdge addEdge2(String label, Node inNode, Object... keyValues);

  /**
   * Add an outgoing edge to the node with provided label and edge properties as key/value pairs.
   */
  // TODO drop suffix `2` after tinkerpop interface is gone
  OdbEdge addEdge2(String label, Node inNode, Map<String, Object> keyValues);

  /**
   * Add an outgoing edge to the node with provided label and edge properties as key/value pairs.
   * These key/values must be provided in an even number where the odd numbered arguments are {@link String}
   * property keys and the even numbered arguments are the related property values.
   * Just like {{{addEdge2}}, but doesn't instantiate and return a dummy edge
   */
  void addEdgeSilent(String label, Node inNode, Object... keyValues);

  /**
   * Add an outgoing edge to the node with provided label and edge properties as key/value pairs.
   * Just like {{{addEdge2}}, but doesn't instantiate and return a dummy edge
   */
  void addEdgeSilent(String label, Node inNode, Map<String, Object> keyValues);

  // TODO drop suffix `2` after tinkerpop interface is gone
  long id2();

  /* adjacent OUT nodes (all labels) */
  Iterator<Node> out();

  /* adjacent OUT nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  Iterator<Node> out(String edgeLabel);

  /* adjacent IN nodes (all labels) */
  Iterator<Node> in();

  /* adjacent IN nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  Iterator<Node> in(String edgeLabel);

  /* adjacent OUT/IN nodes (all labels) */
  Iterator<Node> both();

  /* adjacent OUT/IN nodes for a specific label
   * specialized version of `nodes(Direction, String...)` for efficiency */
  Iterator<Node> both(String edgeLabel);

  /* adjacent OUT edges (all labels) */
  Iterator<OdbEdge> outE();

  /* adjacent OUT edges for a specific label
   * specialized version of `edges(Direction, String...)` for efficiency */
  Iterator<OdbEdge> outE(String edgeLabel);

  /* adjacent IN edges (all labels) */
  Iterator<OdbEdge> inE();

  /* adjacent IN edges for a specific label
   * specialized version of `edges(Direction, String...)` for efficiency */
  Iterator<OdbEdge> inE(String edgeLabel);

  /* adjacent OUT/IN edges (all labels) */
  Iterator<OdbEdge> bothE();

  /* adjacent OUT/IN edges for a specific label
   * specialized version of `edges(Direction, String...)` for efficiency */
  Iterator<OdbEdge> bothE(String edgeLabel);

}
