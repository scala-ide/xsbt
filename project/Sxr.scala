	import sbt._
	import Keys._
	import Scope.ThisScope

object Sxr
{
	val sxrConf = config("sxr") hide
	val sxr = TaskKey[File]("sxr")
	val sourceDirectories = TaskKey[Seq[File]]("sxr-source-directories")

	lazy val settings: Seq[Setting[_]] = inTask(sxr)(inSxrSettings) ++ baseSettings
	
	val sxrScalaVersion = Map("2.9.1" -> "2.9.0")

	def baseSettings = Seq(
	  libraryDependencies <<= (scalaVersion, libraryDependencies) {(sv, deps) =>
	    val sxrVersion = VersionComp.versionMap("sxr").getOrElse(sv, error("Unsupported Scala version " + sv))
	    deps :+ "org.scala-tools.sxr" % ("sxr_" + sxrScalaVersion.getOrElse(sv, sv)) % sxrVersion % sxrConf.name
	  }
		//libraryDependencies += "org.scala-tools.sxr" % "sxr_2.9.0" % "0.2.7" % sxrConf.name
	)
	def inSxrSettings = Seq(
		managedClasspath <<= update map { _.matching( configurationFilter(sxrConf.name) ).classpath },
		scalacOptions <+= sourceDirectories map { "-P:sxr:base-directory:" + _.absString },
		scalacOptions <+= managedClasspath map { "-Xplugin:" + _.files.absString },
		target <<= target in taskGlobal apply { _ / "browse" },
		sxr in taskGlobal <<= sxrTask
	)
	def taskGlobal = ThisScope.copy(task = Global)
	def sxrTask = (sources, target, scalacOptions, classpathOptions, scalaInstance, fullClasspath in sxr, streams) map { (srcs, out, opts, cpOpts, si, cp, s) =>
		IO.delete(out)
		IO.createDirectory(out)
		val comp = new compiler.RawCompiler(si, cpOpts, s.log)
		comp(srcs, cp.files, out, opts)
		out.getParentFile / (out.getName + ".sxr")
	}
}
