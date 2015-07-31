package cuenen.raymond.akka.gpsd

import java.net.InetSocketAddress
import java.util.Date
import akka.io.IO
import akka.io.Inet._
import com.typesafe.config.Config
import org.json4s._
import org.json4s.JsonAST.JValue
import org.json4s.native.Serialization
import scala.concurrent.duration._
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.actor._
import java.lang.{Iterable => JIterable}

/**
 * GPSd Extension for Akka.
 *
 * In order to open an connection to a GPSd daemon service send a [[Gpsd.Connect]] message
 * to the [[GpsdExt#manager]].
 *
 * This is basically a TCP connection, so all [[akka.io.Inet.SocketOption]] applicable
 * socket options for TCP can be used.
 *
 * To close a connection send a [[akka.io.Tcp.Close]] message to the connection actor.
 * Also expect to receive [[akka.io.Tcp.Event]] messages regarding the connection state.
 *
 * The Java API for generating GPSd commands is available at [[GpsdMessage]].
 */
object Gpsd extends ExtensionId[GpsdExt] with ExtensionIdProvider {

  override def lookup = Gpsd

  override def createExtension(system: ExtendedActorSystem): GpsdExt = new GpsdExt(system)

  /**
   * Java API: retrieve the Gpsd extension for the given system.
   */
  override def get(system: ActorSystem): GpsdExt = super.get(system)

  /**
   * The Connect message is sent to the GPSd manager actor, which is obtained via
   * [[GpsdExt#manager]]. Either the manager replies with a [[akka.io.Tcp.CommandFailed]]
   * or the actor handling the new connection replies with a [[akka.io.Tcp.Connected]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to the `Tcp.SO` object for a list of all supported options.
   */
  case class Connect(remoteAddress: Option[InetSocketAddress] = None,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil,
                     timeout: Option[FiniteDuration] = None,
                     pullMode: Boolean = false)

  /**
   * The common interface for [[GpsdCommand]] and [[GpsdEvent]].
   */
  sealed trait GpsdObject {
    implicit val formats = Serialization.formats(NoTypeHints)

    /**
     * Serialize this message to JSON format.
     */
    def asJSON: String = Serialization.write(this)
  }

  /**
   * This is the common trait for all commands understood by GPSd actors.
   */
  trait GpsdCommand extends GpsdObject {

    /**
     * The command, in JSON format, to be sent.
     */
    def command: String
  }

  /**
   * Common interface for all events generated by the GPSd layer actors.
   */
  sealed trait GpsdEvent extends GpsdObject {

    /**
     * The event classifier. This is the `class` field of the message.
     */
    def classifier: String
  }

  /**
   * Request the version object.
   */
  object Version extends GpsdCommand {
    val command = "?VERSION;\n"
  }

  /**
   * The daemon ships a VERSION response to each client when the client first connects to it.
   *
   * @param release Public release level.
   * @param rev Internal revision-control level.
   * @param proto_major API major revision level.
   * @param proto_minor API minor revision level.
   * @param remote URL of the remote daemon reporting this version.
   *               If empty, this is the version of the local daemon.
   */
  case class Version(release: String, rev: String, proto_major: Int, proto_minor: Int, remote: Option[String])
    extends GpsdEvent {

    /**
     * Fixed: `VERSION`.
     */
    val classifier = "VERSION"

    /**
     * API major revision level.
     */
    def protocolMajor = proto_major

    /**
     * API minor revision level.
     */
    def protocolMinor = proto_minor
  }

  /**
   * Request a device list object.
   */
  object Devices extends GpsdCommand {
    val command = "?DEVICES;\n"
  }

  /**
   * A device list object.
   * The daemon occasionally ships a bare DEVICE object to the client (that is, one not inside a DEVICES wrapper).
   *
   * @param devices List of device descriptions.
   * @param remote URL of the remote daemon reporting the device set.
   *               If empty, this is a DEVICES response from the local daemon.
   */
  case class Devices(devices: List[Device], remote: Option[String]) extends GpsdEvent {

    /**
     * Fixed: `DEVICES`.
     */
    val classifier = "DEVICES"
  }

  /**
   * Request the watch status.
   */
  object Watch extends GpsdCommand {
    val command = "?WATCH;\n"
  }

  /**
   * The response describes the subscriber's policy.
   * The response will also include a DEVICES object.
   *
   * There is an additional boolean "timing" attribute which is undocumented because
   * that portion of the interface is considered unstable and for developer use only.
   *
   * @param enable Enable (true) or disable (false) watcher mode. Default is true.
   * @param json Enable (true) or disable (false) dumping of JSON reports. Default is false.
   * @param nmea Enable (true) or disable (false) dumping of binary packets as pseudo-NMEA. Default is false.
   * @param raw Controls 'raw' mode. When this attribute is set to 1 for a channel,
   *            gpsd reports the unprocessed NMEA or AIVDM data stream from whatever device is attached.
   *            Binary GPS packets are hex-dumped. RTCM2 and RTCM3 packets are not dumped in raw mode.
   *            When this attribute is set to 2 for a channel that processes binary data,
   *            gpsd reports the received data verbatim without hex-dumping.
   * @param scaled If true, apply scaling divisors to output before dumping; default is false.
   * @param split24 If true, aggregate AIS type24 sentence parts. If false, report each part as a separate JSON object,
   *                leaving the client to match MMSIs and aggregate. Default is false. Applies only to AIS reports.
   * @param pps If true, emit the TOFF JSON message on each cycle and a PPS JSON message when the device issues 1PPS.
   *            Default is false.
   * @param device If present, enable watching only of the specified device rather than all devices.
   *               Useful with raw and NMEA modes in which device responses aren't tagged.
   *               Has no effect when used with enable:false.
   * @param remote URL of the remote daemon reporting the watch set.
   *               If empty, this is a WATCH response from the local daemon.
   */
  case class Watch(enable: Option[Boolean], json: Option[Boolean], nmea: Option[Boolean], raw: Option[Int],
                   scaled: Option[Boolean], split24: Option[Boolean], pps: Option[Boolean], device: Option[String],
                   remote: Option[String], timing: Option[Boolean]) extends GpsdEvent {

    /**
     * Fixed: `WATCH`.
     */
    val classifier = "WATCH"

  }

  /**
   * This command sets the watcher mode. It also sets or elicits a report of per-subscriber policy and the raw bit.
   * An argument WATCH object changes the subscriber's policy.
   *
   * In watcher mode, GPS reports are dumped as TPV and SKY responses.
   */
  case class SetWatch(enable: Boolean, json: Option[Boolean] = None) extends GpsdCommand {
    val command = "?WATCH=" + asJSON + "\n"
  }

  /**
   * The POLL command requests data from the last-seen fixes on all active GPS devices.
   * Devices must previously have been activated by ?WATCH to be pollable.
   *
   * Polling can lead to possibly surprising results when it is used on a device such as an NMEA GPS
   * for which a complete fix has to be accumulated from several sentences.
   * If you poll while those sentences are being emitted, the response will contain the last complete fix data
   * and may be as much as one cycle time (typically 1 second) stale.
   */
  object Poll extends GpsdCommand {
    val command = "?POLL;\n"
  }

  /**
   * The POLL response will contain a timestamped list of TPV objects describing cached data,
   * and a timestamped list of SKY objects describing satellite configuration.
   * If a device has not seen fixes, it will be reported with a mode field of zero.
   *
   * @param time Time/date stamp, UTC.
   * @param active Count of active devices.
   * @param tpv List of `TPV` objects.
   * @param gst List of `GST` objects.
   * @param sky List of `SKY` objects.
   */
  case class Poll(time: Date, active: Int, tpv: List[TPV], gst: List[GST], sky: List[SKY]) extends GpsdEvent {

    /**
     * Fixed: `POLL`.
     */
    val classifier = "POLL"

    /**
     * Seconds since the Unix epoch, UTC. May have a fractional part of up to .001sec precision.
     */
    def timestamp = time.getTime / 1000.0

    /**
     * List of `TPV` objects.
     */
    def fixes = tpv

    /**
     * List of `SKY` objects.
     */
    def skyviews = sky
  }

  /**
   * This command reports the state of a device.
   */
  object Device extends GpsdCommand {
    val command = "?DEVICE;\n"
  }

  /**
   * When a client is in watcher mode, the daemon will ship it DEVICE notifications
   * when a device is added to the pool or deactivated.
   *
   * @param path Name the device for which the control bits are being reported, or for which they are to be applied.
   *             This attribute may be omitted only when there is exactly one subscribed channel.
   * @param activated Time/date stamp the device was activated. If the device is inactive this attribute is absent.
   * @param flags Bit vector of property flags. Currently defined flags are:
   *              describe packet types seen so far (GPS, RTCM2, RTCM3, AIS).
   *              Won't be reported if empty, e.g. before gpsd has seen identifiable packets from the device.
   * @param driver GPSD's name for the device driver type.
   *               Won't be reported before gpsd has seen identifiable packets from the device.
   * @param subtype Whatever version information the device returned.
   * @param bps Device speed in bits per second.
   * @param parity N, O or E for no parity, odd, or even.
   * @param stopbits Stop bits (1 or 2).
   * @param native 0 means NMEA mode and 1 means alternate mode (binary if it has one, for SiRF and Evermore chipsets in particular).
   *               Attempting to set this mode on a non-GPS device will yield an error.
   * @param cycle Device cycle time in seconds.
   * @param mincycle Device minimum cycle time in seconds. Reported from ?DEVICE when (and only when) the rate is switchable.
   *                 It is read-only and not settable.
   */
  case class Device(path: Option[String], activated: Option[Date], flags: Option[Int], driver: Option[String],
                    subtype: Option[String], bps: Option[Int], parity: String, stopbits: Int, native: Option[Int],
                    cycle: Option[Double], mincycle: Option[Double]) extends GpsdEvent {

    /**
     * Fixed: `DEVICE`.
     */
    val classifier = "DEVICE"

    /**
     * Seconds since the Unix epoch, UTC the device was activated.
     * If the device is inactive this attribute is absent.
     */
    def active = activated.map(_.getTime / 1000.0)
  }

  /**
   * The daemon may ship an error object in response to a syntactically invalid command line or unknown command.
   *
   * @param message Textual error message.
   */
  case class Error(message: String) extends GpsdEvent {

    /**
     * Fixed: `ERROR`.
     */
    val classifier = "ERROR"
  }

  /**
   * All received objects that cannot be de-serialized are wrapped in this object.
   */
  case class Unknown(sentence: JValue) extends GpsdObject {
    override def toString = Serialization.write(sentence)
  }

  object NMEAMode extends Enumeration {
    type Mode = Value
    val NotSeen, NoFix, TwoDimensional, ThreeDimensional = Value
  }

  /**
   * A TPV object is a time-position-velocity report. The `class` and `mode` fields will reliably be present.
   * The `mode` field will be emitted before optional fields that may be absent when there is no fix.
   * Error estimates will be emitted after the fix components they're associated with.
   * Others may be reported or not depending on the fix quality.
   *
   * @param device Name of originating device.
   * @param mode NMEA mode.
   * @param time Time/date stamp, UTC. May be absent if mode is not `TwoDimensional` or `ThreeDimensional`.
   * @param ept Estimated timestamp error in seconds, 95% confidence. Present if time is present.
   * @param lat Latitude in degrees: +/- signifies North/South. Present when mode is `TwoDimensional` or `ThreeDimensional`.
   * @param lon Longitude in degrees: +/- signifies East/West. Present when mode is `TwoDimensional` or `ThreeDimensional`.
   * @param alt Altitude in meters. Present when mode is `ThreeDimensional`.
   * @param epx Longitude error estimate in meters, 95% confidence.
   *            Present if mode is `TwoDimensional` or `ThreeDimensional` and DOPs can be calculated from the satellite view.
   * @param epy Latitude error estimate in meters, 95% confidence.
   *            Present if mode is `TwoDimensional` or `ThreeDimensional` and DOPs can be calculated from the satellite view.
   * @param epv Estimated vertical error in meters, 95% confidence.
   *            Present if mode is `ThreeDimensional` and DOPs can be calculated from the satellite view.
   * @param track Course over ground, degrees from true north.
   * @param speed Speed over ground, meters per second.
   * @param climb Climb (positive) or sink (negative) rate, meters per second.
   * @param epd Direction error estimate in degrees, 95% confidence.
   * @param eps Speed error estimate in meters/sec, 95% confidence.
   * @param epc Climb/sink error estimate in meters/sec, 95% confidence.
   */
  case class TPV(tag: String, device: Option[String], mode: NMEAMode.Mode, time: Option[Date], ept: Option[Double],
                 lat: Option[Double], lon: Option[Double], alt: Option[Double], epx: Option[Double],
                 epy: Option[Double], epv: Option[Double], track: Option[Double], speed: Option[Double],
                 climb: Option[Double], epd: Option[Double], eps: Option[Double], epc: Option[Double]) extends GpsdEvent {

    val classifier = "TPV"

    /**
     * Seconds since the Unix epoch, UTC.
     * May have a fractional part of up to .001sec precision.
     * May be absent if mode is not `TwoDimensional` or `ThreeDimensional`.
     */
    def timestamp = time.map(_.getTime / 1000.0)

    /**
     * Estimated timestamp error in seconds, 95% confidence.
     * Present if time is present.
     */
    def timestampError = ept

    /**
     * Latitude in degrees: +/- signifies North/South.
     * Present when mode is `TwoDimensional` or `ThreeDimensional`.
     */
    def latitude = lat

    /**
     * Longitude in degrees: +/- signifies East/West.
     * Present when mode is `TwoDimensional` or `ThreeDimensional`.
     */
    def longitude = lon

    /**
     * Altitude in meters.
     * Present when mode is `ThreeDimensional`.
     */
    def altitude = alt

    /**
     * Longitude error estimate in meters, 95% confidence.
     * Present if mode is `TwoDimensional` or `ThreeDimensional` and DOPs can be calculated from the satellite view.
     */
    def longitudeError = epx

    /**
     * Latitude error estimate in meters, 95% confidence.
     * Present if mode is `TwoDimensional` or `ThreeDimensional` and DOPs can be calculated from the satellite view.
     */
    def latitudeError = epy

    /**
     * Estimated vertical error in meters, 95% confidence.
     * Present if mode is `ThreeDimensional` and DOPs can be calculated from the satellite view.
     */
    def altitudeError = epv

    /**
     * Course over ground, degrees from true north.
     */
    def course = track

    /**
     * Climb (positive) or sink (negative) rate, meters per second.
     */
    def climbRate = climb

    /**
     * Direction error estimate in degrees, 95% confidence.
     */
    def courseError = epd

    /**
     * Speed error estimate in meters/sec, 95% confidence.
     */
    def speedError = eps

    /**
     * Climb/sink error estimate in meters/sec, 95% confidence.
     */
    def climbRateError = epc
  }

  /**
   * A SKY object reports a sky view of the GPS satellite positions.
   * If there is no GPS device available, or no skyview has been reported yet, only the `class` field will reliably be present.
   *
   * Many devices compute dilution of precision factors but do not include them in their reports.
   * Many that do report DOPs report only HDOP, two-dimensional circular error.
   * gpsd always passes through whatever the device actually reports, then attempts to fill in other DOPs by calculating
   * the appropriate determinants in a covariance matrix based on the satellite view.
   * DOPs may be missing if some of these determinants are singular.
   * It can even happen that the device reports an error estimate in meters when the corresponding DOP is unavailable;
   * some devices use more sophisticated error modeling than the covariance calculation.
   *
   * @param device Name of originating device.
   * @param time Time/date stamp, UTC.
   * @param xdop Longitudinal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param ydop Latitudinal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param vdop Altitude dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param tdop Time dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param hdop Horizontal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get a circular error estimate.
   * @param pdop Spherical dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param gdop Hyperspherical dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
   * @param satellites List of satellite objects in skyview.
   */
  case class SKY(tag: String, device: Option[String], time: Option[Date], xdop: Option[Double], ydop: Option[Double],
                 vdop: Option[Double], tdop: Option[Double], hdop: Option[Double], pdop: Option[Double],
                 gdop: Option[Double], satellites: List[Satellite]) extends GpsdEvent {

    /**
     * Fixed: `SKY`.
     */
    val classifier = "SKY"

    /**
     * Seconds since the Unix epoch, UTC.
     * May have a fractional part of up to .001sec precision.
     */
    def timestamp = time.map(_.getTime / 1000.0)

    /**
     * Longitudinal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def longitudeDOP = xdop

    /**
     * Latitudinal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def latitudeDOP = ydop

    /**
     * Altitude dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def altitudeDOP = vdop

    /**
     * Time dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def timestampDOP = tdop

    /**
     * Horizontal dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get a circular error estimate.
     */
    def horizontalDOP = hdop

    /**
     * Spherical dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def sphericalDOP = pdop

    /**
     * Hyperspherical dilution of precision, a dimensionless factor which should be multiplied by a base UERE to get an error estimate.
     */
    def hypersphericalDOP = gdop
  }

  /**
   * Satellite object.
   *
   * @param PRN PRN ID of the satellite.
   *            1-63 are GNSS satellites, 64-96 are GLONASS satellites, 100-164 are SBAS satellites.
   * @param az Azimuth, degrees from true north.
   * @param el Elevation in degrees.
   * @param ss Signal strength in dB.
   * @param used Used in current solution?
   *             (SBAS/WAAS/EGNOS satellites may be flagged used if the solution has corrections from them,
   *             but not all drivers make this information available.)
   */
  case class Satellite(PRN: Int, az: Int, el: Int, ss: Int, used: Boolean) extends GpsdObject {

    /**
     * Azimuth, degrees from true north.
     */
    def azimuth = az

    /**
     * Elevation in degrees.
     */
    def elevation = el

    /**
     * Signal strength in dB.
     */
    def signalStrength = ss
  }

  /**
   * A GST object is a pseudorange noise report.
   *
   * @param device Name of originating device.
   * @param time Time/date stamp, UTC.
   * @param rms Value of the standard deviation of the range inputs to the navigation process
   *            (range inputs include pseudoranges and DGPS corrections).
   * @param major Standard deviation of semi-major axis of error ellipse, in meters.
   * @param minor Standard deviation of semi-minor axis of error ellipse, in meters.
   * @param orient Orientation of semi-major axis of error ellipse, in degrees from true north.
   * @param lat Standard deviation of latitude error, in meters.
   * @param lon Standard deviation of longitude error, in meters.
   * @param alt Standard deviation of altitude error, in meters.
   */
  case class GST(tag: String, device: Option[String], time: Option[Date], rms: Option[Double], major: Option[Double],
                 minor: Option[Double], orient: Option[Double], lat: Option[Double], lon: Option[Double],
                 alt: Option[Double]) extends GpsdEvent {

    /**
     * Fixed: `GST`.
     */
    val classifier = "GST"

    /**
     * Seconds since the Unix epoch, UTC.
     * May have a fractional part of up to .001sec precision.
     */
    def timestamp = time.map(_.getTime / 1000.0)

  }

  /**
   * An ATT object is a vehicle-attitude report.
   * It is returned by digital-compass and gyroscope sensors; depending on device, it may include:
   * heading, pitch, roll, yaw, gyroscope, and magnetic-field readings.
   * Because such sensors are often bundled as part of marine-navigation systems,
   * the ATT response may also include water depth.
   *
   * The `class` and `mode` fields will reliably be present.
   * Others may be reported or not depending on the specific device type.
   *
   * The heading, pitch, and roll status codes (if present) vary by device.
   *
   * @param device Name of originating device.
   * @param time Time/date stamp, UTC.
   * @param heading Heading, degrees from true north.
   * @param mag_st Magnetometer status.
   * @param pitch Pitch in degrees.
   * @param pitch_st Pitch sensor status.
   * @param yaw Yaw in degrees.
   * @param yaw_st Yaw sensor status.
   * @param roll Roll in degrees.
   * @param roll_st Roll sensor status.
   * @param dip Local magnetic inclination, degrees, positive when the magnetic field points downward (into the Earth).
   * @param mag_len Scalar magnetic field strength.
   * @param mag_x X component of magnetic field strength.
   * @param mag_y Y component of magnetic field strength.
   * @param mag_z Z component of magnetic field strength.
   * @param acc_len Scalar acceleration.
   * @param acc_x X component of acceleration.
   * @param acc_y Y component of acceleration.
   * @param acc_z Z component of acceleration.
   * @param gyro_x X component of acceleration.
   * @param gyro_y Y component of acceleration.
   * @param depth Water depth in meters.
   * @param temperature Temperature at sensor, degrees centigrade.
   */
  case class ATT(tag: String, device: String, time: Date, heading: Option[Double], mag_st: Option[String],
                 pitch: Option[Double], pitch_st: Option[String], yaw: Option[Double], yaw_st: Option[String],
                 roll: Option[Double], roll_st: Option[String], dip: Option[Double], mag_len: Option[Double],
                 mag_x: Option[Double], mag_y: Option[Double], mag_z: Option[Double], acc_len: Option[Double],
                 acc_x: Option[Double], acc_y: Option[Double], acc_z: Option[Double], gyro_x: Option[Double],
                 gyro_y: Option[Double], depth: Option[Double], temperature: Option[Double]) extends GpsdEvent {

    /**
     * Fixed: `ATT`.
     */
    val classifier = "ATT"

    /**
     * Seconds since the Unix epoch, UTC.
     * May have a fractional part of up to .001sec precision.
     */
    def timestamp = time.getTime / 1000.0

    /**
     * Magnetometer status.
     */
    def magState = mag_st

    /**
     * Pitch sensor status.
     */
    def pitchState = pitch_st

    /**
     * Yaw sensor status.
     */
    def yawState = yaw_st

    /**
     * Roll sensor status.
     */
    def rollState = roll_st

  }

  /**
   * This message is emitted on each cycle and reports the offset between the host's clock time and the GPS time
   * at top of second (actually, when the first data for the reporting cycle is received).
   *
   * This message exactly mirrors the PPS message except for two details.
   *
   * TOFF emits no NTP precision, this is assumed to be -2. See the NTP documentation for their definition of precision.
   *
   * The TOFF message reports the GPS time as derived from the GPS serial data stream.
   * The PPS message reports the GPS time as derived from the GPS PPS pulse.
   *
   * This message is emitted once per second to watchers of a device and is intended
   * to report the time stamps of the in-band report of the GPS and seconds as reported by the system clock
   * (which may be NTP-corrected) when the first valid timestamp of the reporting cycle was seen.
   *
   * The message contains two second/nanosecond pairs: real_sec and real_nsec contain the time
   * the GPS thinks it was at the start of the current cycle; clock_sec and clock_nsec contain the time
   * the system clock thinks it was on receipt of the first timing message of the cycle.
   * real_nsec is always to nanosecond precision. clock_nsec is nanosecond precision on most systems.
   *
   * @param device Name of originating device.
   * @param real_sec seconds from the GPS clock.
   * @param real_nsec nanoseconds from the GPS clock.
   * @param clock_sec seconds from the system clock.
   * @param clock_nsec nanoseconds from the system clock.
   */
  case class TOFF(tag: String, device: String, real_sec: Int, real_nsec: Int,
                  clock_sec: Int, clock_nsec: Int) extends GpsdEvent {

    /**
     * Fixed: `TOFF`.
     */
    val classifier = "TOFF"
  }

  /**
   * This message is emitted each time the daemon sees a valid PPS (Pulse Per Second) strobe from a device.
   *
   * This message exactly mirrors the PPS message except for two details.
   *
   * PPS emits the NTP precision. See the NTP documentation for their definition of precision.
   *
   * The TOFF message reports the GPS time as derived from the GPS serial data stream.
   * The PPS message reports the GPS time as derived from the GPS PPS pulse.
   *
   * There are various sources of error in the reported clock times.
   * The speed of the serial connection between the GPS and the system adds a delay to start of cycle detection.
   * An even bigger error is added by the variable computation time inside the GPS.
   * Taken together the time derived from the start of the GPS cycle can have offsets of
   * 10 millisecond to 700 milliseconds and combined jitter and wander of 100 to 300 millisecond.
   *
   * This message is emitted once per second to watchers of a device emitting PPS,
   * and reports the time of the start of the GPS second (when the 1PPS arrives)
   * and seconds as reported by the system clock (which may be NTP-corrected) at that moment.
   *
   * The message contains two second/nanosecond pairs: real_sec and real_nsec contain the time
   * the GPS thinks it was at the PPS edge; clock_sec and clock_nsec contain the time
   * the system clock thinks it was at the PPS edge. real_nsec is always to nanosecond precision.
   * clock_nsec is nanosecond precision on most systems.
   *
   * There are various sources of error in the reported clock times.
   * For PPS delivered via a real serial-line strobe, serial-interrupt latency plus processing time to the timer call
   * should be bounded above by about 10 microseconds; that can be reduced to less than 1 microsecond
   * if your kernel supports RFC 2783. USB1.1-to-serial control-line emulation is limited to about 1 millisecond. seconds.
   *
   * @param device Name of originating device.
   * @param real_sec seconds from the PPS source.
   * @param real_nsec nanoseconds from the PPS source.
   * @param clock_sec seconds from the system clock.
   * @param clock_nsec nanoseconds from the system clock.
   * @param precision NTP style estimate of PPS precision.
   */
  case class PPS(tag: String, device: String ,real_sec: Int, real_nsec: Int,
                 clock_sec: Int, clock_nsec: Int, precision: Int) extends GpsdEvent {

    /**
     * Fixed: `PPS`.
     */
    val classifier = "PPS"
  }

}

class GpsdExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.gpsd"))

  class Settings private[GpsdExt](_config: Config) {

    import _config._

    val DefaultHostname: String = getString("hostname")
    val DefaultPort: Int = getInt("port")
    val Sentences: Map[String, String] =
      (for (e <- getConfig("json").entrySet().asScala) yield (e.getKey, e.getValue.unwrapped.asInstanceOf[String])).toMap
  }

  /**
   *
   */
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

  import language.implicitConversions
  import akka.io.{Tcp, TcpSO}
  import Gpsd._

  /**
   * The Connect message is sent to the GPSd manager actor, which is obtained via
   * [[GpsdExt#getManager]]. Either the manager replies with a [[Tcp.CommandFailed]]
   * or the actor handling the new connection replies with a [[Tcp.Connected]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[TcpSO]] for a list of all supported options.
   * @param timeout is the desired connection timeout, `null` means "no timeout"
   * @param pullMode enables pull based reading from the connection
   */
  def connect(remoteAddress: InetSocketAddress,
              localAddress: InetSocketAddress,
              options: JIterable[SocketOption],
              timeout: FiniteDuration,
              pullMode: Boolean): Connect = Connect(Option(remoteAddress), Option(localAddress), options, Option(timeout), pullMode)

  /**
   * Connect to the given `remoteAddress` of the GPSd daemon service without binding to a local address and without
   * specifying options.
   */
  def connect(remoteAddress: InetSocketAddress): Connect = Connect(Option(remoteAddress), None, Nil, None, pullMode = false)

  /**
   * Connect to the default GPSd daemon service.
   */
  def connect(): Connect = Connect(None, None, Nil, None, pullMode = false)

  /**
   * Request the version object.
   */
  def version(): GpsdCommand = Version

  /**
   * Request a device list object.
   */
  def devices(): GpsdCommand = Devices

  /**
   * Request the watch status.
   */
  def watch(): GpsdCommand = Watch

  /**
   * Enable / disable watch for polling.
   */
  def watch(enable: Boolean): GpsdCommand = SetWatch(enable, None)

  /**
   * Enable / disable watch for pushing.
   */
  def watch(enable: Boolean, json: Boolean): GpsdCommand = SetWatch(enable, Some(json))

  /**
   * Issue a poll.
   */
  def poll(): GpsdCommand = Poll

  /**
   * Request the state of a device.
   */
  def device(): GpsdCommand = Device

  implicit private def fromJava[T](coll: JIterable[T]): immutable.Traversable[T] = {
    akka.japi.Util.immutableSeq(coll)
  }
}