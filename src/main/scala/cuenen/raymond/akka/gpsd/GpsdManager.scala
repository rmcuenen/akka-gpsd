package cuenen.raymond.akka.gpsd

import akka.actor._
import cuenen.raymond.akka.gpsd.Gpsd.Connect

/**
 * INTERNAL API
 *
 * GpsdManager is a facade for accepting the command ([[Connect]]) to open client GPSd connections.
 *
 * GpsdManager is obtainable by calling {{{ IO(Gpsd) }}} (see [[akka.io.IO]] and [[cuenen.raymond.akka.gpsd.Gpsd]])
 *
 * To initiate a connection to a remote server, a [[Connect]] message must be sent to this actor. If the
 * connection succeeds, the sender() will be notified with a [[akka.io.Tcp.Connected]] message. The sender of the
 * [[akka.io.Tcp.Connected]] message is the Connection actor (an internal actor representing the GPSd connection).
 * All incoming data will be sent to the sender() in the form of [[cuenen.raymond.akka.gpsd.Gpsd.GpsdObject]] messages.
 * To write data to the connection, a [[cuenen.raymond.akka.gpsd.Gpsd.GpsdCommand]] message must be sent to the Connection actor.
 *
 * If the connect request is rejected because the underlying Tcp system is not able to register more channels
 * (see the nr-of-selectors and max-channels configuration options in the akka.io.tcp section of the configuration)
 * the sender will be notified with a [[akka.io.Tcp.CommandFailed]] message.
 */
private[gpsd] class GpsdManager(gpsd: GpsdExt) extends Actor with ActorLogging {

  def receive = {
    case c: Connect =>
      val commander = sender()
      context actorOf(Props(classOf[GpsdConnection], gpsd, commander, c).withDeploy(Deploy.local))
  }

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
}
