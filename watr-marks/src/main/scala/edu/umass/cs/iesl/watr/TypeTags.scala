package edu.umass.cs.iesl.watr

import scalaz.Tag
import scalaz.@@

sealed trait ZoneID
sealed trait LabelID
sealed trait RegionID
sealed trait TokenID

sealed trait PageID
sealed trait CharID
sealed trait ComponentID

sealed trait SHA1String

object TagUnwrapping {

}

object TypeTags {
  val SHA1String = Tag.of[SHA1String]

  val ZoneID = Tag.of[ZoneID]
  val RegionID = Tag.of[RegionID]
  val TokenID = Tag.of[TokenID]
  val PageID = Tag.of[PageID]
  val CharID = Tag.of[CharID]
  val ComponentID = Tag.of[ComponentID]
  val LabelID = Tag.of[LabelID]

  implicit class TagOps[A, T](val value: A@@T) extends AnyVal {
    def unwrap: A = Tag.of[T].unwrap(value)
  }

}

import TypeTags._

// trait TypeTagImplicits {
//   // implicit def RegionIDOrdering: Ordering[Int@@RegionID] = Ordering.by(_.unwrap)
// }

trait TypeTagFormats {
  import play.api.libs.json
  import json._

  val ReadPageID: Reads[Int@@PageID]   = __.read[Int].map(i => Tag.of[PageID](i))
  val WritePageID: Writes[Int@@PageID] = Writes[Int@@PageID] { i => JsNumber(i.unwrap) }
  implicit def FormatPageID            = Format(ReadPageID, WritePageID)

  val ReadZoneID: Reads[Int@@ZoneID]   = __.read[Int].map(i => Tag.of[ZoneID](i))
  val WriteZoneID: Writes[Int@@ZoneID] = Writes[Int@@ZoneID] { i => JsNumber(i.unwrap) }
  implicit def FormatZoneID            = Format(ReadZoneID, WriteZoneID)

  val ReadRegionID: Reads[Int@@RegionID]   = __.read[Int].map(i => Tag.of[RegionID](i))
  val WriteRegionID: Writes[Int@@RegionID] = Writes[Int@@RegionID] { i => JsNumber(i.unwrap) }
  implicit def FormatRegionID            = Format(ReadRegionID, WriteRegionID)

  val ReadTokenID: Reads[Int@@TokenID]   = __.read[Int].map(i => Tag.of[TokenID](i))
  val WriteTokenID: Writes[Int@@TokenID] = Writes[Int@@TokenID] { i => JsNumber(i.unwrap) }
  implicit def FormatTokenID            = Format(ReadTokenID, WriteTokenID)

  val ReadCharID: Reads[Int@@CharID]   = __.read[Int].map(i => Tag.of[CharID](i))
  val WriteCharID: Writes[Int@@CharID] = Writes[Int@@CharID] { i => JsNumber(i.unwrap) }
  implicit def FormatCharID            = Format(ReadCharID, WriteCharID)

  val ReadComponentID: Reads[Int@@ComponentID]   = __.read[Int].map(i => Tag.of[ComponentID](i))
  val WriteComponentID: Writes[Int@@ComponentID] = Writes[Int@@ComponentID] { i => JsNumber(i.unwrap) }
  implicit def FormatComponentID            = Format(ReadComponentID, WriteComponentID)

  val ReadChar: Reads[Char]   = __.read[String].map(c => c(0))
  val WriteChar: Writes[Char] = Writes[Char] { c => JsString(c.toString()) }
  implicit def FormatChar     = Format(ReadChar, WriteChar)


  implicit def FormatSHA1String  = Format(
    __.read[String].map(i => Tag.of[SHA1String](i)),
    Writes[String@@SHA1String](i => JsString(i.unwrap))
  )
}
