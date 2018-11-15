package com.wixpress.build.sync

import com.wix.bazel.migrator.model.SourceModule
import com.wixpress.build.BazelWorkspaceDriver
import com.wixpress.build.BazelWorkspaceDriver._
import com.wixpress.build.bazel.{FakeLocalBazelWorkspace, ImportExternalRule, InMemoryBazelRepository}
import com.wixpress.build.maven.FakeMavenDependencyResolver._
import com.wixpress.build.maven.MavenMakers._
import com.wixpress.build.maven.{DependencyNode, _}
import org.specs2.mutable.SpecWithJUnit
import org.specs2.specification.Scope

//noinspection TypeAnnotation
class UserAddedDepsDiffSynchronizerTest extends SpecWithJUnit {

  "ThirdPartyUpdater" >> {
    "when persisting changes" should {
      "add third party dependencies to repo" in new ctx {
        val newArtifacts = Set(artifactA, artifactB)

        synchronizer.syncThirdParties(newArtifacts.map(toDependency))

        newArtifacts.map { c => targetRepoDriver.bazelExternalDependencyFor(c).importExternalRule } must contain(beSome[ImportExternalRule]).forall
      }

      "only add unmanaged dependencies to local repo" in new ctx {
        managedDepsLocalWorkspace.hasDependencies(aRootDependencyNode(asCompileDependency(artifactA)))

        synchronizer.syncThirdParties(Set(artifactA, artifactB).map(toDependency))

        targetRepoDriver.bazelExternalDependencyFor(artifactA).importExternalRule must beNone
        targetRepoDriver.bazelExternalDependencyFor(artifactB).importExternalRule must beSome[ImportExternalRule]
      }

      "update dependency's version in local repo" in new ctx {
        targetFakeLocalWorkspace.hasDependencies(aRootDependencyNode(asCompileDependency(artifactA)))

        val updatedArtifact = artifactA.copy(version = "2.0.0")
        synchronizer.syncThirdParties(Set(updatedArtifact).map(toDependency))

        targetRepoDriver must includeImportExternalTargetWith(updatedArtifact)
      }
    }

    "calculate difference from managed" in new ctx {
      val newArtifacts = Set(artifactA, artifactB)

      private val nodes: Set[DependencyNode] = newArtifacts.map(a => aRootDependencyNode(asCompileDependency(a)))
      synchronizer.resolveUpdatedLocalNodes(newArtifacts.map(toDependency)) mustEqual DiffResult(nodes, Set(), Set())
    }

    "resolve local deps closure when a local transitive dependency is only found in managed set" in new ctx {
      targetFakeLocalWorkspace.hasDependencies(DependencyNode(asCompileDependency(artifactA), Set(asCompileDependency(artifactB))))
      managedDepsLocalWorkspace.hasDependencies(aRootDependencyNode(asCompileDependency(artifactB)))

      synchronizer.resolveUpdatedLocalNodes(Set()).localNodes must contain(DependencyNode(asCompileDependency(artifactA), Set(asCompileDependency(artifactB))))
    }

    "when aggregating local nodes and user-added nodes" should {
      "include user-added node without its transitive dep due to exclusion in local node" in new ctx {
        val localDependency: Dependency = asCompileDependency(artifactA)
        val transitiveDependency = asCompileDependency(someCoordinates("transitive"))
        val localNodes = Set(aRootDependencyNode(localDependency.copy(exclusions = Set(Exclusion(transitiveDependency)))))
        val userAddedDeps = Set(localDependency)
        val userAddedNodes = Set(DependencyNode(localDependency, Set(transitiveDependency)), aRootDependencyNode(transitiveDependency))
        synchronizer.aggregateLocalAndUserAddedNodes(localNodes, userAddedDeps, userAddedNodes) mustEqual localNodes
      }

      // TODO can be removed in phase 2
      "include user-added node without its transitive dep due to it already existing as source module in local repo" in new sourceModulesCtx {
        val localDependency: Dependency = asCompileDependency(artifactA)
        private val transitiveArtifact: Coordinates = someCoordinates("transitive")
        val transitiveDependency = asCompileDependency(transitiveArtifact)

        val sourceModules = Set(SourceModule(relativePathFromMonoRepoRoot = "", coordinates = transitiveArtifact ))

        val localNodes = Set[DependencyNode](aRootDependencyNode(localDependency))
        val userAddedDeps = Set(localDependency)
        val userAddedNodes = Set(DependencyNode(localDependency, Set(transitiveDependency)), aRootDependencyNode(transitiveDependency))

        synchronizer.aggregateLocalAndUserAddedNodes(localNodes, userAddedDeps, userAddedNodes) mustEqual Set(aRootDependencyNode(localDependency))
      }

      // TODO can be removed in phase 2
      "not include user-added node due to it already existing as source module in local repo" in new sourceModulesCtx {
        val localDependency: Dependency = asCompileDependency(artifactA)

        val sourceModules = Set(SourceModule(relativePathFromMonoRepoRoot = "", coordinates = artifactA ))

        val localNodes = Set[DependencyNode]()
        val userAddedDeps = Set(localDependency)
        val userAddedNodes = Set(aRootDependencyNode(localDependency))

        synchronizer.aggregateLocalAndUserAddedNodes(localNodes, userAddedDeps, userAddedNodes) mustEqual Set()

      }
    }
  }

  trait ctx extends Scope {
    val targetFakeLocalWorkspace = new FakeLocalBazelWorkspace(localWorkspaceName = "some_local_workspace_name")
    val targetFakeBazelRepository = new InMemoryBazelRepository(targetFakeLocalWorkspace)

    val managedDepsWorkspaceName = "some_external_workspace_name"
    val managedDepsLocalWorkspace = new FakeLocalBazelWorkspace(localWorkspaceName = managedDepsWorkspaceName)
    val managedDepsFakeBazelRepository = new InMemoryBazelRepository(managedDepsLocalWorkspace)

    val dependencyManagementCoordinates = Coordinates("some.group", "deps-management", "1.0", Packaging("pom"))

    val artifactA = Coordinates("com.aaa", "A-direct", "1.0.0")
    val artifactB = Coordinates("com.bbb", "B-direct", "2.0.0")

    def toDependency(coordinates: Coordinates): Dependency = {
      // scope here is of no importance as it is used on third_party and workspace only
      Dependency(coordinates, MavenScope.Compile)
    }

    val targetRepoDriver = new BazelWorkspaceDriver(targetFakeLocalWorkspace)

    val resolver = givenFakeResolverForDependencies(rootDependencies = Set(asCompileDependency(dependencyManagementCoordinates)))
    def synchronizer = new UserAddedDepsDiffSynchronizer(targetFakeBazelRepository, managedDepsFakeBazelRepository, dependencyManagementCoordinates, resolver, _ => None, Set[SourceModule]())
  }

  trait sourceModulesCtx extends ctx {
    def sourceModules: Set[SourceModule]
    override def synchronizer = new UserAddedDepsDiffSynchronizer(targetFakeBazelRepository, managedDepsFakeBazelRepository, dependencyManagementCoordinates, resolver, _ => None, sourceModules)
  }
}