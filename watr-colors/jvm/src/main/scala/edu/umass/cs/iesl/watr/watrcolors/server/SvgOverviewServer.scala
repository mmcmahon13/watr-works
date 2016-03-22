package edu.umass.cs.iesl.watr
package watrcolors
package server

import better.files._

import scala.collection.mutable
import ext.CermineBoundingBoxes
import watrmarks._

class SvgOverviewServer(
  config: PdfCorpusConfig
) extends SvgOverviewApi  {

  val svgRepoPath = config.rootDirectory

  def createView(svgFilename: String): List[HtmlUpdate] = {
    List(
      HtmlReplaceInner("#main", new html.SvgOverviewView(config).init(svgFilename).toString)
    )
  }

  def getCermineOverlay(corpusEntryId: String): List[BBox] = {
    val corpus = Corpus(config)
    val maybeOverlays = corpus
      .entry(corpusEntryId)
      .getArtifact("cermine-overlays.svg")
      .asReader
      .map({ r =>
        CermineBoundingBoxes.loadSpatialIndices(r)
      })

    val overlays = maybeOverlays
      .getOrElse(sys.error("getCermineOverlay: error loading spatialindex"))

    val combinedOverlay = concatVertical(overlays)

    val asBBoxes = combinedOverlay.getBoundingBoxes.map({ bb =>
      val (xmin, xmax) = bb.minMaxPairs(0)
      val (ymin, ymax) = bb.minMaxPairs(1)

      BBox(xmin, ymin, xmax-xmin, ymax-ymin)
    })

    asBBoxes.toList
  }


  def concatVertical(pages: Seq[PageSpatialInfo]): PageSpatialInfo = {
    ???
  }

  // Create overly w/mozilla pdf.js info displayed, to examine/debug layout info as generated by pdf->svg extraction
  def getCharLevelOverlay(svgFilename: String): List[BBox] = {
    import edu.umass.cs.iesl.watr.watrmarks.Matrix
    import edu.umass.cs.iesl.watr.watrmarks.dom.Transformable
    import watrmarks.StandardLabels
    import java.io.InputStreamReader
    import watrmarks.dom


    val svg = File(svgRepoPath, svgFilename)

    println(s"server loading file ${svg.path}")

    val overlays = mutable.ArrayBuffer[BBox]()

    svg.inputStream.map {is =>
      val doc = time("read watr dom") {
        dom.readWatrDom(
          new InputStreamReader(is),
          StandardLabels.bioDict
        )
      }

      val tspans = time("drop/take") {
        doc.toDomCursor.unfoldTSpansCursors.drop(200).take(250)
      }

      tspans.foreach({ domCursor =>
         // get all transforms leading to this tspan

        val transforms = time("get transforms"){domCursor.loc
          .path.reverse.toList
          .flatMap{ _ match {
            case t: Transformable => t.transforms
            case _  => List()
          }}}

        val tspan = domCursor.getLabel.asInstanceOf[dom.TSpan]

        val mFinal = time("matrix mult"){ transforms.foldLeft(Matrix.idMatrix)({
          case (acc, e) =>
            acc.multiply(e.toMatrix)
        })}


        time("all bbox computations") {
          tspan.textXYOffsets.foreach { offsets =>
            time("1 bbox") {
              val y = offsets.ys(0)
              val ff = tspan.fontFamily
                (offsets.xs :+ offsets.endX).sliding(2).foreach { case List(x, x1) =>
                  val tvec = mFinal.transform(watrmarks.Vector(x, y))
                  val tvec2 = mFinal.transform(watrmarks.Vector(x1, y))
                  val bbox = BBox(
                    x = tvec.x,
                    y = tvec.y,
                    width = tvec2.x - tvec.x,
                    height = -5.0
                      // info = Some(CharInfo(ff))
                  )
                  overlays.append(bbox)
                }
            }
          }
        }
      })
    }
    overlays.toList
  }

}
