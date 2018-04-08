package com.wixpress.build.codota


import java.net.SocketTimeoutException

import org.specs2.mutable.{BeforeAfter, SpecificationWithJUnit}

import scala.util.Random


class CodotaThinClientIT extends SpecificationWithJUnit {

  "client" should {
    "return path for given artifact name" in new Ctx {
      client.pathFor(artifactName) must beSome(path)
    }

    "throw ArtifactNotFoundException in case given artifact that was not found" in new Ctx {
      client.pathFor("some.bad.artifact") must throwA[ArtifactNotFoundException]
    }

    "retry in case of timeouts" in new Ctx {
      codotaFakeServer.delayTheNextNCalls(n = 1)
      client.pathFor(artifactName) must beSome(path)
    }


    "throw TimeoutException in case still getting timeout after given max retries" in new Ctx {
      override def client = new CodotaThinClient(validToken, serverCodePack , codotaFakeServer.url,maxRetries = 2)

      codotaFakeServer.delayTheNextNCalls(n = 3)
      client.pathFor(artifactName) must throwA[SocketTimeoutException]
    }

    "throw NotAuthorizedException in case given invalid token" in new Ctx {
      override def client = new CodotaThinClient("someInvalidToken", serverCodePack, codotaFakeServer.url)

      client.pathFor(artifactName) must throwA[NotAuthorizedException]
    }

    "throw CodePackNotFoundException in case given unknown codePack" in new Ctx {
      override def client = new CodotaThinClient(validToken, codePack = "invalid", codotaFakeServer.url)

      client.pathFor(artifactName) must throwA[CodePackNotFoundException]
    }

    "throw NotAuthorizedException in case given empty codePack" in new Ctx {
      override def client = new CodotaThinClient(validToken, codePack = "", baseURL = codotaFakeServer.url)

      client.pathFor(artifactName) must throwA[NotAuthorizedException]
    }
  }

  trait Ctx extends BeforeAfter {
    val artifactName = "some.group.artifact-name"

    val path = "some/path/to/artifact"
    val serverCodePack = "some_code_pack"


    def client: CodotaThinClient = new CodotaThinClient(validToken, serverCodePack, codotaFakeServer.url)

    val validToken = "validToken"
    val codotaFakeServer = new CodotaFakeServer(selectRandomPort(), serverCodePack, artifactName, path, validToken)

    override def before(): Unit = codotaFakeServer.start()

    override def after(): Unit = codotaFakeServer.stop()

    private def selectRandomPort() = {
      val rnd = new Random()
      val startPortRange = 55000
      val endPortRange   = 56000
      startPortRange + rnd.nextInt( (endPortRange - startPortRange) + 1 )
    }
  }

}

