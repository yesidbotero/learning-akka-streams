package actors

import actors.basicActor.Message
import akka.actor.{Actor, Props}

/**
  * Created by yesidbotero on 9/7/17.
  */
object basicActor {
  case class Message(msg: String);

  def props() = {
    Props(new basicActor())
  }
}


class basicActor extends Actor {
  override def receive: Receive = {
    case Message(x) if x == "done" => {
      context.stop(self)
    }
    case Message(x) => println("printing in basicActor: " + x)
  }
}
