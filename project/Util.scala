	import sbt._
	import Keys._
	import StringUtilities.normalize

	
private object VersionComp {
	val versionMap = Map(
	    "scalacheck" -> Map("2.8.1" -> "1.8", "2.9.0-1" -> "1.9", "2.9.1" -> "1.9", "2.10.0-SNAPSHOT" -> "1.9"),
	    "sbinary" -> Map("2.8.1" -> "0.4.0", "2.9.0" -> "0.4.0", "2.9.0-1" -> "0.4.0", "2.9.1" -> "0.4.0", "2.10.0-SNAPSHOT" -> "0.4.0"),
	    "sxr" -> Map("2.8.1" -> "0.2.7", "2.9.0-1" -> "0.2.7", "2.9.1" -> "0.2.7", "2.10.0-SNAPSHOT" -> "0.2.7")
	    )
}

object Util
{
	lazy val componentID = SettingKey[Option[String]]("component-id")

	def inAll(projects: => Seq[ProjectReference], key: ScopedSetting[Task[Unit]]) =
		state flatMap { s => nop dependsOn( Defaults.inAllProjects(projects, key, Project.extract(s).structure.data) : _*) }
	
	def noPublish(p: Project) = p.copy(settings = noRemotePublish(p.settings))
	def noRemotePublish(in: Seq[Setting[_]]) = in filterNot { s => s.key == deliver || s.key == publish }
	lazy val noExtra = projectDependencies ~= { _.map(_.copy(extraAttributes = Map.empty)) } // remove after 0.10.2

	def project(path: File, nameString: String) = Project(normalize(nameString), path) settings( name := nameString, noExtra )
	def baseProject(path: File, nameString: String) = project(path, nameString) settings( base : _*)
	def testedBaseProject(path: File, nameString: String) = baseProject(path, nameString) settings( testDependencies : _*)
	
	lazy val javaOnly = Seq[Setting[_]](/*crossPaths := false, */compileOrder := CompileOrder.JavaThenScala, unmanagedSourceDirectories in Compile <<= Seq(javaSource in Compile).join)
	lazy val base: Seq[Setting[_]] = Seq(scalacOptions ++= Seq("-Xelide-below", "0"), projectComponent) ++ Licensed.settings
	
	def testDependencies = (
	  libraryDependencies <<= (scalaVersion, libraryDependencies) {(sv, deps) =>
	    val scalaCheck = VersionComp.versionMap("scalacheck").getOrElse(sv, error("Unsupported Scala version " + sv))
	    //deps :+ ("org.scala-tools.testing" % "scalacheck" % scalaCheck % "test")
	    //deps :+ ("org.scala-tools.testing" %% "specs" % "1.6.8" % "test")
	    deps
	  }
	)
	
/*	def testDependencies = libraryDependencies ++= Seq(
		"org.scala-tools.testing" % "scalacheck_2.9.0-1" % "1.9" % "test",
		"org.scala-tools.testing" % "specs_2.9.0-1" % "1.6.8" % "test"
	)*/

	lazy val minimalSettings: Seq[Setting[_]] = Defaults.paths ++ Seq[Setting[_]](crossTarget <<= target.identity, name <<= thisProject(_.id))

	def projectComponent = projectID <<= (projectID, componentID) { (pid, cid) => 
		cid match { case Some(id) => pid extra("e:component" -> id); case None => pid }
	}

	lazy val apiDefinitions = TaskKey[Seq[File]]("api-definitions")

	def generateAPICached(cache: File, defs: Seq[File], cp: Classpath, out: File, main: Option[String], run: ScalaRun, s: TaskStreams): Seq[File] =
	{
		def gen() = generateAPI(defs, cp, out, main, run, s)
		val f = FileFunction.cached(cache / "gen-api", FilesInfo.hash) { _ => gen().toSet} // TODO: check if output directory changed
		f(defs.toSet).toSeq
	}
	def generateAPI(defs: Seq[File], cp: Classpath, out: File, main: Option[String], run: ScalaRun, s: TaskStreams): Seq[File] =
	{
		IO.delete(out)
		IO.createDirectory(out)
		val args = "immutable" :: "xsbti.api" :: out.getAbsolutePath :: defs.map(_.getAbsolutePath).toList
		val mainClass = main getOrElse "No main class defined for datatype generator"
		toError(run.run(mainClass, cp.files, args, s.log))
		(out ** "*.java").get
	}
	def generateVersionFile(version: String, dir: File, s: TaskStreams): Seq[File] =
	{
		import java.util.{Date, TimeZone}
		val formatter = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss")
		formatter.setTimeZone(TimeZone.getTimeZone("GMT"))
		val timestamp = formatter.format(new Date)
		val content = "version=" + version + "\ntimestamp=" + timestamp
		val f = dir / "xsbt.version.properties"
		if(!f.exists) { // TODO: properly handle this
			s.log.info("Writing version information to " + f + " :\n" + content)
			IO.write(f, content)
		}
		f :: Nil
	}
	def binID = "compiler-interface-bin"
	def binIDshort = "compiler-interface"
	def srcID = "compiler-interface-src"
}
object Common
{
	def lib(m: ModuleID) = libraryDependencies += m
	lazy val jlineDep = "jline" % "jline" % "0.9.94" intransitive()
	lazy val jline = lib(jlineDep)
	lazy val ivy = lib("org.apache.ivy" % "ivy" % "2.2.0")
	lazy val httpclient = lib("commons-httpclient" % "commons-httpclient" % "3.1")
	lazy val jsch = lib("com.jcraft" % "jsch" % "0.1.31" intransitive() )
	lazy val sbinary = {//lib("org.scala-tools.sbinary" %% "sbinary" % "0.4.0" )
	  libraryDependencies <<= (scalaVersion, libraryDependencies) {(sv, deps) =>
	    val sbinaryVersion = VersionComp.versionMap("sbinary").getOrElse(sv, error("Unsupported Scala version " + sv))
	    deps :+ ("org.scala-tools.sbinary" %% "sbinary" % sbinaryVersion)
	  }
	}

//	lazy val sbinary = lib("org.scala-tools.sbinary" % "sbinary_2.9.0" % "0.4.0" )
	lazy val scalaCompiler = libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _ )
}
object Licensed
{
	lazy val notice = SettingKey[File]("notice")
	lazy val extractLicenses = TaskKey[Seq[File]]("extract-licenses")

	lazy val seeRegex = """\(see (.*?)\)""".r
	def licensePath(base: File, str: String): File = { val path = base / str; if(path.exists) path else error("Referenced license '" + str + "' not found at " + path) }
	def seePaths(base: File, noticeString: String): Seq[File] = seeRegex.findAllIn(noticeString).matchData.map(d => licensePath(base, d.group(1))).toList
	
	def settings: Seq[Setting[_]] = Seq(
		notice <<= baseDirectory(_ / "NOTICE"),
		unmanagedResources in Compile <++= (notice, extractLicenses) map { _ +: _ },
		extractLicenses <<= (baseDirectory in ThisBuild, notice, streams) map extractLicenses0
	)
	def extractLicenses0(base: File, note: File, s: TaskStreams): Seq[File] =
		if(!note.exists) Nil else
			try { seePaths(base, IO.read(note)) }
			catch { case e: Exception => s.log.warn("Could not read NOTICE"); Nil }
}