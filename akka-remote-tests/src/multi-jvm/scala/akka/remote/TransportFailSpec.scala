/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.actor.Terminated
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystem
import akka.actor.RootActorPath

class TransportFailConfig(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
      akka.loglevel = INFO
      akka.remote.transport-failure-detector.heartbeat-interval = 1 s
      akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 3 s
      akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 60 s
      akka.remote.artery.enabled = $artery
    """)))

}

class TransportFailMultiJvmNode1 extends TransportFailSpec(
  new TransportFailConfig(artery = false))
class TransportFailMultiJvmNode2 extends TransportFailSpec(
  new TransportFailConfig(artery = false))

// FIXME this is failing with Artery
//class ArteryTransportFailMultiJvmNode1 extends TransportFailSpec(
//  new TransportFailConfig(artery = true))
//class ArteryTransportFailMultiJvmNode2 extends TransportFailSpec(
//  new TransportFailConfig(artery = true))

object TransportFailSpec {
  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒
        sender() ! "shutdown-ack"
        context.system.terminate()
      case msg ⇒ sender() ! msg
    }
  }
}

abstract class TransportFailSpec(multiNodeConfig: TransportFailConfig)
  extends RemotingMultiNodeSpec(multiNodeConfig) {
  import multiNodeConfig._
  import TransportFailSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "TransportFail" must {

    "reconnect" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started")

        val subject = identify(second, "subject")
        watch(subject)
        subject ! "hello"
        expectMsg("hello")
      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")
      }

      enterBarrier("watch-established")
      Thread.sleep(3000 + 6000)

      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started2")

        val subject = identify(second, "subject2")
        watch(subject)
        subject ! "hello2"
        expectMsg("hello2")
      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject2")
        enterBarrier("actors-started2")
      }

      enterBarrier("done")

    }

  }
}
