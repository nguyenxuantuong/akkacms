import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorLogging, ActorRef, Address, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.client.ClusterClientReceptionist
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Framing, Sink, Tcp}
import akka.util.{ByteString, Timeout}
import org.json4s.native.Serialization

import scala.collection.mutable
import scala.concurrent.duration._

object CoordinatorActor {
  def props(matSource: SplitterHub.Splitter[TYPED_DATA_EVENT], mat: ActorMaterializer)
    = Props(classOf[CoordinatorActor], matSource, mat)
}

class CoordinatorActor(matSource: SplitterHub.Splitter[TYPED_DATA_EVENT], mat: ActorMaterializer) extends Actor with ActorLogging {
  val cmsActors: mutable.Map[String, ActorRef] = mutable.Map.empty
  implicit val ec = context.dispatcher
  implicit val system = context.system
  implicit val timeout = Timeout(DurationInt(5).second)
  val cluster = Cluster(system)

  val workerMsgTypes = mutable.Map[ActorRef, Set[String]]()

  val receptionist = ClusterClientReceptionist(context.system)


  receptionist.registerService(self)

  override def preStart(): Unit = cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
    classOf[MemberEvent], classOf[UnreachableMember])

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive = {
    case REGISTER(msgType, actorRef) =>
      log.info("registering cms actor")
      cmsActors.put(msgType, actorRef)
      context.watch(actorRef)

    case obj: TYPED_COUNT_QUERY =>
      if(cmsActors.contains(obj.msgType)) {
        cmsActors.get(obj.msgType).get.ask(COUNT_QUERY(obj.key)).pipeTo(sender())
      } else {
        sender ! -1
      }

    case Terminated(actorRef) =>
      log.info("terminating cms actor")
      context.unwatch(actorRef)

    case Terminated(actorRef) =>
      log.info("terminating cms actor")
      context.unwatch(actorRef)
    case WorkerRegister(port, msgTypes) =>
      if (!workerMsgTypes.contains(sender())) {
        workerMsgTypes += (sender() -> msgTypes)
        registerNewConsumer(msgTypes, sender().path.address.copy(port = Some(port)))
      }
    case UnreachableMember(member) =>
      println("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      println("Member is Removed: {} after {}",
        member.address, previousStatus)
    case MemberUp(m) => {
      if (m.hasRole("worker")) {
      }
    }
  }

  def registerNewConsumer(msgTypes: Set[String], addr: Address) = {
    println("start sending to worker", sender().path.address)
    implicit val formats = Serialization.formats(org.json4s.NoTypeHints)
    implicit val m = mat
    var total = 0
    val host = addr.host.getOrElse("localhost")
    val port = addr.port.getOrElse(6000)

    var counter = new AtomicInteger(0)
    var oldCounter = new AtomicInteger(0)

    msgTypes.foreach(msgType => {
      matSource.source(e => e.msgType == msgType)
        .map(e => s"${e.msgType},${e.key},${e.freq}\n")
        .map(x => {
          counter.addAndGet(1)
          x
        })
        .map(ByteString(_))
        .via(Tcp().outgoingConnection(host, port))
        .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024, allowTruncation = true))
        .map(_.utf8String)
        .map(ByteString(_))
        .to(Sink.ignore).run()
    })

    system.scheduler.schedule(1 seconds, 1 seconds) {
      var counterVal = counter.get()
      val oldCounterVal = oldCounter.get()
      println("Rate per second is ", (counterVal - oldCounterVal))
      oldCounter.set(counterVal)
    }
  }
}
