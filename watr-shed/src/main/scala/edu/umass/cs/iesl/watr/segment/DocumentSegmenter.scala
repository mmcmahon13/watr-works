package edu.umass.cs.iesl.watr
package segment


import java.io.InputStream
import watrmarks._

import scalaz.@@
// import pl.edu.icm.cermine.tools.Histogram
// import pl.edu.icm.cermine.tools.DisjointSets
import Bounds._
import Component._
import scala.collection.JavaConversions._
import scala.collection.mutable
import TagUtils._
// import com.softwaremill.debug.DebugConsole._

case class LineDimensionBins(
  page: Int@@PageID,
  // Seq[(width, widthFrequency), Seq[lines w/width]]
  widthBin: Seq[((Double, Double), Seq[ConnectedComponents])],
  unBinned: Seq[ConnectedComponents]
)

case class PageSegAccumulator(
  pageLines: Seq[Seq[ConnectedComponents]],
  commonLineDimensions: Seq[Point] = Seq(),
  lineDimensionBins: Seq[LineDimensionBins] = Seq()
)

trait DocumentUtils {


  def approxSortYX(charBoxes: Seq[CharBox]): Seq[CharBox] = {
    charBoxes.sortBy({ c =>
      (c.bbox.top, c.bbox.left)
    })
  }

  def squishb(charBoxes: Seq[CharBox]): String = {
    approxSortYX(charBoxes)
      .map({ cbox => cbox.prettyPrint })
      .mkString
  }

  def squishc(charBoxes: Seq[CharComponent]): String = {
    squishb(charBoxes.map(_.component))
  }
}

object DocumentSegmenter extends DocumentUtils {

  def pairwiseSpaces(cs: Seq[CharBox]): Seq[Double] = {
    val cpairs = cs.sliding(2).toList

    val dists = cpairs.map({
      case Seq(c1, c2)  => c2.bbox.left - c1.bbox.right
      case _  => 0d
    })

    dists :+ 0d
  }

  def approximateLineBins(charBoxes: Seq[CharBox]): Seq[(LTBounds, Seq[CharBox])] = {
    val sortedYPage = charBoxes
      .groupBy(_.bbox.bottom.pp)
      .toSeq
      .sortBy(_._1.toDouble)

    val sortedXY = sortedYPage
      .map({case (topY, charBoxes) =>
        val sortedXLine = charBoxes
          .sortBy(_.bbox.left)
        (charBoxesBounds(sortedXLine), sortedXLine)
      })
    sortedXY
  }

  def compareDouble(d1: Double, d2: Double, precision: Double): Int = {
    if (d1.isNaN() || d2.isNaN()) {
      d1.compareTo(d2)
    } else {
      val p:Double = if (precision == 0) 0 else 1

      val i1: Long = math.round(d1 / p);
      val i2: Long = math.round(d2 / p);
      i1.compareTo(i2)
    }
  }

  def compareDoubleWithTolerance(d1: Double, d2: Double, tolerance: Double): Int = {
    if (math.abs(d1 - d2) < tolerance) 0
    else if (d1 < d2) -1
    else 1
  }

  def filterAngle(direction: Double, tolerance: Double): (Double) => Boolean = {
    val filter = angleFilter(direction: Double, tolerance: Double)
      (angle: Double) => filter.matches(angle)
  }

  def angleFilter(direction: Double, tolerance: Double): AngleFilter = {
    val t2 = tolerance / 2
    AngleFilter(direction - t2, direction + t2)
  }

  def histogram(min: Double, max: Double, resolution: Double): Histogram = {
    new Histogram(min, max, resolution)
  }

  def histogram(values: Seq[Double], resolution: Double): Histogram = {
    Histogram.fromValues(values, resolution)
  }

  // sealed trait Frequency

  def getMostFrequentValues(in: Seq[Double], resolution: Double): Seq[(Double, Double)] = {
    val hist = histogram(in, resolution)
    hist.iterator.toSeq
      .sortBy(_.getFrequency)
      .reverse
      .takeWhile(_.getFrequency > 0)
      .map{b=>(b.getValue, b.getFrequency)}
  }

  def createSegmenter(pdfins: InputStream): DocumentSegmenter = {
    val chars = extract.CermineExtractor.extractChars(pdfins)
    val zoneIndex = ZoneIndexer.loadSpatialIndices(chars)
    new DocumentSegmenter(zoneIndex)
  }
  def createSegmenter(pagedefs: Seq[(PageChars, PageGeometry)]): DocumentSegmenter = {
    val zoneIndex = ZoneIndexer.loadSpatialIndices(pagedefs)
    new DocumentSegmenter(zoneIndex)
  }



  def init(pagedefs: Seq[(PageChars, PageGeometry)]): (DocumentSegmenter, PageSegAccumulator) = {
    val zoneIndex = ZoneIndexer.loadSpatialIndices(pagedefs)

    val docstrum = new DocumentSegmenter(zoneIndex)

    val allPageLines = for {
      pageId <- docstrum.pages.getPages
    } yield {
      docstrum.determineLines(pageId, docstrum.pages.getComponents(pageId))
    }

    val accum = PageSegAccumulator(allPageLines, Seq())
    // get document-wide stats
    val accum2 = docstrum.getDocumentWideStats(accum)
    (docstrum, accum2)
  }

  import TB._
  def segmentPages(pagedefs: Seq[(PageChars, PageGeometry)]): String = { // Seq[ConnectedComponents]
    val (docstrum, accum) = init(pagedefs)

    val pageZones = for {
      pageId <- docstrum.pages.getPages
    } yield {
      println(s"segmenting page ${pageId}")
      docstrum.determineZones(pageId, accum)
    }

    implicit val initState = Option(CCRenderState(
      numOfPages = pagedefs.length,
      startingPage = PageID(0)
    ))


    val pageBoxes = pageZones.zipWithIndex.map{ case (zones, pagenum) =>
      val pageZones = zones.toList.map({ zone =>
        vjoinTrailSep(left, ",")(renderConnectedComponents(zone):_*)
      })

      initState.foreach(_.advancePage())

      (s"""|{"page": ${pagenum},
           | "blocks": [
           |  ${ indent(8)(punctuateV(left, ",", pageZones)) }
           | ]}""".stripMargin.box)
    }

    val tokenDict = initState.map { state =>
      val tokLines = state.tokens
        .map({case (pg, tok, bb) => s"[${tok},[${pg}, ${bb.compactPrint}]]".box })
        .grouped(10)
        .map(group => hjoin(sep=",")(group:_*))
        .toList

      indent()(vjoinTrailSep(left, ",")(tokLines:_*))
    } getOrElse nullBox

    val allPages = punctuateV(left, ",", pageBoxes)

    (s"""|
         |{ "pages": [
         |    ${indent(4)(allPages)}
         |   ],
         |   "ids": [
         |     ${indent()(tokenDict)}
         |   ]}
         |""".stripMargin)


  }
}

class DocumentSegmenter(
  val pages: ZoneIndexer
) {
  import DocumentSegmenter._

  val LB = StandardLabels

  var docOrientation: Double = 0d

  private def findNeighbors(pageId: Int@@PageID, qbox: CharBox): Seq[CharBox] = {
    pages.nearestNChars(pageId, qbox, 12, 15.0f)
      .filterNot(_.isWonky)
  }


  val withinAngle = filterAngle(docOrientation, math.Pi / 3)

  def fillInMissingChars(pageId: Int@@PageID, charBoxes: Seq[CharBox]): Seq[CharBox] = {
    if (charBoxes.isEmpty) Seq()
    else {
      val ids = charBoxes.map(_.id.unwrap)
      val minId = ids.min
      val maxId = ids.max

      val missingIds = (ids.min to ids.max) diff ids
      val missingChars = missingIds.map(id => pages.getComponent(pageId, CharID(id)))

      // TODO check missing chars for overlap w/lineChars
      val completeLine = (charBoxes ++ missingChars).sortBy(_.bbox.left)

      // // check for split in line
      // println(s"""${charBoxes.map(_.bestGuessChar).mkString}""")
      // println(s"""  +: ${missingChars.map(_.bestGuessChar).mkString}""")
      // println(s"""  =: ${completeLine.map(_.bestGuessChar).mkString}""")
      completeLine
    }
  }

  def determineLines(
    pageId: Int@@PageID,
    components: Seq[CharBox]
  ): Seq[ConnectedComponents] = {

    val lineSets = new DisjointSets[CharBox](components)

    // line-bin coarse segmentation
    val lineBins = approximateLineBins(components)

    /// Split run-on lines (crossing columns, e.g.,), by looking for jumps in the char.id
    val splitAndFilledLines = for {
      (lineBounds, lineChars) <- lineBins
      if !lineChars.isEmpty
    } {

      def spanloop(chars: Seq[CharBox]): Seq[Int] = {
        if (chars.isEmpty) Seq()
        else {
          val (line1, line2) = chars
            .sliding(2).span({
              case Seq(ch1, ch2) => ch2.id.unwrap - ch1.id.unwrap < 10
              case Seq(_)     => true
            })
          val len = line1.length
          len +: spanloop(chars.drop(len+1))
        }
      }

      var totalIndex: Int = 0

      val splitLines = spanloop(lineChars)
        .foreach({ index =>
          val m = fillInMissingChars(pageId, lineChars.drop(totalIndex).take(index+1))

          m.tail.foreach({char =>
            if (!char.isWonky) {
              lineSets.union(char, m.head)
            }
          })
          totalIndex += index+1;
        })
    }

    val lines = lineSets.iterator().toSeq.map{
      _.toSeq.sortBy(c => (c.bbox.left, c.bbox.top)).map(new CharComponent(_, docOrientation))
    }

    lines
      .map{ Component(_, LB.Line) }
      .sortBy(b => (b.bounds.top, b.bounds.left))
  }




  /** Groups components into text lines. */
  def determineLinesNNSearchVersion(
    pageId: Int@@PageID,
    components: Seq[CharBox]
  ): Seq[ConnectedComponents] = {

    val readableComponents = components.filterNot(_.isWonky)
    val sets = new DisjointSets[CharBox](readableComponents)


    for { component <- readableComponents.sortBy(_.bbox.left) } {
      val searchLog = mutable.ArrayBuffer[TB.Box]()
      findNeighbors(pageId, component)
        .foreach({neighbor =>
          val angle = component.bbox.toCenterPoint.angleTo(neighbor.bbox.toCenterPoint)

          val maxWidth = math.max(neighbor.bbox.width, component.bbox.width)
          val dx = neighbor.bbox.toCenterPoint.hdist(component.bbox.toCenterPoint)
          val dy = neighbor.bbox.toCenterPoint.vdist(component.bbox.toCenterPoint)
          val dist = neighbor.bbox.toCenterPoint.dist(component.bbox.toCenterPoint)

          val eastWestDist = component.bbox.toEasternPoint.dist(
            neighbor.bbox.toWesternPoint
          )

          var joinWith = false
          // val maxWidthMult = 2.7
          val maxHWidthMult = 2.7
          val maxAngleWidthMult = 1.0

          if (angle.eqFuzzy(0.01)(0.0) && eastWestDist < maxWidth*maxHWidthMult) {
            sets.union(component, neighbor);
            joinWith = true
          } else if (withinAngle(angle) && dist < maxWidth*maxAngleWidthMult) {
            sets.union(component, neighbor);
            joinWith = true
          }

          { import TB._
            val topsNotEq = component.bbox.top.pp != neighbor.bbox.top.pp
            val angleNotZero = angle.pp != "0.00"
            searchLog.append(
              s"   '${neighbor.char}' ${neighbor.wonkyCharCode} #${neighbor.id} ${neighbor.bbox.prettyPrint}".box %
                s"""       ${ if (joinWith && topsNotEq) "!join" else if (joinWith) "join" else "" }""" %
                s"       angle:${angle.pp} dx:${dx.pp} dy:${dy.pp}" %
                s"       dist:${dist.pp} e/wi-dist:${eastWestDist.pp}" %
                s"       maxwidth= ${maxWidth} withinAngle=${withinAngle(angle)}"
            )
          }
        })

      // { import TB._
      //   println(
      //     s"'${component.char} #${component.id} ${component.bbox.prettyPrint}".box %
      //     vcat(top)(searchLog.toList)
      //   )
      // }

    }

    val lines = sets.iterator().toSeq.map{
      _.toSeq.sortBy(c => (c.bbox.left, c.bbox.top)).map(new CharComponent(_, docOrientation))
    }


    lines
      .map{ Component(_, LB.Line) }
      .sortBy(b => (b.bounds.top, b.bounds.left))
  }


  def charBasedPageBounds(
    pageId: Int@@PageID
  ): LTBounds = {
    val allBboxes = pages.getComponents(pageId).map(_.bbox)

    if (allBboxes.isEmpty) LTBounds(0, 0, 0, 0) else {
      val minX = allBboxes.map(_.left).min
      val minY = allBboxes.map(_.top).min
      val maxX = allBboxes.map(_.right).max
      val maxY = allBboxes.map(_.bottom).max

      LTBounds(
        minX, minY,
        maxX-minX,
        maxY-minY
      )
    }
  }


  def lineWidthMatches(line: ConnectedComponents, width: Double): Boolean  = {
    line.determineNormalTextBounds.width.eqFuzzy(0.5d)(width)
  }
  def lineHeightMatches(line: ConnectedComponents, height: Double): Boolean  = {
    line.determineNormalTextBounds.height.eqFuzzy(0.5d)(height)
  }

  def lineDimensionsMatch(line: ConnectedComponents, hw: Point): Boolean = {
    lineWidthMatches(line, hw.x) && lineHeightMatches(line, hw.y)
  }

  def debugFormatLine(cc: ConnectedComponents): String = {
    import TB._
    val line = renderConnectedComponents(cc.tokenizeLine())
    s"${cc.bounds.prettyPrint}, r:${cc.bounds.right.pp} b:${cc.bounds.bottom} ctr:${cc.bounds.toCenterPoint.prettyPrint} > ${hsep(line)}"
  }

  def printPageLineBins(bin: LineDimensionBins, indent: Int=0): Unit = {
    bin.widthBin.foreach{ case (width, lines) =>
      println(" "*indent + s"Lines within width ${width}")
      lines.sortBy(_.bounds.top).foreach{ line =>
        println("  "*indent + s"w:${line.bounds.width.pp}, h:${line.bounds.height.pp} ${line.bounds.prettyPrint} > ${line.tokenizeLine().toText}")
      }
    }
  }

  def printCommonLineBins(lineBins: Seq[LineDimensionBins]): Unit = {
    lineBins.zipWithIndex.toList.foreach{ case (bin, pnum) =>
      println(s"Page $pnum")
      printPageLineBins(bin, 2)
    }
  }


  def findMostFrequentLineDimensions(psegAccum: PageSegAccumulator):  PageSegAccumulator = {
    val allDocumentWidths = for {
      p <- psegAccum.pageLines;
      l <- p
    } yield l.bounds.width

    val topWidths = getMostFrequentValues(allDocumentWidths, 0.2d).toList
    val topNWidths = topWidths.take(7)
    // Common width meaning from largest->smallest:
    //    left/right justified line width
    //    paragraph-starting indented line width
    //    reference width(s), for hanging-indent first line, remaining lines
    //    other l/r justified blocks (abstract, e.g)

    // println(s"""common widths = ${topNWidths.mkString(", ")}""")

    // bin each page by line widths
    val commonLineBins = for {
      (plines, pagenum) <- psegAccum.pageLines.zipWithIndex
    } yield {

      val remainingLines = mutable.ListBuffer[ConnectedComponents](plines:_*)
      val widthBins = mutable.ArrayBuffer[((Double, Double), Seq[ConnectedComponents])]()

      topNWidths.foreach{ case (width, wfreq) =>
        val mws = remainingLines.filter(lineWidthMatches(_, width))
        widthBins.append(((width -> wfreq), mws))
        remainingLines --= mws
      }

      LineDimensionBins(PageID(pagenum), widthBins, remainingLines)
    }
    // printCommonLineBins(commonLineBins)

    psegAccum.copy(
      lineDimensionBins = commonLineBins
    )
  }

  def getDocumentWideStats(psegAccum: PageSegAccumulator): PageSegAccumulator = {
    findMostFrequentLineDimensions(psegAccum)
  }



  def determineZones(
    pageId: Int@@PageID,
    psegAccum: PageSegAccumulator
  ): Seq[ConnectedComponents] = {
    val pageLinesx: Seq[ConnectedComponents] = psegAccum.pageLines(PageID.unwrap(pageId))

    // println("starting wiht lines: ")
    // pageLinesx.foreach { line =>
    //   val pstr = Component.renderConnectedComponents(line)
    //   println(s"""  >> ${pstr}""")
    // }
    // println("--------------")

    val pageBounds = charBasedPageBounds(pageId)
    val pageCenter = pageBounds.toCenterPoint

    // println(s"page center ${pageCenter.prettyPrint}")

    val lineBins = psegAccum.lineDimensionBins.find(_.page == pageId).get

    // println("binned lines for page")
    // lineBins.widthBin.foreach { case ((width, wfreq), binnedLines) =>
    //   println(s"   bin = ${width}, freq = ${wfreq}")
    //   binnedLines.sortBy(_.bounds.top).foreach { bl =>
    //     val pstr = Component.renderConnectedComponents(bl)
    //     println(s"""    >> ${pstr}""")
    //   }
    //   println("--------------")
    // }

    // println("unbinned lines for page")
    // lineBins.unBinned.sortBy(_.bounds.top).foreach { ubl =>
    //   val pstr = Component.renderConnectedComponents(ubl)
    //   println(s"""    >> ${pstr}""")
    // }

    val unusedPageLines = mutable.ArrayBuffer[ConnectedComponents](pageLinesx:_*)
    val usedPageLines = mutable.ArrayBuffer[ConnectedComponents]()

    // starting w/most common width, down to least common..
    val allBlocks = lineBins.widthBin.sortBy(_._1._2).reverse.map {
      case ((mostFrequentWidthDocwide, wfreq), linesWithFreq)
          if linesWithFreq.length > 0 && mostFrequentWidthDocwide > 10.0 =>

        val remainingLinesWithFreq = linesWithFreq.diff(usedPageLines)

        if (remainingLinesWithFreq.length > 0) {


          // println(s" building cols out of lines of width: ${mostFrequentWidthDocwide} w/freq: ${wfreq}")

          // val ((mostFrequentWidthDocwide, wfreq), linesWFreq)  = lineBins.widthBin.sortBy(_._1._2).reverse.head

          // divide page-specific most frequent lines into likely columns:
          val colCenters = getMostFrequentValues(remainingLinesWithFreq.map(_.bounds.toCenterPoint.x) , resolution=0.2d)

          val commonLinesInCols = for {
            (colX, cfreq) <- colCenters
          } yield {
            (colX, remainingLinesWithFreq.filter({ line => line.bounds.toCenterPoint.x.eqFuzzy(0.4)(colX) }))
          }

          val sortedCommonLines = commonLinesInCols
            .sortBy(_._1)
            .map({ case (colX, colLines) =>

              // println("examining lines: ")
              // colLines.foreach { line =>
              //   val pstr = Component.renderConnectedComponents(line)
              //   // println(s"""  >> ${pstr}""")
              //   println(s"""  >> ${line.chars}""")
              // }
              // println("--------------")

              val ySortedLines = colLines.sortBy(_.bounds.top)
              val topLine = ySortedLines.head
              val bottomLine = ySortedLines.last


              def candidateIsBelowBottom(cand: ConnectedComponents) = cand.bounds.top > bottomLine.bounds.top
              def candidateIsBelowTop(cand: ConnectedComponents) = cand.bounds.top > topLine.bounds.top
              def candidateIsAboveBottom(cand: ConnectedComponents) = cand.bounds.top < bottomLine.bounds.top
              def candidateIsAboveTop(cand: ConnectedComponents) = cand.bounds.top < topLine.bounds.top

              def candidateCrossesLineBounds(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
                val slopFactor = 0.31d

                val linex0 = line.bounds.toWesternPoint.x-slopFactor
                val linex1 = line.bounds.toEasternPoint.x+slopFactor
                val candx0 = cand.bounds.toWesternPoint.x
                val candx1 = cand.bounds.toEasternPoint.x
                val candRightInside = linex0 <= candx1 && candx1 <= linex1
                val candLeftOutside = candx0 < linex0
                val candLeftInside = linex0 <= candx0 && candx0 <= linex1
                val candRightOutside = linex1 < candx1

                val crossesLeft = candRightInside && candLeftOutside
                val crossesRight = candLeftInside && candRightOutside

                val candb = debugFormatLine(cand)
                val lineb = debugFormatLine(line)

                // debugReport(
                //   candb,
                //   lineb,
                //   linex0           , // = line.bounds.toWesternPoint.x
                //   linex1           , // = line.bounds.toEasternPoint.x
                //   candx0           , // = cand.bounds.toWesternPoint.x
                //   candx1           , // = cand.bounds.toEasternPoint.x
                //   candRightInside  , // = linex0 <= candx1 && candx1 <= linex1
                //   candLeftOutside  , // = candx0 < linex0
                //   candLeftInside   , // = linex0 <= candx0 && candx0 <= linex1
                //   candRightOutside , // = linex1 > candx1
                //   crossesLeft        , // = candRightInside && candLeftOutside
                //   crossesRight,     // = candLeftInside && candRightOutside
                //   crossesLeft || crossesRight
                // )

                crossesLeft || crossesRight
              }

              def candidateIsStrictlyLeftOfLine(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
                val linex0 = line.bounds.toWesternPoint.x
                val candx1 = cand.bounds.toEasternPoint.x
                candx1 < linex0
              }

              def candidateIsStrictlyRightOfLine(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
                val linex1 = line.bounds.toEasternPoint.x
                val candx0 = cand.bounds.toWesternPoint.x
                candx0 > linex1
              }
              def candidateIsOutsideLineBounds(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
                candidateIsStrictlyLeftOfLine(cand, line) ||
                  candidateIsStrictlyRightOfLine(cand, line)
              }


              // println("now checking for lines above")

              val possibleCand = unusedPageLines
                .diff(ySortedLines)
                .sortBy(_.bounds.top)

              val candidateLinesAbove = possibleCand
                .reverse
                .filter(candidateIsAboveTop(_))
                .filterNot(candidateIsOutsideLineBounds(_, topLine))
                .takeWhile({cc =>
                  val colBreak = candidateCrossesLineBounds(cc, topLine)

                  // println(s"""| checking for above topline:
                  //             | cand: ${debugFormatLine(cc)}
                  //             | top : ${debugFormatLine(topLine)}
                  //             | breaks col bounds ${colBreak}
                  //             |""".stripMargin)
                  !colBreak
                })

              // println(s"found ${candidateLinesAbove.length} lines above")



              // println("now checking for lines below")

              val candidateLinesBelow = possibleCand
                .filter(candidateIsBelowBottom(_))
                .filterNot(candidateIsOutsideLineBounds(_, topLine))
                .takeWhile({cc =>
                  val colBreak = candidateCrossesLineBounds(cc, topLine)
                  // println(s"""| checking for below bottom line:
                  //             | cand: ${debugFormatLine(cc)}
                  //             | top : ${debugFormatLine(topLine)}
                  //             | breaks col bounds ${colBreak}
                  //             |""".stripMargin)
                    !colBreak
                })


              // println(s"found ${candidateLinesBelow.length} lines below")

              val candidateLinesWithin = possibleCand
                .filter(c =>candidateIsAboveBottom(c) && candidateIsBelowTop(c))
                .filterNot(candidateIsOutsideLineBounds(_, topLine))
                .filterNot(candidateCrossesLineBounds(_, topLine))

              // println(s"found ${candidateLinesWithin.length} lines within")

              // val candidateLinesWithin = possibleCand.filter({cc =>
              //   // val fmt=debugFormatLine(cc)
              //   // println(s"""| checking for within ${fmt}
              //   //             | top: ${topLine.bounds.prettyPrint} / r: ${topLine.bounds.right}
              //   //             | bottom: ${bottomLine.bounds.prettyPrint} / r: ${bottomLine.bounds.right}
              //   //             |""".stripMargin)
              //   topLine.bounds.top < cc.bounds.top && cc.bounds.top < bottomLine.bounds.top
              // })

              val debugAboveLines = candidateLinesAbove.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
              val debugWithin = candidateLinesWithin.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
              val debugMiddle = ySortedLines.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
              val debugBelowLines = candidateLinesBelow.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")

              // println(s"Candidates Above\n${debugAboveLines}\n")
              // println(s"Candidates Within\n${debugWithin}\n")
              // println(s"\n\n${debugMiddle}\n\n")
              // println(s"Candidates below\n${debugBelowLines}\n")


              val totalLines =  candidateLinesAbove ++ ySortedLines ++ candidateLinesWithin ++ candidateLinesBelow
              val totalLineSorted = totalLines.sortBy(_.bounds.top)

              unusedPageLines --= totalLineSorted
              usedPageLines ++= totalLineSorted

              Component(totalLineSorted, LB.Block)
            })
          sortedCommonLines
        } else {
          Seq()
        }

      case _ => Seq()
    }

    allBlocks.flatten

  }


  def sortZonesYX(zones: Seq[ConnectedComponents]): Seq[ConnectedComponents]= {

    zones.sortWith({case (cc1, cc2) =>
      val ycmp = compareDouble(cc1.bounds.top, cc2.bounds.top, 0.01)

      val cmp = if (ycmp == 0) {
        compareDouble(cc1.bounds.left, cc2.bounds.left, 0.01)
      } else {
        ycmp
      }

      cmp < 0
    })
  }

  val DEFAULT_ANGLE_HIST_RES : Double = Math.toRadians(0.5);
  val DEFAULT_ANGLE_HIST_SMOOTH_LEN : Double = 0.25 * Math.PI;
  val DEFAULT_ANGLE_HIST_SMOOTH_STDDEV : Double = 0.0625 * Math.PI;
  val angleHistogramResolution = DEFAULT_ANGLE_HIST_RES
  val angleHistogramSmoothingWindowLength: Double = DEFAULT_ANGLE_HIST_SMOOTH_LEN;
  val angleHistogramSmoothingWindowStdDeviation:Double = DEFAULT_ANGLE_HIST_SMOOTH_STDDEV;

  def determineZonesOriginal(
    pageId: Int@@PageID,
    psegAccum: PageSegAccumulator
  ): Seq[ConnectedComponents] = {
    val pageLines: Seq[ConnectedComponents] = psegAccum.pageLines(PageID.unwrap(pageId))
    println("starting wiht lines: ")
    pageLines.foreach { line =>
      val pstr = Component.renderConnectedComponents(line)
      println(s"""  >> ${pstr}""")
    }
    println("--------------")

    val pageBounds = charBasedPageBounds(pageId)
    val pageCenter = pageBounds.toCenterPoint

    // println(s"page center ${pageCenter.prettyPrint}")

    val lineBins = psegAccum.lineDimensionBins.find(_.page == pageId).get

    println("binned lines for page")
    lineBins.widthBin.foreach { case ((width, wfreq), binnedLines) =>
      println(s"   bin = ${width}, freq = ${wfreq}")
      binnedLines.sortBy(_.bounds.top).foreach { bl =>
        val pstr = Component.renderConnectedComponents(bl)
        println(s"""    >> ${pstr}""")
      }
      println("--------------")
    }

    println("unbinned lines for page")
    lineBins.unBinned.sortBy(_.bounds.top).foreach { ubl =>
      val pstr = Component.renderConnectedComponents(ubl)
      println(s"""    >> ${pstr}""")
    }

    val ((mostFrequentWidthDocwide, wfreq), linesWFreq)  = lineBins.widthBin.sortBy(_._1._2).reverse.head

    // divide page-specific most frequent lines into likely columns:
    val colCenters = getMostFrequentValues(linesWFreq.map(_.bounds.toCenterPoint.x) , resolution=0.2d)

    val commonLinesInCols = for {
      (colX, cfreq) <- colCenters
    } yield {
      (colX, linesWFreq.filter({ line => line.bounds.toCenterPoint.x.eqFuzzy(0.4)(colX) }))
    }

    val sortedCommonLines = commonLinesInCols
      .sortBy(_._1)
      .map({ case (colX, colLines) =>
        println("examining lines: ")
        colLines.foreach { line =>
          val pstr = Component.renderConnectedComponents(line)
          println(s"""  >> ${pstr}""")
        }
        println("--------------")

        val ySortedLines = colLines.sortBy(_.bounds.top)
        val topLine = ySortedLines.head
        val bottomLine = ySortedLines.last

        // // val candidateLines = lineBins.unBinned.filter({cc =>

        // val candidateLines = possibleCand.filter({cc =>
        //   val tlx = topLine.bounds.xProjection
        //   val ccx = cc.bounds.xProjection
        //   val topLineOverlaps = tlx.p1.x-0.1 <= ccx.p1.x && ccx.p2.x <= tlx.p2.x+0.1

        //   // val fmt=debugFormatLine(cc)
        //   // println(s"""| checking for candidates
        //   //             |  ${fmt}
        //   //             |   top: ${topLine.bounds.prettyPrint} / r: ${topLine.bounds.right}
        //   //             |   bottom: ${bottomLine.bounds.prettyPrint} / r: ${bottomLine.bounds.right}
        //   //             |
        //   //             |""".stripMargin)

        //   // if (fmt.endsWith("electrode assembly.")) {
        //   //   val tlfmt=debugFormatLine(topLine)
        //   //   println(s"""| comparing candidates
        //   //               |    top: ${tlfmt}
        //   //               |        ${topLine.bounds.prettyPrint} / r: ${topLine.bounds.right}
        //   //               |    ${fmt}
        //   //               |       top: ${cc.bounds.prettyPrint} / r: ${cc.bounds.right}
        //   //               |       bottom: ${cc.bounds.prettyPrint} / r: ${cc.bounds.right}
        //   //               |""".stripMargin)

        //   //   debugLineComponentStats(topLine)
        //   //   println(s"candidate line ccs")
        //   //   debugLineComponentStats(cc)
        //   // }

        //   topLineOverlaps
        // }).sortBy(_.bounds.top)


        def candidateIsBelowBottom(cand: ConnectedComponents) = cand.bounds.top > bottomLine.bounds.top
        def candidateIsBelowTop(cand: ConnectedComponents) = cand.bounds.top > topLine.bounds.top
        def candidateIsAboveBottom(cand: ConnectedComponents) = cand.bounds.top < bottomLine.bounds.top
        def candidateIsAboveTop(cand: ConnectedComponents) = cand.bounds.top < topLine.bounds.top

        def candidateCrossesLineBounds(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
          val slopFactor = 0.1d

          val linex0 = line.bounds.toWesternPoint.x-slopFactor
          val linex1 = line.bounds.toEasternPoint.x+slopFactor
          val candx0 = cand.bounds.toWesternPoint.x
          val candx1 = cand.bounds.toEasternPoint.x
          val candRightInside = linex0 <= candx1 && candx1 <= linex1
          val candLeftOutside = candx0 < linex0
          val candLeftInside = linex0 <= candx0 && candx0 <= linex1
          val candRightOutside = linex1 < candx1

          val crossesLeft = candRightInside && candLeftOutside
          val crossesRight = candLeftInside && candRightOutside

          val candb = debugFormatLine(cand)
          val lineb = debugFormatLine(line)

          // debugReport(
          //   candb,
          //   lineb,
          //   linex0           , // = line.bounds.toWesternPoint.x
          //   linex1           , // = line.bounds.toEasternPoint.x
          //   candx0           , // = cand.bounds.toWesternPoint.x
          //   candx1           , // = cand.bounds.toEasternPoint.x
          //   candRightInside  , // = linex0 <= candx1 && candx1 <= linex1
          //   candLeftOutside  , // = candx0 < linex0
          //   candLeftInside   , // = linex0 <= candx0 && candx0 <= linex1
          //   candRightOutside , // = linex1 > candx1
          //   crossesLeft        , // = candRightInside && candLeftOutside
          //   crossesRight,     // = candLeftInside && candRightOutside
          //   crossesLeft || crossesRight
          // )

          crossesLeft || crossesRight
        }

        def candidateIsStrictlyLeftOfLine(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
          val linex0 = line.bounds.toWesternPoint.x
          val candx1 = cand.bounds.toEasternPoint.x
          candx1 < linex0
        }

        def candidateIsStrictlyRightOfLine(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
          val linex1 = line.bounds.toEasternPoint.x
          val candx0 = cand.bounds.toWesternPoint.x
          candx0 > linex1
        }
        def candidateIsOutsideLineBounds(cand: ConnectedComponents, line: ConnectedComponents): Boolean = {
          candidateIsStrictlyLeftOfLine(cand, line) ||
            candidateIsStrictlyRightOfLine(cand, line)
        }


        // println("now checking for lines above")

        val possibleCand = pageLines
          .diff(ySortedLines)
          .sortBy(_.bounds.top)

        val candidateLinesAbove = possibleCand
          .reverse
          .filter(candidateIsAboveTop(_))
          .filterNot(candidateIsOutsideLineBounds(_, topLine))
          .takeWhile({cc =>
            val colBreak = candidateCrossesLineBounds(cc, topLine)

            // println(s"""| checking for above topline:
            //             | cand: ${debugFormatLine(cc)}
            //             | top : ${debugFormatLine(topLine)}
            //             | breaks col bounds ${colBreak}
            //             |""".stripMargin)
            !colBreak
          })

        // println(s"found ${candidateLinesAbove.length} lines above")



        // println("now checking for lines below")

        val candidateLinesBelow = possibleCand
          .filter(candidateIsBelowBottom(_))
          .filterNot(candidateIsOutsideLineBounds(_, topLine))
          .takeWhile({cc =>
            val colBreak = candidateCrossesLineBounds(cc, topLine)
            // println(s"""| checking for below bottom line:
            //             | cand: ${debugFormatLine(cc)}
            //             | top : ${debugFormatLine(topLine)}
            //             | breaks col bounds ${colBreak}
            //             |""".stripMargin)
            !colBreak
          })


        // println(s"found ${candidateLinesBelow.length} lines below")

        val candidateLinesWithin = possibleCand
          .filter(c =>candidateIsAboveBottom(c) && candidateIsBelowTop(c))
          .filterNot(candidateIsOutsideLineBounds(_, topLine))
          .filterNot(candidateCrossesLineBounds(_, topLine))

        // println(s"found ${candidateLinesWithin.length} lines within")

        // val candidateLinesWithin = possibleCand.filter({cc =>
        //   // val fmt=debugFormatLine(cc)
        //   // println(s"""| checking for within ${fmt}
        //   //             | top: ${topLine.bounds.prettyPrint} / r: ${topLine.bounds.right}
        //   //             | bottom: ${bottomLine.bounds.prettyPrint} / r: ${bottomLine.bounds.right}
        //   //             |""".stripMargin)
        //   topLine.bounds.top < cc.bounds.top && cc.bounds.top < bottomLine.bounds.top
        // })

        val debugAboveLines = candidateLinesAbove.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
        val debugWithin = candidateLinesWithin.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
        val debugMiddle = ySortedLines.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")
        val debugBelowLines = candidateLinesBelow.map({ cc=> renderConnectedComponents(cc) }).mkString("\n")

        // println(s"Candidates Above\n${debugAboveLines}\n")
        // println(s"Candidates Within\n${debugWithin}\n")
        // println(s"\n\n${debugMiddle}\n\n")
        // println(s"Candidates below\n${debugBelowLines}\n")


        // find common v-dist within block, grab lines from above/below within that distance

        // val vDists = ySortedLines.sliding(2).map({
        //   case Seq(c1, c2) => c1.bounds.toCenterPoint.vdist(c2.bounds.toCenterPoint)
        //   case Seq(c1) => 0d
        // })

        // val topVDists = getMostFrequentValues(vDists.toList, 0.5d).toList
        // println(s"""top v-dists ${topVDists.mkString(", ")}""")

        // // pad out the distance slightly
        // val topVDist = topVDists.head._1 + 0.1d

        // // val hitsAbove = candidateLinesAbove.filter({cabove =>
        // //   val vdist = topLine.bounds.toCenterPoint.vdist(cabove.bounds.toCenterPoint)
        // //   println(s"checking vdist=${vdist.pp} for ${cabove.toText}")
        // //   vdist <= topVDist
        // // })
        // var currTopBounds = topLine.bounds
        // val hitsAbove = candidateLinesAbove.reverse.takeWhile({cabove =>
        //   val vdist = cabove.bounds.toCenterPoint.vdist(currTopBounds.toCenterPoint)
        //   println(s"checking vdist=${vdist.pp} for ${cabove.toText}")
        //   currTopBounds = cabove.bounds
        //   vdist <= topVDist*3.0
        // })

        // var currBottomBounds = bottomLine.bounds
        // val hitsBelow = candidateLinesBelow.takeWhile({cbelow =>
        //   val vdist = cbelow.bounds.toCenterPoint.vdist(currBottomBounds.toCenterPoint)
        //   currBottomBounds = cbelow.bounds
        //   vdist <= topVDist*3.0
        // })

        // val totalLine =  hitsAbove ++ ySortedLines ++ candidateLinesWithin ++ hitsBelow
        val totalLines =  candidateLinesAbove ++ ySortedLines ++ candidateLinesWithin ++ candidateLinesBelow
        val totalLineSorted = totalLines.sortBy(_.bounds.top)

        Component(totalLineSorted, LB.Block)
      })

    sortedCommonLines

  }


}
