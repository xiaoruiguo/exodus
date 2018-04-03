package com.wix.bazel.migrator

import java.nio.file.Files
import java.time.temporal.ChronoUnit

import better.files.File
import com.wix.bazel.migrator.transform.CodotaDependencyAnalyzer
import com.wix.build.maven.analysis.{SourceModules, ThirdPartyConflict, ThirdPartyConflicts, ThirdPartyValidator}
import com.wixpress.build.maven
import com.wixpress.build.maven._
import com.wixpress.build.sync.HighestVersionConflictResolution

trait MigratorApp extends App {
  lazy val configuration = RunConfiguration.from(args)

  val aetherResolver = new AetherMavenDependencyResolver(List(
    "http://repo.dev.wixpress.com:80/artifactory/libs-releases",
    "http://repo.dev.wixpress.com:80/artifactory/libs-snapshots"),
    resolverRepo)

  private def resolverRepo: File = {
    val f = File("resolver-repo")
    Files.createDirectories(f.path)
    f
  }

  // Conveniences --
  def repoRoot = configuration.repoRoot

  def managedDepsRepoRoot = configuration.managedDepsRepo

  lazy val sourceModules = readSourceModules()

  lazy val directDependencies = collectExternalDependenciesUsedByRepoModules()

  lazy val externalDependencies =
    new DependencyCollector(aetherResolver)
      .addOrOverrideDependencies(constantDependencies)
      .addOrOverrideDependencies(new HighestVersionConflictResolution().resolve(directDependencies))
      .mergeExclusionsOfSameCoordinates()
      .dependencySet()

  private def collectExternalDependenciesUsedByRepoModules() =
    codeModules.flatMap(_.dependencies.directDependencies).filterExternalDeps(codeModules.map(_.coordinates))

  lazy val externalSourceDependencies: Set[Coordinates] =
    if (configuration.interRepoSourceDependency) externalDependencies.collect {
      case d if hasSourceDependencyProperties(d.coordinates) => d.coordinates
    } else Set.empty

  lazy val externalBinaryDependencies: Set[Coordinates] = externalDependencies.map(_.coordinates) diff externalSourceDependencies

  private def hasSourceDependencyProperties(artifact: Coordinates) = {
    artifact.version.endsWith("-SNAPSHOT")
  }

  def codeModules = sourceModules.codeModules

  def codotaToken = configuration.codotaToken

  lazy val codotaDependencyAnalyzer = new CodotaDependencyAnalyzer(repoRoot, codeModules, codotaToken)
  val thirdPartyDependencySource = Coordinates.deserialize("com.wixpress.common:third-party-dependencies:pom:100.0.0-SNAPSHOT")
  val managedDependenciesArtifact = Coordinates.deserialize("com.wixpress.common:wix-base-parent-ng:pom:100.0.0-SNAPSHOT")

  private def staleFactorInHours = sys.props.getOrElse("num.hours.classpath.cache.is.fresh", "24").toInt

  private def readSourceModules() = {
    val sourceModules = if (configuration.performMavenClasspathResolution ||
      Persister.mavenClasspathResolutionIsUnavailableOrOlderThan(staleFactorInHours, ChronoUnit.HOURS)) {
      val modules = SourceModules.of(repoRoot)
      Persister.persistMavenClasspathResolution(modules)
      modules
    } else {
      Persister.readTransMavenClasspathResolution()
    }
    sourceModules
  }

  protected def checkConflictsInThirdPartyDependencies(resolver: MavenDependencyResolver): ThirdPartyConflicts = {
    val managedDependencies = aetherResolver.managedDependenciesOf(thirdPartyDependencySource).map(_.coordinates)
    val thirdPartyConflicts = new ThirdPartyValidator(codeModules, managedDependencies).checkForConflicts()
    print(thirdPartyConflicts)
    thirdPartyConflicts
  }

  private def print(thirdPartyConflicts: ThirdPartyConflicts): Unit = {
    printIfNotEmpty(thirdPartyConflicts.fail, "FAIL")
    printIfNotEmpty(thirdPartyConflicts.warn, "WARN")
  }


  private def printIfNotEmpty(conflicts: Set[ThirdPartyConflict], level: String): Unit = {
    if (conflicts.nonEmpty) {
      println(s"[$level] ********  Found conflicts with third party dependencies ********")
      conflicts.map(_.toString).toList.sorted.foreach(println)
      println(s"[$level] ***********************************************************")
    }
  }

  // hack to add hoopoe-specs2 (and possibly other needed dependencies)
  protected def constantDependencies: Set[Dependency] = {
    aetherResolver.managedDependenciesOf(managedDependenciesArtifact)
      .filter(_.coordinates.artifactId == "hoopoe-specs2")
      .filter(_.coordinates.packaging.contains("pom")) +
      //proto dependencies
      maven.Dependency(Coordinates.deserialize("com.wixpress.grpc:dependencies:pom:1.0.0-SNAPSHOT"), MavenScope.Compile) +
      maven.Dependency(Coordinates.deserialize("com.wixpress.grpc:generator:1.0.0-SNAPSHOT"), MavenScope.Compile) +
      //core-server-build-tools dependency
      maven.Dependency(Coordinates.deserialize("com.google.jimfs:jimfs:1.1"), MavenScope.Compile)
  }

  implicit class DependencySetExtensions(dependencies: Set[Dependency]) {
    def filterExternalDeps(repoCoordinates: Set[Coordinates]) = {
      dependencies.filterNot(dep => repoCoordinates.exists(_.equalsOnGroupIdAndArtifactId(dep.coordinates)))
    }
  }

}
