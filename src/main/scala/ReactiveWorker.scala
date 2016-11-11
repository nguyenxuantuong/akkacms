import akka.actor.{ActorRef, ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.cluster.client.ClusterClient.SendToAll
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.japi.Util._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.{ActorSubscriber, MaxInFlightRequestStrategy, RequestStrategy}
import akka.stream.scaladsl.{Flow, Framing, Sink, Tcp}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory
import org.json4s.native.Serialization

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class ReactiveWorkerSubscriber extends ActorSubscriber {
  val MAX_QUEUE_SIZE = 100
  var inFlight = 0
  var total = 0

  override protected def requestStrategy: RequestStrategy = new MaxInFlightRequestStrategy(MAX_QUEUE_SIZE) {
    override def inFlightInternally: Int = inFlight
  }

  override def receive: Receive = {
    case OnNext(me: String) =>
      inFlight += 1
      total += 1
      inFlight -= 1
      println("Worker received: ", total, me)
  }
}

object ReactiveWorkerSubscriber {
  def props = Props(classOf[ReactiveWorkerSubscriber])
}

case class WorkerRegister(port: Int, msgTypes: Set[String])

object ReactiveWorker extends App {
  val port = args.head.toInt
  val msgTypes = args.tail.toSet
  val conf = ConfigFactory.parseString(s"akka.cluster.roles=[worker]").
    withFallback(ConfigFactory.parseString("akka.remote.netty.tcp.port=0")).
    withFallback(ConfigFactory.load())

  implicit val formats = Serialization.formats(org.json4s.NoTypeHints)
  implicit val system = ActorSystem("ClusterSystem", conf)
  implicit val mat = ActorMaterializer()
  val address = "0.0.0.0"

  val initialContacts = immutableSeq(conf.getStringList("contact-points")).map {
    case AddressFromURIString(addr) => RootActorPath(addr) / "system" / "receptionist"
  }.toSet
  val clusterClient = system.actorOf(
    ClusterClient.props(
      ClusterClientSettings(system).withInitialContacts(initialContacts)),
    "clusterClient")

  implicit val ec = system.dispatcher

  val cmsActors: Map[String, ActorRef] = msgTypes.map(msgType => (msgType, system.actorOf(CmsActor.props(msgType)))).toMap

  val handler = Sink.foreach[Tcp.IncomingConnection] { conn =>
    println("Client connected from: " + conn.remoteAddress)
    implicit val formats = Serialization.formats(org.json4s.NoTypeHints)
    implicit val askTimeout = Timeout(5.seconds)
    conn handleWith Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024, allowTruncation = true))
      .map(_.utf8String)
      .map(TYPED_DATA_EVENT(_))
      .mapAsync(100)(e => (cmsActors(e.msgType) ? e).mapTo[String])
      .map(_ => ByteString.empty)
  }

  val connections = Tcp().bind(address, port)
  val binding = connections.to(handler).run()

  binding.onComplete {
    case Success(b) =>
      println("Server started, listening on: " + b.localAddress)
      system.scheduler.schedule(0.seconds, 1.seconds, clusterClient,
        SendToAll("/user/coordinator/singleton", WorkerRegister(port, msgTypes)))
    case Failure(e) =>
      println(s"Server could not bind to $address:$port: ${e.getMessage}")
      system.terminate()
  }
}
