import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Merge, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, SourceShape}
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.IndexedSeq

object ReactiveMaster extends App {
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

  val conf = ConfigFactory.parseString(s"akka.cluster.roles=[coordinator]").
    withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")).
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterSystem", conf)
  implicit val ec = system.dispatcher
  val settings = ActorMaterializerSettings(system)
  implicit val mat = ActorMaterializer(settings)

  //mock data => return several data stream
  val dataSourceList = createMockData()

  //merge all datastream into single stream
  val mergedSource = mergeSource(dataSourceList)

  val matSource = mergedSource.via(Flow[TYPED_DATA_EVENT].map(identity))
    .toMat(SplitterHub.sink[TYPED_DATA_EVENT])(Keep.right).run()

  val coordinator = system.actorOf(
    ClusterSingletonManager.props(
      CoordinatorActor.props(matSource, mat),
      PoisonPill,
      ClusterSingletonManagerSettings(system).withRole("coordinator")
    ),
    "coordinator")
  // val coordinator = system.actorOf(Props(new CoordinatorActor(matSource, mat)))
}
