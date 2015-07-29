package cuenen.raymond.akka.gpsd

import akka.actor._

private[gpsd] class GpsdManager(gpsd: GpsdExt) extends Actor with ActorLogging {

  def receive = {
    case c: Connect =>
      val commander = sender()
      context actorOf(Props(classOf[GpsdConnection], gpsd, commander, c).withDeploy(Deploy.local))
  }

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
}
