package cuenen.raymond.akka.gpsd

import java.net.InetSocketAddress
import java.util.Date

import akka.actor._
import akka.io.IO
import akka.io.Inet.SocketOption
import com.typesafe.config.Config
import org.json4s.JsonAST.JValue
import org.json4s.NoTypeHints
import org.json4s.native.Serialization
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
sealed trait GpsdObject {
  implicit val formats = Serialization.formats(NoTypeHints)
  def asJSON: String = Serialization.write(this)
}

trait GpsdCommand extends GpsdObject {
  def command: String
}

sealed trait GpsdEvent extends GpsdObject {
  def classifier: String
}

trait GpsdResponse extends GpsdEvent

trait GpsdReport extends GpsdEvent

object Version extends GpsdCommand {
  val command = "?VERSION;\n"
}
case class Version(release: String, rev: String, proto_major: Int, proto_minor: Int, remote: Option[String])
  extends GpsdResponse {

  override def classifier = "VERSION"
  def protocolMajor = proto_major
  def protocolMinor = proto_minor
}

object Devices extends GpsdCommand {
  val command = "?DEVICES;\n"
}
case class Devices(devices: List[Device], remote: Option[String]) extends GpsdResponse {

  override def classifier = "DEVICES"
}

object Watch extends GpsdCommand {
  val command = "?WATCH;\n"
}
case class Watch(enable: Option[Boolean], json: Option[Boolean], nmea: Option[Boolean], raw: Option[Int],
                 scaled: Option[Boolean], split24: Option[Boolean], pps: Option[Boolean], device: Option[String],
                 remote: Option[String], timing: Option[Boolean]) extends GpsdResponse {

  override def classifier = "WATCH"

}
case class SetWatch(enable: Boolean, json: Option[Boolean] = None) extends GpsdCommand {
  val command = "?WATCH=" + asJSON + "\n"
}

object Poll extends GpsdCommand {
  val command = "?POLL;\n"
}
case class Poll(time: Date, active: Int, tpv: List[TPV], gst: List[GST], sky: List[SKY]) extends GpsdResponse {

  override def classifier = "POLL"
  def timestamp = time.getTime / 1000.0
  def fixes = tpv
  def skyviews = sky
}

object Device extends GpsdCommand {
  val command = "?DEVICE;\n"
}
case class Device(path: Option[String], activated: Option[String], flags: Option[Int], driver: Option[String],
                   subtype: Option[String], bps: Option[Int], parity: String, stopbits: Int, native: Option[Int],
                   cycle: Option[Double], mincycle: Option[Double]) extends GpsdResponse {

  override def classifier = "DEVICE"
}

case class Error(message: String) extends GpsdResponse {

  override def classifier = "ERROR"
}

case class Unknown(sentence: JValue) extends GpsdObject {
  override def toString = Serialization.write(sentence)
}

object NMEAMode extends Enumeration {
  type Mode = Value
  val NotSeen, NoFix, TwoDimensional, ThreeDimensional = Value
}

case class TPV(tag: String, device: Option[String], mode: NMEAMode.Mode, time: Option[Date], ept: Option[Double],
               lat: Option[Double], lon: Option[Double], alt: Option[Double], epx: Option[Double],
               epy: Option[Double], epv: Option[Double], track: Option[Double], speed: Option[Double],
               climb: Option[Double], epd: Option[Double], eps: Option[Double], epc: Option[Double]) extends GpsdReport {

  override def classifier = "TPV"
  def timestamp = time.map(_.getTime / 1000.0)
  def timestampError = ept
  def latitude = lat
  def longitude = lon
  def altitude = alt
  def longitudeError = epx
  def latitudeError = epy
  def altitudeError = epv
  def course = track
  def climbRate = climb
  def courseError = epd
  def speedError = eps
  def climbRateError = epc
}

case class SKY(tag: String, device: Option[String], time: Option[Date], xdop: Option[Double], ydop: Option[Double],
               vdop: Option[Double], tdop: Option[Double], hdop: Option[Double], pdop: Option[Double],
               gdop: Option[Double], satellites: List[Satellite]) extends GpsdReport {

  override def classifier = "SKY"
  def timestamp = time.map(_.getTime / 1000.0)
  def longitudeDOP = xdop
  def latitudeDOP = ydop
  def altitudeDOP = vdop
  def timestampDOP = tdop
  def horizontalDOP = hdop
  def sphericalDOP = pdop
  def hypersphericalDOP = gdop
}

case class Satellite(PRN: Int, az: Int, el: Int, ss: Int, used: Boolean) {

  def azimuth = az
  def elevation = el
  def signalStrength = ss
}

case class GST(tag: String, device: Option[String], time: Option[Date], rms: Option[Double], major: Option[Double],
               minor: Option[Double], orient: Option[Double], lat: Option[Double], lon: Option[Double],
               alt: Option[Double]) extends GpsdReport {

  override def classifier = "GST"
  def timestamp = time.map(_.getTime / 1000.0)

}

case class ATT(tag: String, device: String, time: Date, heading: Option[Double], mag_st: Option[String],
               pitch: Option[Double], pitch_st: Option[String], yaw: Option[Double], yaw_st: Option[String],
               roll: Option[Double], roll_st: Option[String], dip: Option[Double], mag_len: Option[Double],
               mag_x: Option[Double], mag_y: Option[Double], mag_z: Option[Double], acc_len: Option[Double],
               acc_x: Option[Double], acc_y: Option[Double], acc_z: Option[Double], gyro_x: Option[Double],
               gyro_y: Option[Double], depth: Option[Double], temperature: Option[Double]) extends GpsdReport {

  override def classifier = "ATT"
  def timestamp = time.getTime / 1000.0
  def magState = mag_st
  def pitchState = pitch_st
  def yawState = yaw_st
  def rollState = roll_st

}

class GpsdExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.gpsd"))
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