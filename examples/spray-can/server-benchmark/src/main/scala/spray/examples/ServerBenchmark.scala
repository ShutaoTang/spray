package spray.examples

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import spray.can.Http

object ServerBenchmark extends App {

  /**
   * system used as the implicit parameter of
   *   IO.apply(key: ExtensionId[T])(implicit system: ActorSystem): ActorRef
   */
  implicit val system = ActorSystem("benchmark")

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props[BenchmarkService], name = "handler")

  val interface = system.settings.config.getString("app.interface")
  val port = system.settings.config.getInt("app.port")

  /**
   * For IDEA 2018.2 or higher
   *    Ctrl + Alt + Shift + "+"  expands all the implicit conversion
   *    Ctrl + Alt + Shift + "-"  will collapse all foldings or disable the above mode
   *
   *    Ctrl + Shift + P shows type, structure, and location of implicit arguments.
   *
   *    Scalafmt as an alternative to the built-in formatter, see Settings | Editor | Code Style | Scala
   */
  IO(Http) ! Http.Bind(handler, interface, port)
}
