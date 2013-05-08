import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  resolvers += Resolver.sonatypeRepo("snapshots")

  val appName         = "tezapp"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    // Add your project dependencies here,
    javaCore,
    "commons-io" % "commons-io" % "2.4",
    "commons-validator" % "commons-validator" % "1.4.0",
    "xerces" % "xercesImpl" % "2.11.0",
    "org.apache.jena" % "jena-core" % "2.10.0" excludeAll(ExclusionRule(organization = "org.slf4j"), ExclusionRule(organization = "xerces")),
    "se.radley" % "play-plugins-salat_2.10" % "1.2",
    "org.openrdf.sesame" % "sesame-rio" % "2.7.0"
  )

  // Only compile the bootstrap bootstrap.less file and any other *.less file in the stylesheets directory 
  def customLessEntryPoints(base: File): PathFinder = ( 
      (base / "app" / "assets" / "css" / "bootstrap" * "bootstrap.less") +++
      (base / "app" / "assets" / "css" / "bootstrap" * "responsive.less") +++ 
      (base / "app" / "assets" / "css" * "*.less")
  )
  
  val main = play.Project(appName, appVersion, appDependencies).settings(
    lessEntryPoints <<= baseDirectory(customLessEntryPoints),
    routesImport += "se.radley.plugin.salat.Binders._",
    templatesImport += "org.bson.types.ObjectId",
    resolvers += "Maven Central" at "http://repo1.maven.org/maven2",
    resolvers += "Typesafe Repository 2" at "http://repo.typesafe.com/typesafe/repo/"
  )
  
}
