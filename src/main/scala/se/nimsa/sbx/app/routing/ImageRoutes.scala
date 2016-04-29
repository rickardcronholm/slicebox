/*
 * Copyright 2016 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.app.routing

import java.io.File

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.pattern.ask
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute, Jpeg2Dcm}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import spray.http.ContentType.apply
import spray.http._
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.routing.Route
import se.nimsa.sbx.util.SbxExtensions._
import spray.can.Http

trait ImageRoutes {
  this: SliceboxService =>

  val chunkSize = 524288
  val bufferSize = chunkSize

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            addDatasetRoute(file.entity.data.toByteArray, apiUser)
          } ~ entity(as[Array[Byte]]) { bytes =>
            addDatasetRoute(bytes, apiUser)
          }
        }
      } ~ pathPrefix(LongNumber) { imageId =>
        onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
          case Some(image) =>
            pathEndOrSingleSlash {
              get {
                onSuccess(storageService.ask(GetImageData(image)).mapTo[Option[ImageData]]) {
                  case Some(imageData) =>
                    complete(HttpEntity(`application/octet-stream`, HttpData(imageData.data)))
                  case None =>
                    complete((NotFound, s"No file found for image id $imageId"))
                }
              } ~ delete {
                onSuccess(storageService.ask(DeleteDataset(image)).flatMap { _ =>
                  metaDataService.ask(DeleteMetaData(image.id))
                }) {
                  case _ =>
                    complete(NoContent)
                }
              }
            } ~ path("attributes") {
              get {
                onSuccess(storageService.ask(GetImageAttributes(image)).mapTo[Option[List[ImageAttribute]]]) {
                  import spray.httpx.SprayJsonSupport._
                  complete(_)
                }
              }
            } ~ path("imageinformation") {
              get {
                onSuccess(storageService.ask(GetImageInformation(image)).mapTo[Option[ImageInformation]]) {
                  import spray.httpx.SprayJsonSupport._
                  complete(_)
                }
              }
            } ~ path("png") {
              parameters(
                'framenumber.as[Int] ? 1,
                'windowmin.as[Int] ? 0,
                'windowmax.as[Int] ? 0,
                'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
                get {
                  onSuccess(storageService.ask(GetImageFrame(image, frameNumber, min, max, height)).mapTo[Option[Array[Byte]]]) {
                    case Some(bytes) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                    case None => complete(NotFound)
                  }
                }
              }
            }
          case None =>
            complete(NotFound)
        }
      } ~ path("delete") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            val futureDeleted = Future.sequence {
              imageIds.map { imageId =>
                metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].map { imageMaybe =>
                  imageMaybe.map { image =>
                    storageService.ask(DeleteDataset(image)).flatMap { _ =>
                      metaDataService.ask(DeleteMetaData(image.id))
                    }
                  }
                }.unwrap
              }
            }
            onSuccess(futureDeleted) { m =>
              complete(NoContent)
            }
          }
        }
      } ~ path("export") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            if (imageIds.isEmpty)
              complete(NoContent)
            else {
              //              val imagesAndSeriesFuture = Future.sequence {
              //                imageIds.map { imageId =>
              //                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
              //                    imageMaybe.map { image =>
              //                      metaDataService.ask(GetSingleFlatSeries(image.seriesId)).mapTo[Option[FlatSeries]].map { flatSeriesMaybe =>
              //                        flatSeriesMaybe.map { flatSeries =>
              //                          (image, flatSeries)
              //                        }
              //                      }
              //                    }.unwrap
              //                  }
              //                }
              //              }.map(_.flatten)

              complete(storageService.ask(CreateExportSet(imageIds)).mapTo[ExportSetId])
            }
          }
        }
      } ~ path(LongNumber) { exportSetId =>
        get { ctx =>
          respondWithHeader(`Content-Disposition`("attachment; filename=\"slicebox-export.zip\"")) {
            storageService.ask(GetExportSetImageIds(exportSetId)).mapTo[Option[Seq[Long]]].map {
              case Some(imageIds) =>
                actorRefFactory.actorOf(Props(new Streamer(ctx.responder, imageIds)))
              case None =>
                complete(NotFound)
            }
          }
        }
      } ~ path("jpeg") {
        parameters('studyid.as[Long]) { studyId =>
          post {
            entity(as[Array[Byte]]) { jpegBytes =>
              val source = Source(SourceType.USER, apiUser.user, apiUser.id)
              val addedJpegFuture = metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]].flatMap { studyMaybe =>
                studyMaybe.map { study =>
                  metaDataService.ask(GetPatient(study.patientId)).mapTo[Option[Patient]].map { patientMaybe =>
                    patientMaybe.map { patient =>
                      val encapsulatedJpeg = Jpeg2Dcm(jpegBytes, patient, study)
                      metaDataService.ask(AddMetaData(encapsulatedJpeg, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                        storageService.ask(AddJpeg(encapsulatedJpeg, source, metaData.image)).map { _ => metaData.image }
                      }
                    }
                  }
                }.unwrap
              }.unwrap
              onSuccess(addedJpegFuture) {
                case Some(image) =>
                  import spray.httpx.SprayJsonSupport._
                  complete((Created, image))
                case _ =>
                  complete(NotFound)
              }
            }
          }
        }
      }
    }

  private def addDatasetRoute(bytes: Array[Byte], apiUser: ApiUser) = {
    val dataset = DicomUtil.loadDataset(bytes, withPixelData = true, useBulkDataURI = false)
    val source = Source(SourceType.USER, apiUser.user, apiUser.id)
    val futureImageAndOverwrite =
      storageService.ask(CheckDataset(dataset)).mapTo[Boolean].flatMap { status =>
        metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded].flatMap { metaData =>
          storageService.ask(AddDataset(dataset, source, metaData.image)).mapTo[DatasetAdded].map { datasetAdded => (metaData.image, datasetAdded.overwrite) }
        }
      }
    onSuccess(futureImageAndOverwrite) {
      case (image, overwrite) =>
        import spray.httpx.SprayJsonSupport._
        if (overwrite)
          complete((OK, image))
        else
          complete((Created, image))
    }
  }

  class Streamer(client: ActorRef, imageIds: Seq[Long]) extends Actor with ActorLogging {
    log.info("Starting streaming response ...")

    // we use the successful sending of a chunk as trigger for scheduling the next chunk
    client ! ChunkedResponseStart(HttpResponse(entity = " " * 2048)).withAck(Ok(imageIds.tail))

    def receive = {
      case Ok(remaining) if remaining.isEmpty =>
        log.info("Finalizing response stream ...")
        client ! MessageChunk("\nStopped...")
        client ! ChunkedMessageEnd
        context.stop(self)

      case Ok(remaining) =>
        log.info("Sending response chunk ...")
        context.system.scheduler.scheduleOnce(100.millis) {
          client ! MessageChunk(remaining.head + ", ").withAck(Ok(remaining.tail))
        }

      case x: Http.ConnectionClosed =>
        log.info("Canceling response stream due to {} ...", x)
        context.stop(self)
    }

    // simple case class whose instances we use as send confirmation message for streaming chunks
    case class Ok(imageIds: Seq[Long])

  }

}
