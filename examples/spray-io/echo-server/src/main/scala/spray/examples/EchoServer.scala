package spray.examples

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import akka.pattern.ask
import akka.io.{Tcp, IO}
import akka.util.{ByteString, Timeout}
import akka.actor._

object EchoServer extends App {
  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("echo-server")
  implicit val bindingTimeout = Timeout(10.second)
  implicit val executionContext = system.dispatcher // execution context for the future

  // and our actual server "service" actor
  val server = system.actorOf(Props[EchoServerActor], name = "echo-server")

  // we bind the server to a port on localhost and hook
  // in a continuation that informs us when bound
  val endpoint = new InetSocketAddress("localhost", 23456)
  val boundFuture = IO(Tcp) ? Tcp.Bind(server, endpoint)

  boundFuture.onSuccess { case Tcp.Bound(address) =>
    println("\nBound echo-server to " + address)
    println("Run `telnet localhost 23456`, type something and press RETURN. Type `STOP` to exit...\n")
  }
}

class EchoServerActor extends Actor with ActorLogging {
  var childrenCount = 0

  def receive = {
    case Tcp.Connected(_, _) =>
      val tcpConnection = sender
      val serverConnection = context.actorOf(Props(new EchoServerConnection(tcpConnection)))
      context.watch(serverConnection)

      childrenCount += 1
      sender ! Tcp.Register(serverConnection)
      log.debug("Registered for new connection")

    case Terminated(_) if childrenCount > 0 =>
      childrenCount -= 1
      log.debug("Connection handler stopped, another {} connections open", childrenCount)

    case Terminated(_) =>
      log.debug("Last connection handler stopped, shutting down")
      context.system.shutdown()
  }
}

class EchoServerConnection(tcpConnection: ActorRef) extends Actor with ActorLogging {
  context.watch(tcpConnection)

  def receive = idle

  def idle: Receive = stopOnConnectionTermination orElse {
    case Tcp.Received(data) if data.utf8String.trim == "STOP" =>
      log.info("Shutting down")
      tcpConnection ! Tcp.Write(ByteString("Shutting down...\n"))
      tcpConnection ! Tcp.Close

    case Tcp.Received(data) =>
      tcpConnection ! Tcp.Write(data, ack = SentOk)
      context.become(waitingForAck)

    case evt: Tcp.ConnectionClosed =>
      log.debug("Connection closed: {}", evt)
      context.stop(self)
  }

  def waitingForAck: Receive = stopOnConnectionTermination orElse {
    case Tcp.Received(data) =>
      tcpConnection ! Tcp.SuspendReading
      context.become(waitingForAckWithQueuedData(Queue(data)))

    case SentOk =>
      context.become(idle)

    case evt: Tcp.ConnectionClosed =>
      log.debug("Connection closed: {}, waiting for pending ACK", evt)
      context.become(waitingForAckWithQueuedData(Queue.empty, closed = true))
  }

  def waitingForAckWithQueuedData(queuedData: Queue[ByteString], closed: Boolean = false): Receive =
    stopOnConnectionTermination orElse {
      case Tcp.Received(data) =>
        context.become(waitingForAckWithQueuedData(queuedData.enqueue(data)))

      case SentOk if queuedData.isEmpty && closed =>
        log.debug("No more pending ACKs, stopping")
        tcpConnection ! Tcp.Close
        context.stop(self)

      case SentOk if queuedData.isEmpty =>
        tcpConnection ! Tcp.ResumeReading
        context.become(idle)

      case SentOk =>
        // for brevity we don't interpret STOP commands here
        tcpConnection ! Tcp.Write(queuedData.head, ack = SentOk)
        context.become(waitingForAckWithQueuedData(queuedData.tail, closed))

      case evt: Tcp.ConnectionClosed =>
        log.debug("Connection closed: {}, waiting for completion of {} pending writes", evt, queuedData.size)
        context.become(waitingForAckWithQueuedData(queuedData, closed = true))
    }

  def stopOnConnectionTermination: Receive = {
    case Terminated(`tcpConnection`) =>
      log.debug("TCP connection actor terminated, stopping...")
      context.stop(self)
  }
  
  object SentOk extends Tcp.Event
}