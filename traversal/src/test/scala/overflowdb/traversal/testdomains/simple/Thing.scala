package overflowdb.traversal.testdomains.simple

import overflowdb.traversal._
import overflowdb.{NodeFactory, NodeLayoutInformation, NodeRef, OdbGraph, PropertyKey}

import scala.jdk.CollectionConverters._

class Thing(graph: OdbGraph, _id: Long) extends NodeRef[ThingDb](graph, _id) {
  override def label: String = Thing.Label

  def name: String = get.name
  def size: Integer = get.size

  /* Thing --- followedBy --- Thing */
  def followedBy: Traversal[Thing] = get.followedBy

  override def toString = s"Thing(id=$id)"
}


object Thing {
  val Label = "thing"
  val LabelId = 6

  object Properties {
    val Name = PropertyKey[String](PropertyNames.Name)
    val Size = PropertyKey[Integer](PropertyNames.Size)
  }

  object PropertyNames {
    val Name = "name"
    val Size = "size"
    val all: Set[String] = Set(Name, Size)
  }

  val factory: NodeFactory[ThingDb] = new NodeFactory[ThingDb]() {
    override def forLabel: String = Label
    override def forLabelId() = LabelId
    override def createNode(ref: NodeRef[ThingDb]) = new ThingDb(ref)
    override def createNodeRef(graph: OdbGraph, id: Long) = new Thing(graph, id)
  }

  val layoutInformation: NodeLayoutInformation =
    new NodeLayoutInformation(
      LabelId,
      PropertyNames.all.asJava,
      List(Connection.layoutInformation).asJava,
      List(Connection.layoutInformation).asJava
    )
}
