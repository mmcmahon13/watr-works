package edu.umass.cs.iesl.watr
package spindex

import scalaz.@@
import utils._
import watrmarks._

import textboxing.{TextBoxing => TB}

import ComponentRendering._
import ComponentOperations._
import IndexShapeOperations._
import GeometricFigure._

import TypeTags._

sealed trait Component {
  def id: Int@@ComponentID

  def zoneIndex: ZoneIndexer

  def targetRegions: Seq[TargetRegion]

  def targetRegion: TargetRegion = {
    targetRegions.headOption
      .map(tr => TargetRegion(RegionID(0), tr.target, bounds))
      .getOrElse {  sys.error("no target region found in Component}") }
  }

  def chars: String

  def atoms: Seq[PageAtom]

  def children(): Seq[Component]

  // TODO this seems like an awful idea:
  def replaceChildren(ch: Seq[Component]): Unit

  def charComponents: Seq[PageComponent]

  def descendants(): Seq[Component] = {
    def _loop(c: Component): Seq[Component] = {
      if (c.children().isEmpty) List.empty
      else c.children.flatMap(cn => cn +: _loop(cn))
    }

    _loop(this)
  }

  def labeledChildren(l: Label): Seq[Component] = {
    children.filter(_.getLabels.contains(l))
  }

  def labeledDescendants(l: Label): Seq[Component] = {
    descendants.filter(_.getLabels.contains(l))
  }

  def mapChars(subs: Seq[(Char, String)]): Component

  def toText(implicit idgen:Option[CCRenderState] = None): String

  def bounds: LTBounds

  def orientation: Double = 0.0d // placeholder until this is implemented for real

  // Best-fit line through the center(s) of all contained connected components
  def characteristicLine: Line

  def x0: Double = characteristicLine.p1.x
  def y0: Double = characteristicLine.p1.y
  def x1: Double = characteristicLine.p2.x
  def y1: Double = characteristicLine.p2.y

  def height: Double

  def angularDifference(j: Component): Double = {
    val diff = math.abs(characteristicLine.angle - j.characteristicLine.angle)
    if (diff <= math.Pi/2) {
      return diff;
    } else {
      return math.Pi - diff;
    }
  }

  def horizontalDistance(other: Component,  orientation: Double): Double = {
    var xs = Array[Double](0, 0, 0, 0)
    var s = math.sin(-orientation)
    var c = math.cos(-orientation);
    xs(0) = c * x0 - s * y0;
    xs(1) = c * x1 - s * y1;
    xs(2) = c * other.x0 - s * other.y0;
    xs(3) = c * other.x1 - s * other.y1;
    var overlapping = xs(1) >= xs(2) && xs(3) >= xs(0);
    xs = xs.sorted
    math.abs(xs(2) - xs(1)) * (if(overlapping) 1 else -1)
  }

  def verticalDistance(other: Component, orientation: Double): Double = {
    val xm = (x0 + x1) / 2
    val  ym = (y0 + y1) / 2;
    val xn = (other.x0 + other.x1) / 2
    val yn = (other.y0 + other.y1) / 2;
    val a = math.tan(orientation);
    return math.abs(a * (xn - xm) + ym - yn) / math.sqrt(a * a + 1);
  }

  def determineNormalTextBounds: LTBounds = {
    val mfHeights = Histogram.getMostFrequentValues(children.map(_.bounds.height), 0.1d)
    val mfTops = Histogram.getMostFrequentValues(children.map(_.bounds.top), 0.1d)


    val mfHeight= mfHeights.headOption.map(_._1).getOrElse(0d)
    val mfTop = mfTops.headOption.map(_._1).getOrElse(0d)

    children
      .map({ c =>
        val cb = c.bounds
        LTBounds(
          left=cb.left, top=mfTop,
          width=cb.width, height=mfHeight
        )
      })
      .foldLeft(children().head.bounds)( { case (b1, b2) =>
        b1 union b2
      })
  }




  def findCenterY(): Double = {
    children().map({c => c.bounds.toCenterPoint.y}).sum / children().length
  }


  def addLabel(l: Label): Component = {
    zoneIndex.addLabel(this, l)
  }

  def removeLabel(l: Label): Component = {
    zoneIndex.removeLabel(this, l)
  }

  def getLabels(): Set[Label] = {
    zoneIndex.getLabels(this)
  }

  def containedLabels(): Set[Label] = {
    val descLabels = children.map(_.containedLabels())
    val descLabelSet = descLabels.foldLeft(Set[Label]())(_ ++ _)
    getLabels() ++ descLabelSet
  }
}

case class PageComponent(
  id: Int@@ComponentID,
  component: PageAtom,
  override val zoneIndex: ZoneIndexer
) extends Component {

  def targetRegions: Seq[TargetRegion] = Seq(component.region)

  def children(): Seq[Component] = Seq()

  def replaceChildren(ch: Seq[Component]): Unit = ()

  def charComponents: Seq[PageComponent] = Seq(this)

  def atoms: Seq[PageAtom] = Seq(component)

  def char = component match {
    case rg: CharAtom => rg.char.toString
    case rg: ImgAtom => ""
  }

  def mapChars(subs: Seq[(Char, String)]): Component  = {
    subs
      .find(_._1.toString==char)
      .map({case (_, sub) =>
        component match {
          case rg: CharAtom => this.copy(
            component= CharAtom.apply(rg.region, sub, rg.wonkyCharCode))


          case rg: ImgAtom  => this
        }
      })
      .getOrElse(this)
  }

  override val characteristicLine: Line = {
    val dx = component.region.bbox.width / 3
    val dy = dx * math.tan(orientation);

    Line(Point(
      centerX(component) - dx,
      centerY(component) - dy
    ), Point(
      centerY(component) + dy,
      centerX(component) + dx
    ))
  }

  val bounds = component.region.bbox
  def height: Double  = bounds.height

  def toText(implicit idgen:Option[CCRenderState] = None): String = {
    component match {
      case rg: CharAtom => rg.char.toString
      case rg: ImgAtom => ""
    }
  }

  def chars: String = toText

}
import scala.collection.mutable

object ConnectedComponent {
  def apply(
    id: Int@@ComponentID,
    components: Seq[Component],
    zoneIndex: ZoneIndexer
  ): ConnectedComponents = ConnectedComponents(
    id, mutable.MutableList(components:_*), zoneIndex
  )
}


case class ConnectedComponents(
  id: Int@@ComponentID,
  components: mutable.MutableList[Component],
  override val zoneIndex: ZoneIndexer
) extends Component {

  def targetRegions: Seq[TargetRegion] = components.flatMap(_.targetRegions)

  def replaceChildren(ch: Seq[Component]): Unit = {
    this.components.clear()
    this.components ++= ch
  }

  def atoms: Seq[PageAtom] = components.flatMap(_.atoms)

  def children(): Seq[Component] = components

  def mapChars(subs: Seq[(Char, String)]): Component  = {
    copy(
      components = components.map(_.mapChars(subs))
    )
  }

  def chars:String = {
    components.map(_.chars).mkString
  }

  def charComponents: Seq[PageComponent] =
    components.flatMap(_.charComponents)

  def toText(implicit idgen:Option[CCRenderState] = None): String ={
    val ccs = renderConnectedComponents(this)
    TB.hcat(ccs).toString()
  }

  override def characteristicLine: Line = {
    if (components.isEmpty) {
      sys.error("Component list must not be empty")
    } else if (components.length == 1) {
      components.head.characteristicLine
    } else {
      // Linear regression through component centers
      val (sx, sxx, sxy, sy) = components
        .foldLeft((0d, 0d, 0d, 0d))({ case ((sx, sxx, sxy, sy), comp) =>
          val c = comp.bounds.toCenterPoint
          (sx + c.x, sxx + c.x*c.x, sxy + c.x*c.y, sy + c.y)
        })

      val b:Double = (components.length * sxy - sx * sy) / (components.length * sxx - sx * sx);
      val a:Double = (sy - b * sx) / components.length;

      val x0 = components.head.bounds.toCenterPoint.x
      val x1 = components.last.bounds.toCenterPoint.x
      val y0 = a + b * x0;
      val y1 = a + b * x1;

      Line(
        Point(x0, a + b * x0),
        Point(x1, a + b * x1))
    }
  }

  def bounds: LTBounds = components.tail
    .map(_.bounds)
    .foldLeft(components.head.bounds)( { case (b1, b2) =>
      b1 union b2
    })

  def height: Double  = bounds.height




}
