package controllers

import play.api.mvc._
import play.api.mvc.Controller
import play.api.libs.json._
import play.api.libs.functional.syntax._
import service.ontologyFetcher.OntologyFetcher
import scala.concurrent.Future
import common.ExecutionContexts.fastOps
import service.FetchResult
import service.ontologySearch.Search
import models.{OntologyTriple, SearchResult, DisplayableElement}
import org.openrdf.rio.RDFFormat
import common.{BasicTimer, RDF}

object Application extends Controller {
  implicit val searchObjectRead: Reads[(List[String], Boolean)] =
    {
      (JsPath \ "keywords").read[List[String]] and
      (JsPath \ "offline").read[Boolean]
    }.tupled
  implicit val DisplayableElementWrites = Json.writes[DisplayableElement]
  implicit val SearchResultWrites = Json.writes[SearchResult]
  implicit val fetchResultWrites = Json.writes[FetchResult]

  def index = Action {
    Ok(views.html.main.render())
  }

  def search = Action.async(parse.json) {
    request =>
      request.body.validate[(List[String], Boolean)].map {
        case (keywords, offline) =>
          val timer = new BasicTimer("search|all", keywords.mkString + "|" + offline).start()
          /*++ keywords.map {
            keyword => OntologyFetcher.WatsonFetcher.search(keyword.trim)
          } */
          // Update database if requested.
          (if(!offline) {
            val timerOnline = new BasicTimer("search|fetch|all", keywords.mkString("|")).start()
            val futureList: List[Future[FetchResult]] = keywords.map {
              keyword =>
                val timerOnlineSwoogle = new BasicTimer("search|fetch|swoogle", keyword).start()
                val swoogleFetching = OntologyFetcher.SwoogleFetcher.search(keyword.trim)
                swoogleFetching.onComplete { _ => timerOnlineSwoogle.stop() }
                swoogleFetching
            } ++ keywords.map {
              keyword =>
                val timerOnlineSindice = new BasicTimer("search|fetch|sindice", keyword).start()
                val sindiceFetching = OntologyFetcher.SindiceFetcher.search(keyword.trim)
                sindiceFetching.onComplete { _ => timerOnlineSindice.stop() }
                sindiceFetching
            }
            val fetchResult = Future.sequence(futureList)
            fetchResult.onComplete { _ => timerOnline.stop() }
            fetchResult
          } else {
            Future.successful(List(FetchResult()))
          }).flatMap { fetchResult =>
            val timerSearch = new BasicTimer("search|search", keywords.mkString("|")).start()
            // Do the actual search
            val searchResults = Search.findElementsByKeyword(keywords.mkString(" ")).map {
              searchResult =>
                timer.stop()
                Ok {
                  JsObject(Seq(
                    "fetchResults"  -> Json.toJson(fetchResult),
                    "searchResults" -> Json.toJson(searchResult)
                  ))
                }
            }
            searchResults.onComplete { _ => timerSearch.stop() }
            searchResults
          }
      }.recoverTotal {
        e => Future.successful(BadRequest("Detected error:" + JsError.toFlatJson(e)))
      }
  }

  def getExportFormats = Action {
    import scala.collection.JavaConversions._
    val json = RDFFormat.values().toIterable.map {
      aFormat =>
        Json.obj(
          "name" -> aFormat.getName,
          "mime" -> aFormat.getDefaultMIMEType,
          "extension" -> aFormat.getDefaultFileExtension
        )
    }.toSeq
    Ok(JsArray(json))
  }

  implicit val getRelatedElementsRead: Reads[(String, String)] =
    {
      (__ \ "uri").read[String] and
      (__ \ "predicate").read[String]
    }.tupled
  val searchableTypes = RDF.InverseOf :: RDF.DisjointWith :: RDF.Range :: RDF.Domain ::
    RDF.SubPropertyOf :: RDF.SubclassOf :: RDF.Type :: Nil
  def getRelatedElements(getWhat: String) = Action.async(parse.json) {
    request =>
      request.body.validate[(String, String)].map {
        case (uri: String, predicate: String) =>
          if(!searchableTypes.contains(predicate)) {
            Future.successful { BadRequest("Invalid 'type'") }
          } else {
            (
              if(getWhat == "object") {
                OntologyTriple.getObject(subject = uri, predicate = predicate)
              } else if(getWhat == "subject") {
                OntologyTriple.getSubject(predicate = predicate, objekt = uri)
              } else {
                Future.sequence {
                  OntologyTriple.getObject(subject = uri, predicate = predicate) ::
                    OntologyTriple.getSubject(predicate = predicate, objekt = uri) :: Nil
                }.map {
                  f => f.flatten
                }
              }
              ).flatMap {
              elements =>
                Future.sequence {
                  elements.map {
                    element => OntologyTriple.getDisplayableElement(element)
                  }
                }
            }.map {
              setOfMaybeDisplayableElements => setOfMaybeDisplayableElements.flatten
            }.map {
              displayableElements =>
                Ok(Json.toJson(displayableElements.toSet))
            }
          }
      }.recoverTotal {
        e => Future.successful {
          BadRequest(JsError.toFlatJson(e))
        }
      }
  }


  def getTriple = Action.async(parse.json) {
    request =>
      val subject = (request.body \ "subject").asOpt[String]
      val predicate = (request.body \ "predicate").asOpt[String]
      val objekt = (request.body \ "object").asOpt[String]

      if (!subject.isDefined && !predicate.isDefined && !objekt.isDefined) {
        Future.successful { BadRequest("invalid data") }
      } else {
        OntologyTriple.getTriple(subject = subject, predicate = predicate, objekt = objekt, max = 100)
          .flatMap {
            triples =>
              Future.sequence {
                triples.map {
                  triple =>

                    val maybeSubjectDisplayableFuture = if (!subject.isDefined) {
                      Some(OntologyTriple.getDisplayableElement(triple.subject, true))
                    } else {
                      None
                    }
                    val maybePredicateDisplayableFuture = if (!predicate.isDefined) {
                      Some(OntologyTriple.getDisplayableElement(triple.predicate, true))
                    } else {
                      None
                    }
                    val maybeDisplayableObjectFuture = if (!objekt.isDefined) {
                      if (!triple.isObjectData) {
                        Some(OntologyTriple.getDisplayableElement(triple.objekt, true))
                      } else {
                        Some(Future.successful { Some(DisplayableElement(
                          kind = "__DATA__",
                          label = Some(triple.objekt),
                          uri = triple.objekt)
                        )})
                      }
                    } else {
                      None
                    }

                    val fList = (maybeSubjectDisplayableFuture :: maybePredicateDisplayableFuture :: maybeDisplayableObjectFuture :: Nil).flatten

                    val Z: Future[Option[Map[String, DisplayableElement]]] = Future.sequence(fList).map {
                      list =>

                        if( (maybeSubjectDisplayableFuture.isDefined && !maybeSubjectDisplayableFuture.get.value.get.get.isDefined) ||
                            (maybePredicateDisplayableFuture.isDefined && !maybePredicateDisplayableFuture.get.value.get.get.isDefined) ||
                            (maybeDisplayableObjectFuture.isDefined && !maybeDisplayableObjectFuture.get.value.get.get.isDefined)
                        ) {
                          None
                        } else {
                          val pt1 = Map[String, DisplayableElement]()
                          val pt2 = if (!subject.isDefined) {
                            pt1 + ("subject" -> maybeSubjectDisplayableFuture.get.value.get.get.get)
                          } else pt1

                          val pt3 = if (!predicate.isDefined) {
                            pt2 + ("predicate" -> maybePredicateDisplayableFuture.get.value.get.get.get)
                          } else {
                            pt2
                          }
                          val pt4 = if (!objekt.isDefined) {
                            pt3 + ("object" -> maybeDisplayableObjectFuture.get.value.get.get.get)
                          } else {
                            pt3
                          }
                          Some(pt4)
                        }
                    }
                    Z
                }
              }.map {
                //scala.concurrent.Future[Option[Map[String,models.DisplayableElement]]]
                setOfMaybeDisplayableElements => setOfMaybeDisplayableElements.flatten
              }
        }.map {
          displayableElements =>
            Ok(Json.toJson(displayableElements.toSet))
        }
      }
  }

}
