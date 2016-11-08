//it is client to send data into the platform via TCP
import scala.util._
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util._
import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Merge, MergeHub, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, SourceShape}
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.{IndexedSeq, List}
import akka.actor.{ActorRef, ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Sink, Tcp}
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ReactiveClient extends App {
  implicit val client = ActorSystem("SimpleTcpClient")
  implicit val materializer = ActorMaterializer()

  //address of the master where will send data into via TCP
  val address = "127.0.0.1"
  val port = args.head.toInt

  //merge input sources into one
  def mergeSource(inputSources: IndexedSeq[Source[Log, NotUsed]]): Source[TYPED_DATA_EVENT, NotUsed] = {
    Source.fromGraph(GraphDSL.create() {implicit builder =>
      import GraphDSL.Implicits._

      val merge = builder.add(Merge[TYPED_DATA_EVENT](inputSources.size))

      inputSources.map(x => x.mapConcat[TYPED_DATA_EVENT](_.convert()))
        .foreach(source => {
          source ~> merge
        })

      SourceShape(merge.out)
    })
  }

  def createMockData(): IndexedSeq[Source[Log, NotUsed]] = {
    val hamData = Seq("127.0.0.1", "54.200.201.215", "209.143.65.125", "127.0.0.1", "127.0.0.1") zip
      Seq("A@who.is", "B@who.is", "C@whois", "A@whois", "A@whois")

    val avData = Seq("google.com", "fb.com", "bbc.com", "fb.com", "google.com") zip
      Seq("Zeus", "Sasser", "Conficker", "Stuxnet", "Stuxnet")

    //create an infinitive input streams
    IndexedSeq(Source.cycle(() =>  hamData.map(x => HamLog(x._1, x._2)).toIterator),
      Source.cycle(() => avData.map(x => AvLog(x._1, x._2)).toIterator))
  }

  //mock data => return several data stream
  val dataSourceList = createMockData()

  //merge all data-stream into single stream
  val mergedSource = mergeSource(dataSourceList)

  val matSource = mergedSource.via(Flow[TYPED_DATA_EVENT].map(identity))
    .toMat(SplitterHub.sink[TYPED_DATA_EVENT])(Keep.right).run()

  val msgTypes = List("ham:ip", "ham:sender", "av:domain", "av:virus")

  //establish 4 TCP connections with the server to mock 4 different streams
  //server will merge all those stream together before splitting
  //the load will be splitted into a set of downstream consumers
  msgTypes.foreach {msgType =>
    matSource.source(x => x.msgType == msgType)
      .map(e => s"${e.msgType},${e.key},${e.freq}\n")
      .map(ByteString(_))
      .via(Tcp().outgoingConnection(address, port))
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024, allowTruncation = true))
      .map(_.utf8String)
      .map(ByteString(_))
      .to(Sink.ignore).run()
  }
}