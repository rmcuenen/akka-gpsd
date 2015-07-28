package cuenen.raymond.akka.gpsd

import java.net.InetSocketAddress

import akka.actor._
import akka.dispatch.{UnboundedMessageQueueSemantics, RequiresMessageQueue}
import akka.io.{IO, Tcp}
import akka.util.ByteString

private[gpsd] class GpsdConnection(_gpsd: GpsdExt,
                                    commander: ActorRef,
                                   connect: Connect)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import _gpsd.Settings._
  import Tcp._
  import context.system

  IO(Tcp) ! Tcp.Connect(remoteAddress = connect.remoteAddress.getOrElse(new InetSocketAddress(DefaultHostname, DefaultPort)),
                        localAddress = connect.localAddress,
                        options = connect.options,
                        timeout = connect.timeout,
                        pullMode = connect.pullMode)

  def receive = {
    case _ @ CommandFailed(_: Tcp.Connect) =>
      log.debug("connect failed")
      commander ! _
      context stop self

    case _: Connected =>
      commander ! _
      val connection = sender()
      connection ! Register(self)
      context become {
        case request: GpsdCommand =>
          //TODO
          connection ! Write(ByteString.empty)
        case c @ CommandFailed(_: Write) =>
          // OS kernel buffer was full
          log.debug("write failed")
          commander ! c
        case Received(data) =>
          //TODO
          commander ! data
        case Close =>
          connection ! Close
        case _: ConnectionClosed =>
          log.debug("connection closed")
          commander ! _
          context stop self
      }
  }
}
