package spray.examples

import scala.concurrent.duration._
import akka.actor.{Actor, ActorLogging}
import spray.can.Http
import spray.json._
import spray.http._
import HttpMethods._
import StatusCodes._

class BenchmarkService extends Actor with ActorLogging {
  import context.dispatcher // ExecutionContext for scheduler
  import Uri._
  import Uri.Path._

  def jsonResponseEntity = HttpEntity(
    contentType = ContentTypes.`application/json`,
    string = JsObject("message" -> JsString("Hello, World!")).compactPrint)

  def fastPath: Http.FastPath = {
    case HttpRequest(GET, Uri(_, _, Slash(Segment("fast-ping", Path.Empty)), _, _), _, _, _) =>
      HttpResponse(entity = "FAST-PONG!")

    case HttpRequest(GET, Uri(_, _, Slash(Segment("fast-json", Path.Empty)), _, _), _, _, _) =>
      HttpResponse(entity = jsonResponseEntity)
  }

  def receive: Receive = {
    /**
     * Note that below sender() is
     *   LocalActorRef --> Actor[akka://default/user/IO-HTTP/listener-0/3#-1099396836]
     *   ActorPath of parent --> /user/IO-HTTP/listener-0/
     *   name                --> 3#-1099396836
     * which is an instance of HttpServerConnection and it is the child of HttpListener.
     * see [[spray.can.server.HttpListener]] and [[spray.can.server.HttpServerConnection]]
     */
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected =>
      log.info("message Http.Connected from sender() >>> {}", sender)
      sender ! Http.Register(self, fastPath = fastPath)

    case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      log.info("message HttpRequest from sender >>> {}}", sender)
      sender ! HttpResponse(
        entity = HttpEntity(MediaTypes.`text/html`,
          <html>
            <body>
              <h1>Tiny <i>spray-can</i> benchmark server</h1>
              <p>Defined resources:</p>
              <ul>
                <li><a href="/ping">/ping</a></li>
                <li><a href="/fast-ping">/fast-ping</a></li>
                <li><a href="/json">/json</a></li>
                <li><a href="/fast-json">/fast-json</a></li>
                <li><a href="/stop">/stop</a></li>
              </ul>
            </body>
          </html>.toString()
      )
    )

    /**
     * Note that below sender() is a temp actor, ResponseReceiverRef --> Actor[akka://default/temp/$b]
     * see [[akka.spray.UnregisteredActorRefBase]] and [[spray.can.server.OpenRequestComponent.DefaultOpenRequest]]
     * or the function openNewRequest(...) in [[spray.can.server.ServerFrontend]]
     */
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _) => sender ! HttpResponse(entity = "PONG!")

    case HttpRequest(GET, Uri.Path("/json"), _, _, _) => sender ! HttpResponse(entity = jsonResponseEntity)

    case HttpRequest(GET, Uri.Path("/stop"), _, _, _) =>
      sender ! HttpResponse(entity = "Shutting down in 1 second ...")
      context.system.scheduler.scheduleOnce(1.second) {
        context.system.shutdown()
      }

    case _: HttpRequest => sender ! HttpResponse(NotFound, entity = "Unknown resource!")
  }
}
