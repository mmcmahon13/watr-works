// package edu.umass.cs.iesl.watr

// package watrmarks

// import java.io.Reader
// import javax.xml.stream.XMLInputFactory
// import javax.xml.stream.events._

// package object dom {

//   import javax.xml.namespace.QName
//   import scalaz.{Show, TreeLoc, Tree}

//   implicit val showElementTree = Show.shows[WatrElement](_.toString())

//   def maybeAttrValue(e: StartElement, s:String): Option[String] = {
//     val maybeAttr = e.getAttributeByName(QName.valueOf(s))
//     if (maybeAttr!=null) Some(maybeAttr.getValue())
//     else None
//   }

//   def attrValue(e: StartElement, s:String): String = {
//     e.getAttributeByName(QName.valueOf(s)).getValue()
//   }

//   def getTransforms(e: StartElement): List[Transform] = {
//     transformParser.parse(maybeAttrValue(e, "transform").getOrElse(""))
//       .left.map(err => sys.error(s"parsing transform='${attrValue(e, "transform")}': ${err}"))
//       .right.get
//   }

//   def digits(s: String): Double = {
//     val ds = """((?:\d+)(?:\.\d+)?)(px)?""".r
//     val ds(d, _) = s
//     d.toDouble
//   }

//   def getXs(e: StartElement): Option[List[Double]] = { maybeAttrValue(e, "x").map(_.split(" ").map(_.toDouble).toList) }
//   def getEndX(e: StartElement): Option[Double]     = { maybeAttrValue(e, "endX").map(_.toDouble) }
//   def getY(e: StartElement): Double                = { attrValue(e, "y").toDouble }
//   def getFontSize(e: StartElement): String         = { maybeAttrValue(e, "font-size").getOrElse("0") }
//   def getFontFamily(e: StartElement): String       = { maybeAttrValue(e, "font-family").getOrElse("") }
//   def getBioBrick(e: StartElement): Option[String] = { maybeAttrValue(e, "bio") }
//   def getWidth(e: StartElement): Double            = { digits(attrValue(e, "width")) }
//   def getHeight(e: StartElement): Double           = { digits(attrValue(e, "height")) }
//   def getViewBox(e: StartElement): ViewBox = {
//     val vs = attrValue(e, "viewBox")
//       .split(" ")
//       .map(digits(_))
//     ViewBox(vs(0), vs(1), vs(2), vs(3))
//   }

//   import scala.collection.mutable

//   def readWatrDom(ins: Reader, bioDict: LabelDictionary): WatrDom = {
//     val factory = XMLInputFactory.newInstance();
//     val reader = factory.createXMLEventReader(ins);

//     var accum: TreeLoc[WatrElement] = null

//     def accAppend(e: WatrElement): Unit = {
//       accum = accum.insertDownLast(Tree.leaf(e))
//     }

//     def accPop(): Unit = {
//       // if (accum.getLabel == NullElement) {
//       //   accum
//       // }
//       accum = accum.parent.get
//     }

//     var annotations = mutable.ArrayBuffer[Annotation]()
//     var collectingAnnotations = false


//     while (reader.hasNext()) {
//       val event = reader.nextEvent();

//       event match {
//         case elem: Namespace =>
//         case attribute: Attribute =>
//           val name = attribute.getName();
//           val value = attribute.getValue();
//         case elem: StartDocument =>
//           val n: WatrElement = Document(bioDict)

//           accum = Tree.leaf(n).loc

//         case elem: EndDocument =>

//         case elem: StartElement =>
//           // if (accum.path.length == 2) {
//           //   println(s"StartElement: ${elem.getName()} stack size = ${accum.path.length}")
//           // }
//           // println(s"Start Element: ${elem.getName}, prefix: ${elem.getName().getPrefix()}, local: ${elem.getName().getLocalPart()}")

//           elem.getName.getLocalPart.toLowerCase match {
//             case "svg"   =>
//               val n = Svg(
//                 width=getWidth(elem),
//                 height=getHeight(elem),
//                 viewBox=getViewBox(elem),
//                 getTransforms(elem)
//               )

//               accAppend(n)

//             case "g"     =>
//               if (collectingAnnotations) {
//                 val labelName =  elem.getAttributeByName(new QName("label-name")).getValue
//                 val labelValue =  elem.getAttributeByName(new QName("label-value")).getValue
//                 val label = bioDict(labelName)
//                 // println(s"""adding annotation ${labelName} = ${labelValue}""")

//                 annotations.append(Annotation(
//                   elem.getAttributeByName(new QName("id")).getValue,
//                   label,
//                   List()
//                 ))

//                 accAppend(NullElement)
//               } else {
//                 val n = Grp(
//                   getTransforms(elem)
//                 )
//                 accAppend(n)
//               }

//             case "defs"  =>
//               val id = elem.getAttributeByName(new QName("id"))
//               if (id != null && id.getValue == "annotation-boxes") {
//                 // println("starting annots")
//                 collectingAnnotations = true
//                 accAppend(NullElement)
//               } else {
//                 accAppend(Defs())
//               }

//             case "text"  =>
//               val n =  Text(getTransforms(elem))
//               accAppend(n)

//             case "path"  =>
//               val n =  Path(getTransforms(elem))
//               accAppend(n)

//             case "annotation-links"  =>
//               accAppend(NullElement)
//             case "citation-reference-link"  =>
//               accAppend(NullElement)
//             case "use"  =>
//               accAppend(NullElement)
//             case "rect"  =>
//               // println("maybe annotation?")
//               if (collectingAnnotations) {
//                 // println("... yes!")
//                 val ann = annotations.remove(annotations.length-1)
//                 annotations.append(ann.copy(
//                     bboxes = ann.bboxes :+ Rect(
//                       attrValue(elem, "x").toDouble,
//                       attrValue(elem, "y").toDouble,
//                       attrValue(elem, "width").toDouble,
//                       attrValue(elem, "height").toDouble
//                     )
//                   )
//                 )
//               }

//               accAppend(NullElement)
//             case "image"  =>
//               accAppend(NullElement)
//             case "mask"  =>
//               accAppend(NullElement)

//             case "tspan" =>

//               val _y = getY(elem)

//               val offs = TextXYOffsets(
//                 xs=getXs(elem).get,
//                 // endX=_endx,
//                 ys=List(_y)
//               )

//               val n = TSpanInit(
//                 "",
//                 getTransforms(elem),
//                 Some(offs),
//                 getFontSize(elem),
//                 getFontFamily(elem)
//               )
//               accAppend(n)

//             case "style"
//                | "clippath" =>
//               accAppend(NullElement)
//             case _ =>
//               sys.error(s"no case match for StartElement: ${elem}")

//           }


//         case elem: EndElement =>
//           elem.getName.getLocalPart.toLowerCase match {
//             case "defs" if collectingAnnotations  =>
//               collectingAnnotations = false

//             case "tspan"   =>
//               val init = accum.getLabel.asInstanceOf[TSpanInit]
//               val rootDocument = accum.root.getLabel.asInstanceOf[Document]

//               lazy val fontInfo =  FontInfo(init.fontFamily, init.fontSize)

//               def fonts: List[FontInfo] = List.fill(init.text.length)(fontInfo)


//               val tspan = TSpan(
//                 init.text,
//                 init.textXYOffsets,
//                 init.fontSize,
//                 init.fontFamily,
//                 rootDocument
//               )

//               accum = accum.modifyLabel { _ => tspan }

//               println("tree to date")
//               println(accum.toTree.drawTree)

//             case _   =>
//           }
//           // println(s"EndElement: ${elem}, accum.parents=${accum.parents}")
//           accPop()

//         case elem: EntityReference =>
//         case elem: Characters =>

//               accum.getLabel match {
//                 case t: TSpanInit =>
//                   accum = accum.modifyLabel { _ =>
//                     t.copy(text = t.text+elem.getData())
//                   }
//                 case _ =>
//                   // sys.error(s"xml text found outside of tspan element: '${elem.getData()}'")
//               }
//           }


//     }

//     // println(s"annotations: ${annotations.toList}")
//     val tree = accum.toTree

//     WatrDom(accum.toTree, annotations.toList)
//   }



// }
