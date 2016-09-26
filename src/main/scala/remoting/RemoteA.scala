package remoting
/*
* Two actors: remoteA, a server class, and LogActor which keeps chat history.
 */

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{ Promise, ExecutionContext }


// A main object to launch the server with.
object remoteInit extends App {
  def init{
    val system = ActorSystem("ChatastropheRemoteActorSys")
    val remoteActor = system.actorOf(RemoteA.props, "remoteActor")
    println("remote actor up. . . ")
  }
}



/*
* LogActor is used to keep a chat sessions, chat history.
 */
case class WriteToLog(text: String)
case object ReadFromLog

class LogActor extends Actor {

  private var log: String = ""

  private def write(text: String): Unit =  log += text + "\n"

  def receive = {
    case WriteToLog(text) => write(text)
    case ReadFromLog => sender ! log
    case GUI_Request => sender ! log
  }
}


/*
 * Remote actor is our server, clients actors may connect to it.
 * The communication protocol is matched through a RemoteCommand abstract case class.
 */
sealed abstract class RemoteCommand
  case class Connect(address: String, name: String, actorRef: ActorRef) extends RemoteCommand // only used by client (localActor).
  case class SendMessage(text: String) extends RemoteCommand
  case class ReceiveMessage(text: String) extends RemoteCommand
  case class Disconnect(user: String) extends RemoteCommand
  case class BroadcastIncoming(text: String) extends RemoteCommand

  case object GUI_Request extends RemoteCommand
  case object Broadcast extends RemoteCommand
  case object Poll extends RemoteCommand

object RemoteA {
  def props = Props(new RemoteA)
}

class RemoteA extends Actor {
  implicit val ec = ExecutionContext.global
  implicit val timeout = Timeout(5 seconds)

  // A log actor from which clients may poll
  val logActor = context.actorOf(Props(classOf[LogActor]))

  // A map of connected clients
  var connections = Map.empty[String, ActorRef] // TODO: can this be a val not var??

  // Upon receiveing a message
  def receive = {
    case command: RemoteCommand => command match {
      case Connect(address, user, actorRef) =>  // A user joins 
        connections += user -> actorRef
        self ! ReceiveMessage("user: " + user + " joined.\n")

      case ReceiveMessage(text) => // A user sent a message to the server
        logActor ! WriteToLog(text)
        self ! BroadcastIncoming(text)

      case Broadcast  =>   // Send latest chat messages to all users. TODO: Perhaps obsolete
        val f = (logActor ? ReadFromLog).mapTo[String]
        val p = Promise[String]()
        p completeWith f
        p.future onSuccess {
          case s =>
            connections foreach { e =>
              val(user, actorRef) = e
              actorRef ! ReceiveMessage(s)
            }
        }

      case BroadcastIncoming(text) =>  // Push all incoming messages out to each client.
        connections foreach {
          client =>
            val(user, actorRef) = client 
            actorRef ! ReceiveMessage(s"${user}: ${text}")
        }

      case Poll => {  // Same as Broadcast except it only sends to one other.
        val f = (logActor ? ReadFromLog).mapTo[String]
        val p = Promise[String]()
        p completeWith f
        p.future onSuccess {
          case s =>
            sender ! ReceiveMessage(s)
        }
     }

      case Disconnect(user) =>    //  Signal received from a client when they wish to disconnect from the server.
        connections -= user
        self ! ReceiveMessage("user " + user + " disconneccted")

      case GUI_Request =>
        logActor forward GUI_Request
    } 

    case DeadLetter(msg, from, to) =>
      println("RECEIVED DEAD LETTER LocalA")

    case any: Any => println("received " + any); sender ! ReceiveMessage("unsupported message (" + any + ")")


  }
}
