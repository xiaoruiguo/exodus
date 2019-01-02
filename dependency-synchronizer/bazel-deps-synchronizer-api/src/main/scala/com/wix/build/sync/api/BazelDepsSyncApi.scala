package com.wix.build.sync.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.wixpress.build.maven.Coordinates
trait BazelDepsSyncApi {
  def getManagedThirdPartyArtifacts() : Set[ThirdPartyArtifact]
}


object BazelSyncGreyhoundEvents{
  val BazelManagedDepsSyncEndedTopic : String = "BazelManagedDepsSyncEndedTopic"
}

case class ThirdPartyArtifact(coordinates : Coordinates, digest : Option[String]){
  def label() : String = {
    val gid = coordinates.groupId.replaceAll("-","_").replaceAll("\\.","_")
    val aid = coordinates.artifactId.replaceAll("-","_").replaceAll("\\.","_")
    s"@${gid}_${aid}"
  }
}

case class BazelManagedDepsSyncEnded(thirdPartyArtifacts : Set[ThirdPartyArtifact])