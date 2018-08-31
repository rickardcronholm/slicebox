/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.app

import java.nio.file.{NoSuchFileException, Paths}
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import se.nimsa.sbx.anonymization.{AnonymizationDAO, AnonymizationServiceActor}
import se.nimsa.sbx.app.GeneralProtocol.SystemInformation
import se.nimsa.sbx.app.routing.SliceboxRoutes
import se.nimsa.sbx.box.{BoxDAO, BoxServiceActor}
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.directory.{DirectoryWatchDAO, DirectoryWatchServiceActor}
import se.nimsa.sbx.forwarding.{ForwardingDAO, ForwardingServiceActor}
import se.nimsa.sbx.importing.{ImportDAO, ImportServiceActor}
import se.nimsa.sbx.log.{LogDAO, LogServiceActor, SbxLog}
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.scp.{ScpDAO, ScpServiceActor}
import se.nimsa.sbx.scu.{ScuDAO, ScuServiceActor}
import se.nimsa.sbx.seriestype.{SeriesTypeDAO, SeriesTypeServiceActor}
import se.nimsa.sbx.storage.{FileStorage, S3Storage, StorageService, StorageServiceActor}
import se.nimsa.sbx.user.{UserDAO, UserServiceActor}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

trait SliceboxBase extends SliceboxRoutes with DicomStreamOps with JsonFormats with PlayJsonSupport {

  val systemInformation: SystemInformation = SystemInformation(BuildInfo.version)

  val appConfig: Config  = ConfigFactory.load()
  val sliceboxConfig: Config = appConfig.getConfig("slicebox")

  implicit def system: ActorSystem

  implicit def materializer: ActorMaterializer

  implicit def executor: ExecutionContextExecutor

  implicit val timeout: Timeout = {
    val clientTimeout = appConfig.getDuration("akka.http.client.connecting-timeout", MILLISECONDS)
    val serverTimeout = appConfig.getDuration("akka.http.server.request-timeout", MILLISECONDS)
    Timeout(math.max(clientTimeout, serverTimeout) + 10, MILLISECONDS)
  }

  def dbConfig: DatabaseConfig[JdbcProfile]

  val userDao = new UserDAO(dbConfig)
  val logDao = new LogDAO(dbConfig)
  val seriesTypeDao = new SeriesTypeDAO(dbConfig)
  val forwardingDao = new ForwardingDAO(dbConfig)
  val metaDataDao = new MetaDataDAO(dbConfig)
  val propertiesDao = new PropertiesDAO(dbConfig)
  val directoryWatchDao = new DirectoryWatchDAO(dbConfig)
  val scpDao = new ScpDAO(dbConfig)
  val scuDao = new ScuDAO(dbConfig)
  val boxDao = new BoxDAO(dbConfig)
  val importDao = new ImportDAO(dbConfig)
  val anonymizationDao = new AnonymizationDAO(dbConfig)

  val createDbTables: Future[Unit] = for {
    _ <- logDao.create()
    _ <- seriesTypeDao.create()
    _ <- forwardingDao.create()
    _ <- metaDataDao.create()
    _ <- propertiesDao.create()
    _ <- directoryWatchDao.create()
    _ <- scpDao.create()
    _ <- scuDao.create()
    _ <- boxDao.create()
    _ <- importDao.create()
    _ <- userDao.create()
    _ <- anonymizationDao.create()
  } yield Unit
  createDbTables.onComplete {
    case Success(_) => SbxLog.default("System", "Database tables created. ")
    case Failure(e) => SbxLog.error("System", s"Could not create tables. ${e.getMessage}")
  }
  Await.ready(createDbTables, 1.minute)

  val host: String = sliceboxConfig.getString("host")
  val port: Int = sliceboxConfig.getInt("port")
  val publicHost: String = sliceboxConfig.getString("public.host")
  val publicPort: Int = sliceboxConfig.getInt("public.port")

  val useSsl: Boolean = sliceboxConfig.getString("ssl.ssl-encryption") == "on"

  val apiBaseURL: String = {
    val withReverseProxy = (host != publicHost) || (port != publicPort)
    val withSsl = withReverseProxy && sliceboxConfig.getBoolean("public.with-ssl") || useSsl

    val ssl = if (withSsl) "s" else ""

    if (!withSsl && (publicPort == 80) || withSsl && (publicPort == 443))
      s"http$ssl://$publicHost/api"
    else
      s"http$ssl://$publicHost:$publicPort/api"
  }

  val superUser: String = sliceboxConfig.getString("superuser.user")
  val superPassword: String = sliceboxConfig.getString("superuser.password")
  val sessionsIncludeIpAndUserAgent: Boolean = sliceboxConfig.getBoolean("user-sessions-include-ip-and-useragent")

  def storage: StorageService

  val userService: ActorRef = {
    val sessionTimeout = sliceboxConfig.getDuration("session-timeout", MILLISECONDS)
    system.actorOf(UserServiceActor.props(userDao, superUser, superPassword, sessionTimeout), name = "UserService")
  }
  val logService: ActorRef = system.actorOf(LogServiceActor.props(logDao), name = "LogService")
  val metaDataService: ActorRef = system.actorOf(MetaDataServiceActor.props(metaDataDao, propertiesDao), name = "MetaDataService")
  val storageService: ActorRef = system.actorOf(StorageServiceActor.props(storage), name = "StorageService")
  val anonymizationService: ActorRef = {
    val purgeEmptyAnonymizationKeys = sliceboxConfig.getBoolean("anonymization.purge-empty-keys")
    var encryptionMode = sliceboxConfig.getBoolean("anonymization.encryption")
    var publicKey
    var privateKey
    if (encryptionMode) {
      try {
        publicKey: PublicKey = KeyReader.PublicKeyReader(sliceboxConfig.getString("anonymization.public-key-path"))
        privateKey: PrivateKey = KeyReader.PrivateKeyReader(sliceboxConfig.getString("anonymization.private-key-path"))
      }
      catch {
        case e: NoSuchFileException =>
          ecnryptionMode = false
      }
    }
    if (ecnryptionMode)
      system.actorOf(AnonymizationServiceActor.props(anonymizationDao, purgeEmptyAnonymizationKeys, publicKey, privateKey), name = "AnonymizationService")
    else
      system.actorOf(AnonymizationServiceActor.props(anonymizationDao, purgeEmptyAnonymizationKeys), name = "AnonymizationService")
  }
  val boxService: ActorRef = system.actorOf(BoxServiceActor.props(boxDao, apiBaseURL, storage), name = "BoxService")
  val scpService: ActorRef = system.actorOf(ScpServiceActor.props(scpDao, storage), name = "ScpService")
  val scuService: ActorRef = system.actorOf(ScuServiceActor.props(scuDao, storage), name = "ScuService")
  val directoryService: ActorRef = system.actorOf(DirectoryWatchServiceActor.props(directoryWatchDao, storage), name = "DirectoryService")
  val seriesTypeService: ActorRef = system.actorOf(SeriesTypeServiceActor.props(seriesTypeDao, storage), name = "SeriesTypeService")
  val forwardingService: ActorRef = system.actorOf(ForwardingServiceActor.props(forwardingDao, storage), name = "ForwardingService")
  val importService: ActorRef = system.actorOf(ImportServiceActor.props(importDao), name = "ImportService")

  override def callAnonymizationService[R: ClassTag](message: Any): Future[R] = anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any): Future[R] = metaDataService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable = system.scheduler.scheduleOnce(delay)(task)

  // special context for blocking IO
  val blockingIoContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

}

object Slicebox extends {
  implicit val system: ActorSystem = ActorSystem("slicebox")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executor: ExecutionContextExecutor = system.dispatcher
  val cfg = ConfigFactory.load().getConfig("slicebox")

  val dbConfig = DatabaseConfig.forConfig[JdbcProfile]("slicebox.database.config")

  val chunkSize: Int = cfg.getMemorySize("stream.chunk-size").toBytes.toInt

  val storage =
    if (cfg.getString("dicom-storage.config.name") == "s3")
      new S3Storage(cfg.getString("dicom-storage.config.bucket"), cfg.getString("dicom-storage.config.prefix"), cfg.getString("dicom-storage.config.region"))(system, materializer) {
        override val streamChunkSize: Int = chunkSize
      }
    else
      new FileStorage(Paths.get(cfg.getString("dicom-storage.file-system.path"))) {
        override val streamChunkSize: Int = chunkSize
      }
} with SliceboxBase with App {

  val bindFuture = if (useSsl) {
    Http().setDefaultClientHttpsContext(SslConfiguration.httpsContext)
    Http().bindAndHandle(routes, host, port, SslConfiguration.httpsContext)
  } else
    Http().bindAndHandle(routes, host, port)

  bindFuture onComplete {
    case Success(_) =>
      SbxLog.info("System", s"Slicebox bound to $host:$port")
    case Failure(e) =>
      SbxLog.error("System", s"Could not bind to $host:$port, ${e.getMessage}")
  }
}
