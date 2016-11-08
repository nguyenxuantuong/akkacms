import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Merge, Source, MergeHub}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, SourceShape}
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.IndexedSeq
import akka.actor.{ActorRef, ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Sink, Tcp}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory
import org.json4s.native.Serialization
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ReactiveMaster extends App {
  val port = args.head.toInt

  //data tcp port will port where tcp server will listening for incomming data stream
  val dataTcpPort = args(1).toInt

  val conf = ConfigFactory.parseString(s"akka.cluster.roles=[coordinator]").
    withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")).
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", conf)
  implicit val ec = system.dispatcher
  val settings = ActorMaterializerSettings(system)
  implicit val mat = ActorMaterializer(settings)

  // Obtain a Sink and Source which will publish and receive from the "bus" respectively.
  val (matSink, matSource) =
    MergeHub.source[TYPED_DATA_EVENT](perProducerBufferSize = 256)
      .toMat(SplitterHub.sink[TYPED_DATA_EVENT](bufferSize = 256))(Keep.both)
      .run()

  val coordinator = system.actorOf(
    ClusterSingletonManager.props(
      CoordinatorActor.props(matSource, mat),
      PoisonPill,
      ClusterSingletonManagerSettings(system).withRole("coordinator")
    ),
    "coordinator")
  // val coordinator = system.actorOf(Props(new CoordinatorActor(matSource, mat)))

  val handler = Sink.foreach[Tcp.IncomingConnection] { conn =>
    println("Input stream client connected from: " + conn.remoteAddress)
    implicit val formats = Serialization.formats(org.json4s.NoTypeHints)
    implicit val askTimeout = Timeout(5.seconds)
    conn handleWith Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024, allowTruncation = true))
      .map(_.utf8String)
      .map(TYPED_DATA_EVENT(_))
      .alsoTo(matSink)
      .map(_ => ByteString.empty)
  }

  //the master also accepts incomming data via TCP stream
  val dataTcpAddress = "0.0.0.0"
  val connections = Tcp().bind(dataTcpAddress, dataTcpPort)
  val binding = connections.to(handler).run()

  binding.onComplete {
    case Success(b) =>
      println("Server started, listening on: " + b.localAddress)
    case Failure(e) =>
      println(s"Server could not bind to $dataTcpAddress:$dataTcpPort: ${e.getMessage}")
      system.terminate()
  }
}
