package cuenen.raymond.akka.gpsd

import java.net.InetSocketAddress

import akka.actor._
import akka.dispatch.{UnboundedMessageQueueSemantics, RequiresMessageQueue}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import org.json4s._
import org.json4s.ext.EnumSerializer
import org.json4s.native.JsonMethods._
import org.json4s.reflect.Reflector

private[gpsd] class GpsdConnection(_gpsd: GpsdExt,
                                    commander: ActorRef,
                                   connect: Connect)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import _gpsd.Settings._
  import Tcp._
  import context.system

  implicit val formats = new DefaultFormats {
    override def dateFormatter = DefaultFormats.losslessDate()
  } + new EnumSerializer(NMEAMode)

  IO(Tcp) ! Tcp.Connect(remoteAddress = connect.remoteAddress.getOrElse(new InetSocketAddress(DefaultHostname, DefaultPort)),
                        localAddress = connect.localAddress,
                        options = connect.options,
                        timeout = connect.timeout,
                        pullMode = connect.pullMode)

  def receive = {
    case connectFailed @ CommandFailed(_: Tcp.Connect) =>
      log.debug("connect failed")
      commander ! connectFailed
      context stop self

    case connected: Connected =>
      commander ! connected
      val connection = sender()
      connection ! Register(self)
      context become {
        case request: GpsdCommand =>
          connection ! Write(ByteString(request.command))
        case writeFailed @ CommandFailed(_: Write) =>
          // OS kernel buffer was full
          log.debug("write failed")
          commander ! writeFailed
        case Received(data) =>
          data.utf8String.split("\n").foreach(commander ! extract(_))
        case Close =>
          connection ! Close
        case connectionClosed: ConnectionClosed =>
          log.debug("connection closed")
          commander ! connectionClosed
          context stop self
      }
  }

  def extract(data: String): GpsdObject = {
    val json = parse(data)
    (json \ "class").extractOpt[String] match {
      case Some(classifier: String) => Sentences.get(classifier) match {
        case Some(clazz) => Extraction.extract(json, Reflector.scalaTypeOf(Class.forName(clazz))).asInstanceOf[GpsdObject]
        case None => Unknown(json)
      }
      case None => Unknown(json)
    }
  }
}
