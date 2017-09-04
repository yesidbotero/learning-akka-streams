import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.scalatest.FunSuite
import scala.concurrent.duration.Duration

import scala.concurrent.{Await, Future}

class BasicSuite extends FunSuite {
  implicit val actorSystem = ActorSystem("ActorsSystem")
  implicit val materializer = ActorMaterializer()

  test("A Source emits elements on demand") {
    val source: Source[Int, NotUsed] = Source(1 to 10)

    assertCompiles("source.runForeach(println)")
  }

  test("A Source should be able to run privideing it a sink") {
    val sink: Sink[Int, Future[Done]] = Sink.foreach(x => println("receiving: " + x))
    val source: Source[Int, NotUsed] = Source(10 to 20)

    assertCompiles("source.runWith(sink)")
  }

  test("A Source connects to a flow") {
    val source: Source[Int, NotUsed] = Source(1 to 3)
    val isPairFlow: Flow[Int, String, NotUsed] = Flow[Int].map { case n if n % 2 == 0 => "Par "
    case _ => "Impar " }
    val sink: Sink[String, Future[String]] = Sink.fold("")(_ + _)
    val result: Future[String] = source.via(isPairFlow).runWith(sink)

    assert(Await.result(result, Duration.Inf) == "Impar Par Impar ")
  }

}
