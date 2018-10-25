
name := "slicebox"
version := "1.5.1-sp1"
organization := "se.nimsa"
scalaVersion := "2.12.6"
scalacOptions := Seq("-encoding", "UTF-8", "-Xlint", "-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

// define the project

lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging, SystemVPlugin, DockerPlugin, AshScriptPlugin, BuildInfoPlugin)

mainClass in Compile := Some("se.nimsa.sbx.app.Slicebox")

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "se.nimsa.sbx.app"

// for sbt-resolver, (the re-start and re-stop commands)

Revolver.settings

// native packaging

maintainer := "nimsa.se"
packageSummary := "Slicebox DICOM sharing service"
packageDescription := "Slicebox is a service for sharing medical image data with collaborators while protecting patient information"

// native packaging - universal

mappings in Universal += {
  val conf = (resourceDirectory in Compile).value / "slicebox.conf"
  conf -> "conf/slicebox.conf"
}
mappings in Universal += {
  val conf = (resourceDirectory in Compile).value / "logback.xml"
  conf -> "conf/logback.xml"
}
batScriptExtraDefines += """set _JAVA_OPTS=%_JAVA_OPTS% -Xmx2G -Dconfig.file="%SLICEBOX_HOME%\\conf\\slicebox.conf" -Dlogback.configurationFile="%SLICEBOX_HOME%\\conf\\logback.xml" """

// native packaging - linux

daemonUser in Linux := normalizedName.value // user which will execute the application
daemonGroup in Linux := (daemonUser in Linux).value // group which will execute the application
bashScriptExtraDefines ++= Seq(
  """addJava "-Xmx2G" """,
  """addJava "-Dconfig.file=${app_home}/../conf/slicebox.conf" """,
  """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml" """)

// rpm specific
rpmVendor := maintainer.value
rpmLicense := Some("Apache v2")
packageArchitecture in Rpm := "noarch"
rpmGroup := Some("Applications/Research")
rpmRelease := {
  if (version.value.matches(".*-SNAPSHOT")) System.currentTimeMillis().toString else "1"
}

// for automatic license stub generation

organizationName := "Lars Edenbrandt"
startYear := Some(2014)
licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

// repos


resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  "dcm4che Repository" at "https://www.dcm4che.org/maven2/",
  Resolver.bintrayRepo("hseeberger", "maven"))

updateOptions := updateOptions.value.withGigahorse(false) // temporary workaround for SBT 1.0.2

// deps

libraryDependencies ++= {
  val akkaVersion = "2.5.14"
  val akkaHttpVersion = "10.1.3"
  val slickVersion = "3.2.3"
  val dcm4cheVersion = "3.3.8"
  val alpakkaVersion = "0.20"
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion, // force newer version than default in akka-http
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-play-json" % "1.21.0",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.h2database" % "h2" % "1.4.197",
    "mysql" % "mysql-connector-java" % "6.0.6",
    "com.github.t3hnar" %% "scala-bcrypt" % "3.1",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.385",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.dcm4che" % "dcm4che-core" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-image" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-imageio" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-net" % dcm4cheVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",  // force newer version than default in akka-http
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "org.webjars" % "angularjs" % "1.5.11",
    "org.webjars" % "angular-material" % "1.1.5",
    "org.webjars" % "angular-file-upload" % "11.0.0",
    "se.nimsa" %% "dicom-streams" % "0.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpakkaVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-file" % alpakkaVersion
  )
}

// run tests in separate JVMs

fork in Test := true

// turn on cached resolution in SBT

updateOptions := updateOptions.value.withCachedResolution(true)

// make sure files in the public folder are included in build

WebKeys.packagePrefix in Assets := "public/"
(managedClasspath in Runtime) += (packageBin in Assets).value

// docker base image
dockerBaseImage := "openjdk:8-jre-alpine"
