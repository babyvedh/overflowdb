package overflowdb.traversal.testdomains.hierarchical

import org.apache.tinkerpop.gremlin.structure.VertexProperty
import overflowdb.{NodeRef, OdbNode}

class CarDb(ref: NodeRef[CarDb]) extends OdbNode(ref) {
  private var _name: String = null

  def name: String = _name

  override def valueMap = {
    val properties = new java.util.HashMap[String, Any]
    if (_name != null) properties.put(Car.PropertyNames.Name, _name)
    properties
  }

  override protected def specificProperty2(key: String) =
    key match {
      case Car.PropertyNames.Name => _name
      case _ => null
    }

  override protected def updateSpecificProperty[V](cardinality: VertexProperty.Cardinality, key: String, value: V) =
    key match {
      case Car.PropertyNames.Name =>
        _name = value.asInstanceOf[String]
        property(Car.PropertyNames.Name)
      case _ =>
        throw new RuntimeException("property with key=" + key + " not (yet) supported by " + this.getClass().getName());
    }

  override protected def removeSpecificProperty(key: String) =
    key match {
      case Car.PropertyNames.Name => _name = null
      case _ =>
        throw new RuntimeException("property with key=" + key + " not (yet) supported by " + this.getClass().getName());
    }

  override protected def layoutInformation = Car.layoutInformation

  override def toString = s"CarDb(id=${ref.id}, name=$name)"
}
