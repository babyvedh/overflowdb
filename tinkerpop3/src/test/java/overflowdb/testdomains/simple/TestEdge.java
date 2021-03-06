package overflowdb.testdomains.simple;

import overflowdb.EdgeFactory;
import overflowdb.EdgeLayoutInformation;
import overflowdb.NodeRef;
import overflowdb.OdbEdge;
import overflowdb.OdbGraph;

import java.util.Arrays;
import java.util.HashSet;

public class TestEdge extends OdbEdge {
  public static final String LABEL = "testEdge";
  public static final String LONG_PROPERTY = "longProperty";
  public static final HashSet<String> PROPERTY_KEYS = new HashSet<>(Arrays.asList(LONG_PROPERTY));

  public TestEdge(OdbGraph graph, NodeRef outVertex, NodeRef inVertex) {
    super(graph, LABEL, outVertex, inVertex, PROPERTY_KEYS);
  }

  public Long longProperty() {
    return (Long) property(LONG_PROPERTY).value();
  }

  public static final EdgeLayoutInformation layoutInformation = new EdgeLayoutInformation(LABEL, PROPERTY_KEYS);

  public static EdgeFactory<TestEdge> factory = new EdgeFactory<TestEdge>() {
    @Override
    public String forLabel() {
      return TestEdge.LABEL;
    }

    @Override
    public TestEdge createEdge(OdbGraph graph, NodeRef outVertex, NodeRef inVertex) {
      return new TestEdge(graph, outVertex, inVertex);
    }
  };
}
