package sbt

import java.io.File

object PackageProject
{
  val PackageDescription = "Produces a single jar containing all the " +
  		"jars necessary for sbt integration with IDE"
  val WritePackageDescription = "Creates configuration file necessary for " +
      "Proguard to do the job"
}


trait PackageProject extends BasicScalaProject {
  import PackageProject._
  
  def packageProguardConfigurationPath = outputPath / "packaged.all.sbt"
  def outputJar: Path = outputPath / "packaged.all.sbt.jar"
  
  val toolsConfig = config("tools") hide
  val proguardJar = "net.sf.proguard" % "proguard" % "4.4" % toolsConfig.name

  val jarsConfig = config("jars") hide
  
  def rawPackage: Task
  def launcherProject: Project
  
  def basicOptions: Seq[String] = 
    Seq() // At the moment none.
    
  lazy val packageAllSbt = packageAllSbtAction
  def packageAllSbtAction = packageTask dependsOn(writePackageProguardConfiguration) describedAs(PackageDescription)
  lazy val writePackageProguardConfiguration = writePackageProguardConfigurationAction
  def writePackageProguardConfigurationAction = writePackageProguardConfigurationTask dependsOn(rawPackage)
  
  private def options: Seq[String] = {
    Seq("-dontwarn org.apache.tools*", "-dontwarn scala.Serializable", "-dontskipnonpubliclibraryclasses") ++
    Seq("-dontskipnonpubliclibraryclassmembers", "-ignorewarnings") ++
    Seq("-dontshrink", "-dontoptimize", "-dontobfuscate")
  }
  
  private def launcherBootJar: Seq[String] = {
    launcherProject match {
      case bpp: BasicScalaPaths => Seq("-injars " + mkpath(bpp.jarPath.asFile) + "(xsbt/boot/**.class;!xsbti/**.class)")
      case _ => Seq()
    }
  }
        
  def template(inJars: Seq[File], libraryJars: Seq[File], outputJar: File): String ={    
    val lines: Seq[String] = 
      inJars.map(f => "-injars " + mkpath(f) + "(!NOTICE;!LICENSE;!META-INF/MANIFEST.MF)") ++ 
      launcherBootJar ++
      libraryJars.map(f => "-libraryjars " + mkpath(f)) ++
      Seq("-outjars " + mkpath(outputJar)) ++
      options
    lines.mkString("\n")
  }
  
	def mkpath(f: File) : String = mkpath(f.getAbsolutePath, '\"')
	def mkpath(path: String, delimiter : Char) : String = delimiter + path + delimiter

	protected def packageTask =
		task
		{
			FileUtilities.clean(outputJar :: Nil, log)
			val proguardClasspathString = Path.makeString(managedClasspath(toolsConfig).get)
			val configFile = mkpath(packageProguardConfigurationPath.asFile.getAbsolutePath, '\'')
			val exitValue = Process("java", List("-Xmx256M", "-Xss100M", "-cp", proguardClasspathString, "proguard.ProGuard", "-include " + configFile)) ! log
			if(exitValue == 0) None else Some("Proguard failed with nonzero exit code (" + exitValue + ")")
		}
	protected def writePackageProguardConfigurationTask =
		task
		{
			val dependencies = mainDependencies.snapshot
			

	    val (allSubprojectsJars, compilerInterfaces) = jarsOfProjectDependencies.get.toSeq.map(_.asFile).partition(!isJarX(_, "compiler-interface-bin-" + version))
	    val (nonScalaLibs, scalaJars) = findExternalLibs.partition(!isJarX(_, "scala-compiler"))
	    val allJarsToPack = allSubprojectsJars.toSeq ++ nonScalaLibs
	    val singleCompilerInterface = compilerInterfaces.filter(_.getParent.endsWith("target"))
	    
	    val externalJars = dependencies.external ++ dependencies.scalaJars ++ scalaJars ++ singleCompilerInterface

			log.debug("proguard configuration external dependencies: \n\t" + externalJars.mkString("\n\t"))
			// partition jars from the external jar dependencies of this project by whether they are located in the project directory
			// if they are, they are specified with -injars, otherwise they are specified with -libraryjars
			val libraryJars = dependencies.libraries ++ dependencies.scalaJars
			log.debug("proguard configuration library jars locations:\n\t" + libraryJars.mkString("\n\t"))
			val proguardConfiguration = template(allJarsToPack, externalJars, outputJar.asFile)
			log.debug("Proguard configuration written to " + packageProguardConfigurationPath)
			FileUtilities.write(packageProguardConfigurationPath.asFile, proguardConfiguration, log)
		}
	
	private def findExternalLibs: Seq[File] = {
	  val allPaths = runClasspath.get.filter(p => !p.isDirectory && !p.name.contains("test")).toSeq
	  val withoutDuplicates = allPaths.foldRight(Nil: Seq[Path])((dep, prev) =>
	    if (prev.exists(pdep => pdep.name == dep.name)) prev else (prev ++ Seq(dep))
	  )
	  withoutDuplicates.map(_.asFile)
	}    
  
	private def isJarX(file: File, x: String) =
	{
		val name = file.getName
		name.startsWith(x) && name.endsWith(".jar")
	}

}