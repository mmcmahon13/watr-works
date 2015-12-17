package edu.umass.cs.iesl.watr
package watrmarks
package dom



import scalaz.{Show, TreeLoc, Tree}


case class WatrDomCursor(
  loc: TreeLoc[WatrElement]
) {

  def root: WatrDomCursor                     = WatrDomCursor(loc.root)
  def parent: Option[WatrDomCursor]           = loc.parent map {p => WatrDomCursor(p) }
  def left: Option[WatrDomCursor]             = loc.left map {p => WatrDomCursor(p) }
  def right: Option[WatrDomCursor]            = loc.right map {p => WatrDomCursor(p) }
  def firstChild: Option[WatrDomCursor]       = loc.firstChild map {p => WatrDomCursor(p) }
  def lastChild: Option[WatrDomCursor]        = loc.lastChild map {p => WatrDomCursor(p) }
  def getChild(n: Int): Option[WatrDomCursor] = loc.getChild(n) map {p => WatrDomCursor(p) }

  // def findChild(p: Tree[A] =
  // def split(acc: TreeForest[A], xs: TreeForest[A]): Option[(TreeForest[A], Tree[A], TreeForest[A])] =
  // def find(p: WatrDomCursor =
  // def toTree: Tree[A] =
  // def toForest: TreeForest[A] =
  // def isRoot: Boolean =
  // def isFirst: Boolean =
  // def isLast: Boolean =
  // def isLeaf: Boolean =
  // def isChild: Boolean =
  // def hasChildren: Boolean =

}


sealed trait WatrElement {
  // def transforms: List[Transform]
}


case class WatrDom(
  tree: Tree[WatrElement]
) {

  def prettyPrint: String = tree.drawTree

  def toCursor = WatrDomCursor(tree.loc)

}

case class Document (
  transforms: List[Transform] = List()
) extends WatrElement  {
  override def toString = s"""<doc:>"""
}

case class Svg (
  transforms: List[Transform] = List()
) extends WatrElement  {
  override def toString = s"""<svg:${transforms}>"""
}

case class Desc (
) extends WatrElement {
  override def toString = s"""<desc:>"""
}

case class Grp (
  transforms: List[Transform] = List()
) extends WatrElement {
  override def toString = s"""<g:${transforms}>"""
}

case class Defs (
) extends WatrElement {
  override def toString = s"""<defs:>"""
}

case class Text (
  transforms: List[Transform] = List()
) extends WatrElement   {
  override def toString = s"""<text:${transforms}>"""
}

case class Path (
  transforms: List[Transform] = List()
) extends WatrElement   {
  override def toString = s"""<path:>"""
}

// case class Chars(
//   chs: String
// ) extends WatrElement {
//   override def toString = s"""<ch:${chs}>"""
// }

case class TSpan (
  text: String,
  transforms: List[Transform],
  xs: List[Double],
  endX: Double,
  y: Double,
  fontSize: String,
  fontFamily: String
) extends WatrElement  {
  override def toString = s"""<tspan:${text}>"""
} 


