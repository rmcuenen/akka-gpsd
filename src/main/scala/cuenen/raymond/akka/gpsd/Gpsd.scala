package cuenen.raymond.akka.gpsd

import java.net.InetSocketAddress

import akka.actor._
import akka.io.IO
import akka.io.Inet.SocketOption
import com.typesafe.config.Config
import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

object Gpsd extends ExtensionId[GpsdExt] with ExtensionIdProvider {

  override def lookup = Gpsd

  override def createExtension(system: ExtendedActorSystem): GpsdExt = new GpsdExt(system)

  /**
   * Java API: retrieve the Gpsd extension for the given system.
   */
  override def get(system: ActorSystem): GpsdExt = super.get(system)
}

case class Connect(remoteAddress: Option[InetSocketAddress] = None,
                   localAddress: Option[InetSocketAddress] = None,
                   options: immutable.Traversable[SocketOption] = Nil,
                   timeout: Option[FiniteDuration] = None,
                   pullMode: Boolean = false)
/**
 * The common interface for [[GpsdCommand]], [[GpsdResponse]] and [[GpsdReport]].
 */
sealed trait GpsdObject

trait GpsdCommand extends GpsdObject {
  val RequestChar = "?"
}

trait GpsdResponse extends GpsdObject {
  def command: String
}

trait GpsdReport extends GpsdObject {

}

class GpsdExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.gpsd"))
  class Settings private[GpsdExt] (_config: Config) {
    import _config._

    val DefaultHostname: String = getString("hostname")
    val DefaultPort: Int = getInt("port")
    val Sentences: Map[String, String] =
      (for (e <- getConfig("json").entrySet()) yield (e.getKey, e.getValue.unwrapped.asInstanceOf[String])).toMap
  }

  val manager: ActorRef =
    system.systemActorOf(
      props = Props(classOf[GpsdManager], this).withDeploy(Deploy.local),
      name = "IO-GPSd")

  /**
   * Java API: retrieve a reference to the manager actor.
   */
  def getManager: ActorRef = manager
}

object GpsdMessage {

}