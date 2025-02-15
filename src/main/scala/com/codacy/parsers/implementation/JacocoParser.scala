package com.codacy.parsers.implementation

import java.io.File

import com.codacy.api._
import com.codacy.parsers.CoverageParser
import com.codacy.parsers.util.{TextUtils, XMLoader}

import scala.util.{Failure, Success, Try}
import scala.xml.{Node, NodeSeq}

private case class LineCoverage(missedInstructions: Int, coveredInstructions: Int)

object JacocoParser extends CoverageParser {

  override val name: String = "Jacoco"

  def parse(projectRoot: File, reportFile: File): Either[String, CoverageReport] = {
    val report = (Try(XMLoader.loadFile(reportFile)) match {
      case Success(xml) if (xml \\ "report").nonEmpty =>
        Right(xml \\ "report")

      case Success(_) =>
        Left("Invalid report. Could not find top level <report> tag.")

      case Failure(ex) =>
        Left(s"Unparseable report. ${ex.getMessage}")
    })

    report.right.flatMap(parse(projectRoot, _))
  }

  private def parse(projectRoot: File, report: NodeSeq): Either[String, CoverageReport] = {
    val projectRootStr: String = TextUtils.sanitiseFilename(projectRoot.getAbsolutePath)
    totalPercentage(report).right.map { total =>
      val filesCoverage = for {
        pkg <- report \\ "package"
        packageName = (pkg \ "@name").text
        sourcefile <- pkg \\ "sourcefile"
      } yield {
        val filename =
          TextUtils
            .sanitiseFilename(s"$packageName/${(sourcefile \ "@name").text}")
            .stripPrefix(projectRootStr)
            .stripPrefix("/")
        lineCoverage(filename, sourcefile)
      }

      CoverageReport(total, filesCoverage)
    }
  }

  private def totalPercentage(report: NodeSeq): Either[String, Int] = {
    (report \\ "report" \ "counter")
      .collectFirst {
        case counter if (counter \ "@type").text == "LINE" =>
          val covered = TextUtils.asFloat((counter \ "@covered").text)
          val missed = TextUtils.asFloat((counter \ "@missed").text)
          Right(((covered / (covered + missed)) * 100).toInt)
      }
      .getOrElse {
        Left("Could not retrieve total percentage of coverage.")
      }
  }

  private def lineCoverage(filename: String, fileNode: Node): CoverageFileReport = {
    val lineHit = (fileNode \ "counter").collect {
      case counter if (counter \ "@type").text == "LINE" =>
        val covered = TextUtils.asFloat((counter \ "@covered").text)
        val missed = TextUtils.asFloat((counter \ "@missed").text)
        ((covered / (covered + missed)) * 100).toInt
    }

    val fileHit = if (lineHit.sum > 0) { lineHit.sum / lineHit.length } else 0

    val lineHitMap: Map[Int, Int] = (fileNode \\ "line")
      .map { line =>
        (line \ "@nr").text.toInt -> LineCoverage((line \ "@mi").text.toInt, (line \ "@ci").text.toInt)
      }
      .collect {
        case (key, lineCoverage) if lineCoverage.missedInstructions + lineCoverage.coveredInstructions > 0 =>
          key -> (if (lineCoverage.coveredInstructions > 0) 1 else 0)
      }(collection.breakOut)

    CoverageFileReport(filename, fileHit, lineHitMap)
  }

}
