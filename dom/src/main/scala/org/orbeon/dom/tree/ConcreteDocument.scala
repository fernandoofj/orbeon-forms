package org.orbeon.dom.tree

import java.{util ⇒ ju}

import org.orbeon.dom._

object ConcreteDocument {
  def apply(rootElement: Element) = {
    val newDoc = new ConcreteDocument
    newDoc.setRootElement(rootElement)
    newDoc
  }
}

class ConcreteDocument extends AbstractBranch with Document {

  // ORBEON TODO: review: why would a document expose `name`?
  private var _name: String = _
  override def getName = _name
  override def setName(name: String) = _name = name

  override def getDocument: Document = this

  private var _rootElement: Element = _
  def getRootElement = _rootElement

  private var _internalContent: ju.List[Node] = _
  protected def internalContent = {
    if (_internalContent eq null) {
      _internalContent = new ju.ArrayList[Node](1)
      if (_rootElement ne null)
        _internalContent.add(_rootElement)
    }
    _internalContent
  }

  def content = internalContent

  override def clone(): AnyRef = {
    val document = super.clone().asInstanceOf[ConcreteDocument]
    document._rootElement = null
    document._internalContent = null
    document.appendContent(this)
    document
  }

  def clearContent(): Unit = {
    contentRemoved()
    _internalContent = null
    _rootElement = null
  }

  private def contentRemoved(): Unit =
    for (i ← 0 until internalContent.size) {
      internalContent.get(i) match {
        case node: Node ⇒ childRemoved(node)
        case _ ⇒
      }
    }

  protected def addNode(node: Node): Unit = {
    if (node ne null) {
      val document = node.getDocument
      if ((document ne null) && (document ne this)) {
        throw new IllegalAddException(
          this,
          node,
          s"The Node already has an existing document: $document"
        )
      }
      content.add(node)
      childAdded(node)
    }
  }

  protected def addNode(index: Int, node: Node): Unit = {
    if (node ne null) {
      val document = node.getDocument
      if ((document ne null) && (document ne this)) {
        throw new IllegalAddException(
          this,
          node,
          s"The Node already has an existing document: $document"
        )
      }
      content.add(index, node)
      childAdded(node)
    }
  }

  protected def removeNode(node: Node): Boolean = {
    if (node == _rootElement) {
      _rootElement = null
    }
    if (content.remove(node)) {
      childRemoved(node)
      return true
    }
    false
  }

  override def getStringValue: String = {
    val root = getRootElement
    if (root ne null)
      root.getStringValue else ""
  }

  def accept(visitor: Visitor): Unit = {
    visitor.visit(this)
    val ct = content
    if (ct ne null) {
      val iter = ct.iterator()
      while (iter.hasNext) {
        val node = iter.next()
        node.accept(visitor)
      }
    }
  }

  override def toString: String = {
    super.toString + " [Document]"
  }

  def normalize(): Unit = {
    val element = getRootElement
    if (element ne null) {
      element.normalize()
    }
  }

  def addComment(comment: String): Document = {
    val node = DocumentFactory.createComment(comment)
    add(node)
    this
  }

  def addProcessingInstruction(target: String, data: String): Document = {
    val node = DocumentFactory.createProcessingInstruction(target, data)
    add(node)
    this
  }

  def setRootElement(rootElement: Element): Unit = {
    // TODO ORBEON review: what if we have text and comment nodes at the top?
    clearContent()
    if (rootElement ne null) {
      add(rootElement)
      rootElementAdded(rootElement)
    }
  }

  def add(element: Element): Unit = {
    checkAddElementAllowed(element)
    addNode(element)
    rootElementAdded(element)
  }

  private def rootElementAdded(element: Element): Unit = {
    _rootElement = element
    element.setDocument(this)
  }

  def remove(element: Element): Boolean = {
    val answer = removeNode(element)
    val root = getRootElement
    if ((root ne null) && answer) {
      setRootElement(null)
    }
    element.setDocument(null)
    answer
  }

  protected[dom] def childAdded(node: Node): Unit =
    if (node ne null)
      node.setDocument(this)

  protected[dom] def childRemoved(node: Node): Unit =
    if (node ne null)
      node.setDocument(null)

  protected def checkAddElementAllowed(element: Element): Unit = {
    val root = getRootElement
    if (root ne null) {
      throw new IllegalAddException(
        this,
        element,
         s"Cannot add another element to this Document as it already has a root element of: ${root.getQualifiedName}"
      )
    }
  }
}
