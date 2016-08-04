package edu.umass.cs.iesl.watr
package watrcolors
package client

import scala.collection.mutable
import org.querki.jquery._

trait VisualTraceOperations extends FabricCanvasOperations {

  import GeometricFigure._
  import TraceLog._

  val pageGeometry = mutable.Map[Int, PageGeometry]()

  val pageOffsets = mutable.MutableList[LTBounds]()

  val pageImageGeometries = mutable.MutableList[LTBounds]()


  def totalPagesHeight: Double = pageOffsets.lastOption
    .map{ p => p.top + p.height }
    .getOrElse { 0d }

  def canvasBorder: Int
  def canvasX: Int
  def canvasY: Int
  def canvasW: Int
  def canvasH: Int



  // def canvasXScale = 2
  // def canvasYScale = 2

  // def offsetX(v: Double): Double = {
  //   v - canvasX + canvasBorder
  // }

  // def offsetY(v: Double): Double = {
  //   v - canvasY + canvasBorder
  // }
  // def scaleY(v: Double): Double = {
  //   (canvasYScale * v)
  // }
  // def scaleX(v: Double): Double = {
  //   (canvasXScale * v)
  // }

  // def transformX(v: Double) = offsetX(scaleX(v))
  // def transformY(v: Double) = offsetY(scaleY(v))

  var xxx = 10
  def transformTargetRegion(tr: TargetRegion): TargetRegion = {
    val offsetPage = pageOffsets(tr.target)
    val pageImgGeometry = pageImageGeometries(tr.target)

    val s = tr.bbox

    val pageTopTrans = (offsetPage.top * canvasH / totalPagesHeight) + canvasY
    val ltrans = (s.left * pageImgGeometry.width / offsetPage.width)
    val ttrans = (s.top * pageImgGeometry.height / offsetPage.height) + pageTopTrans
    val wtrans = (s.width * pageImgGeometry.width / offsetPage.width)
    val htrans = (s.height * pageImgGeometry.height / offsetPage.height)

    val bounds = LTBounds(ltrans, ttrans, wtrans, htrans)
    if (xxx > 0) {
      xxx -= 1
      println(s""" transformTargetRegion: ${s} -> ${bounds}  """)
      println(s"""     offsetPage: ${offsetPage} """)
      println(s"""     pageTopTrans: ${pageTopTrans} """)
      println(s"""     canvas x,y,w,h: $canvasX, $canvasY, $canvasW, $canvasH """)
    }

    tr.copy(bbox = bounds)
  }

  import scala.scalajs.js
  import native.fabric

  def setGradient(
    obj: fabric.FabricObject,
    strokeOrFill: String, // stroke|fill
    gtype: String, // radial|linear
    start: (Int, Int),
    end: (Int, Int),
    colorStops: js.Object,
    radii: Option[(Int, Int)] = None
      // gradientTransform: js.Object = js.Dynamic.literal()
  ): fabric.FabricObject = {

    val rd1 = radii.map(_._1).getOrElse(0)
    val rd2 = radii.map(_._2).getOrElse(0)

    obj.setGradient(strokeOrFill,
      js.Dynamic.literal(
        "type"       -> gtype,
        "x1"         -> start._1,
        "x2"         -> start._2,
        "y1"         -> end._1,
        "y2"         -> end._2,
        "r1"         -> rd1,
        "r2"         -> rd2,
        "colorStops" -> colorStops,
        "gradientTransform" -> js.Array[Int](1, 0, 0, 1, 0, 0)
      ))
  }




  def runTrace(traces: Seq[TraceLog]): Unit = {
    fabricCanvas.renderOnAddRemove = false

    var classNum = 0

    val classStack = mutable.Stack[String]()

    var currR = 0
    var currG = 0
    var currB = 0

    def nextRGB(): String  = {
      currR += 1; currR = currR % 16
      currG += 3; currG = currG % 16
      currB += 5; currB = currB % 16
      val r = currR.toHexString
      val g = currG.toHexString
      val b = currB.toHexString
      s"#$r$g$b"
    }

    var currRGB = nextRGB()




    def _run(traceEntries: Seq[TraceLog]): Unit = {
      traceEntries.foreach({ _ match {
        case Noop =>
        case SetPageGeometries(b: Seq[PageGeometry]) =>
          pageGeometry.clear()
          pageOffsets.clear()

          b.foreach { geom =>
            pageGeometry.put(geom.id, geom)
          }

          pageGeometry.keys.toList.sorted.foreach{k =>
            val geom = pageGeometry(k)

            pageOffsets.lastOption match {
              case Some(lastPageGeom) =>
                pageOffsets += geom.bounds.copy(
                  top=geom.bounds.top + lastPageGeom.top + lastPageGeom.height
                )
              case None =>
                pageOffsets += geom.bounds
            }
          }
          println(s"""offset pages = ${pageOffsets.mkString("\n   ")}""")

        case Show(s: Seq[TargetRegion]) =>
          val rs = s.map(
            transformTargetRegion(_)
          )

          rs.foreach(tr => addShape(tr.bbox, "blue", currRGB))


        case ShowZone(s: Zone) =>
          println(s"ShowZone! ${s}")

        case ShowComponent(s: Component) =>
          val ttrans = transformTargetRegion(s.targetRegion)
          addShape(ttrans.bbox, "blue", currRGB)

        case ShowLabel(s: Label) =>

          // val cls = classStack.mkString(" ")
          val cls = "."+classStack.top

          val content = s"""<li><button class="$cls">${s.ns}:${s.key}</button></li>"""

          // jQuery("#messages").hover(handlerInOut: Function1[JQueryEventObject, Any])

          jQuery("#messages").append(
            content
          )

          // import org.scalajs.jquery._

          val hin = (e:JQueryEventObject) => {
            println("hover in")
            jQuery(e.target).addClass("hover")
            jQuery(cls).addClass("hover")
          }
          val hout = (e:JQueryEventObject) => {
            println("hover out")
            jQuery(e.target).removeClass("hover")
            jQuery(cls).removeClass("hover")
          }

          // jQuery(cls).hover(hin, hout)

        case ShowVDiff(d1: Double, d2: Double) =>
        case FocusOn(s: TargetRegion) =>
          println(s"FocusOn ${s}")

        case VRuler(s: Double) =>
          // println(s"v-rule! ${s} scaled: ${scaleY(s)}, inplace = ${scaleY(s) - canvasY}}")

        case HRuler(s: Double) =>
          // println(s"h-rule! ${s} scaled: ${scaleY(s)}, inplace = ${scaleY(s) - canvasY}}")

          // val r = fabric.Rect()
          // r.left = 0
          // r.top =  transformY(s)
          // r.width = canvasW+canvasBorder
          // r.height = 1
          // r.stroke      = "red"
          // r.strokeWidth = 1
          // r.fill        = "rgb(100, 30, 52)"
          // r.opacity = 0.2

          // // setGradient(r,
          // //   "fill",
          // //   "linear",
          // //   (0, 0),
          // //   (50, 15),
          // //   js.Dynamic.literal(
          // //     "0" -> "#222",
          // //     "1" -> "#888"
          // //   )
          // // )

          // fabricCanvas.add(r)

        case Message(s: String) =>
          jQuery("#messages").append(s)

        case a:All =>
          currRGB = nextRGB()

          classNum += 1
          classStack.push(s"c${classNum}")

          _run(a.ts)

          classStack.pop()

        case a:Link =>
          classNum += 1
          classStack.push(s"c${classNum}")

          _run(a.ts)

          classStack.pop()


      }})
    }

    _run(traces)

    fabricCanvas.renderAll()
    fabricCanvas.renderOnAddRemove = true

  }

}

// case SetViewport(b: LTBounds) =>
// put a border around the canvas
// put message area below canvas
// set total width/height
// canvasX = scaleX(b.x).toInt
// canvasY = scaleY(b.y).toInt
// canvasW = scaleX(b.width).toInt
// canvasH = scaleY(b.height).toInt

// fabricCanvas.setWidth(canvasW+canvasBorder*2+1)
// fabricCanvas.setHeight(canvasH+canvasBorder*2+1)

// addShape(LTBounds(
//   canvasX, canvasY, canvasW, canvasH
// ), "black")
// // fabricCanvas.setBackgroundColor("green", () => {})

// println("b = " + translate(b))
