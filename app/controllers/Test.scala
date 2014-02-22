package controllers

import play.api.mvc._
import service.ontologyFetcher.OntologyFetcher
import scala.concurrent.Future
import common.ExecutionContexts.fastOps
import models.{OntologyTriple, DisplayableElement, SearchResult}
import service.ontologySearch.Search
import org.openrdf.rio.{RDFFormat, Rio, RDFWriter}
import java.io.{PipedOutputStream, PipedInputStream}
import play.api.libs.iteratee.Enumerator
import org.openrdf.model.Statement
import org.openrdf.model.impl.{LiteralImpl, URIImpl, StatementImpl}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.Logger

object Test extends Controller {

  def swoogle(keyword: String) = Action.async {
    if(keyword.length < 1) {
      Future.successful(BadRequest)
    } else {
      OntologyFetcher.SwoogleFetcher.search(keyword).map {
        result => Ok(result.toString)
      }
    }
  }

  def find(keyword: String) = Action.async {
    Search.findElementsByKeyword(keyword).map {
      r =>
        implicit val iDisplayableElement = Json.writes[DisplayableElement]
        implicit val iSearchResult = Json.writes[SearchResult]
        Ok(Json.toJson(r))
    }
  }

  implicit val searchObjectRead: Reads[(List[String], String, Int)] =
    {
      (__ \ "elements").read[List[String]] and
      (__ \ "properties" \ "format").read[String] and
      (__ \ "properties" \ "degree").read[Int](min(0) or max(5))
    }.tupled
  def export() = Action(parse.json) {
    request =>
      request.body.validate[(List[String], String, Int)].map {
        case (elements: List[String], format: String, degree: Int) =>

          val formatObj = Option(RDFFormat.valueOf(format))

          val in = new PipedInputStream()
          val out = new PipedOutputStream(in)

          def isBlankNode(uri: String) = uri.indexOf(':') < 0

          OntologyTriple.getRecursive(elements, degree)(OntologyTriple.getTriplesThatIncludes){
            x: OntologyTriple =>
              Set(x.subject :: x.predicate :: (if(!x.isObjectData) { List(x.objekt) } else { Nil }))
          }.map {
            triples =>
              val writer: RDFWriter = Rio.createWriter(formatObj.getOrElse(RDFFormat.RDFXML), out)
              writer.startRDF()

              triples.foreach {
                triple =>
                  if(!isBlankNode(triple.subject) && (triple.isObjectData || !isBlankNode(triple.objekt))) {

                    val s: Statement = new StatementImpl(
                      new URIImpl(triple.subject),
                      new URIImpl(triple.predicate),
                      if(triple.isObjectData) {
                        new LiteralImpl(triple.objekt)
                      } else {
                        new URIImpl(triple.objekt)
                      }
                    )
                    writer.handleStatement(s)

                  }
              }

              writer.endRDF()

              out.close()
          } recover {
            case e:Throwable =>
              Logger.error("Failed exporting ontology. Reason: " + e)
              out.close()
          }

          val dataContent: Enumerator[Array[Byte]] = Enumerator.fromStream(in)
          Ok.chunked(dataContent)

    }.recoverTotal {
      e => BadRequest("Detected error:" + JsError.toFlatJson(e))
    }
  }
}
