//Support remote actor via Akka Stream's TCP
import akka.actor.ActorRef

import scala.collection.immutable.List

//event type inside actors
case class COUNT_QUERY(key: String)
case class DATA_EVENT(key: String, freq: Long)

case class REGISTER(msgType: String, actorRef: ActorRef)

//those messages will have msgType (such as IP, Domain, etc...) so that we know which actor to forward to
case class TYPED_DATA_EVENT(msgType: String, key: String, freq: Long)
object  TYPED_DATA_EVENT {
  def apply(s: String): TYPED_DATA_EVENT = {
    val ss = s.split(",")
    TYPED_DATA_EVENT(ss(0), ss(1), ss(2).toLong)
  }
}
case class TYPED_COUNT_QUERY(msgType: String, key: String)

//models class
trait Log {
  def convert(): List[TYPED_DATA_EVENT]
}

case class HamLog(ip: String, sender: String) extends Log {
  override def convert(): List[TYPED_DATA_EVENT] = {
    List(TYPED_DATA_EVENT("ham:ip", ip, 1), TYPED_DATA_EVENT("ham:sender", sender, 1))
  }
}

case class AvLog(domain: String, virus: String) extends Log {
  override def convert(): List[TYPED_DATA_EVENT] = {
    List(TYPED_DATA_EVENT("av:domain", domain, 1), TYPED_DATA_EVENT("av:virus", virus, 1))
  }
}


